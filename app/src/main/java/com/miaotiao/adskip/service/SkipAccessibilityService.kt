package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.miaotiao.adskip.data.AppLists
import com.miaotiao.adskip.data.LearnStore
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.data.RuleRepository
import com.miaotiao.adskip.engine.Access
import com.miaotiao.adskip.engine.AppRule
import com.miaotiao.adskip.engine.Matcher
import com.miaotiao.adskip.engine.RuleSet
import com.miaotiao.adskip.engine.SplashRule
import com.miaotiao.adskip.engine.SplashScanner
import com.miaotiao.adskip.net.RemoteRules
import com.miaotiao.adskip.util.SkipLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跳广告服务。
 *
 * v2 相比 v1 的三处关键改动：
 *
 * 1. 扫描不再跑在主线程
 *    无障碍回调跑在主线程，而遍历节点树是一次几百次 IPC 的操作。原来在主线程扫描，
 *    会把后续事件全部堵住——表现就是「点了但延迟一两秒」「有时候完全没反应」。
 *    现在事件回调只做「标记 + 投递任务」，真正的遍历和点击都在单独线程里。
 *
 * 2. 事件驱动 + 轮询互补
 *    除了固定间隔轮询，界面上任何内容变化（TYPE_WINDOW_CONTENT_CHANGED）都会立刻
 *    触发一次扫描——广告渲染完成的瞬间就能被抓住，而不是等下一个 poll 周期。
 *
 * 3. 规则预编译
 *    关键词在加载规则时就编译好，扫描时零字符串分配，单次扫描从几十毫秒降到几毫秒。
 *
 * 另外增加了「自动学习」：规则没命中但启发式认出按钮时，自动记下控件 id / 坐标，
 * 下次直接命中——相当于自己给自己补规则。
 */
class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkip"

        @Volatile
        var instance: SkipAccessibilityService? = null
            private set

        /**
         * 这些包名永远不扫。
         *
         * 起因很具体：在系统设置页里，我们自己的无障碍服务描述里就写着
         * 「自动点击开屏广告的「跳过 / 关闭」按钮」，结果被当成开屏按钮点了一下。
         * 系统设置、权限弹窗、安装器这类页面本来也没有开屏广告，直接排除最省事。
         */
        private val NEVER_SCAN = setOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.android.providers.settings",
            "com.android.documentsui",
            "com.android.systemui",
            // 控制中心 / 通知中心的插件包：下拉一次就发一次窗口事件，
            // 不排除的话每拉一下都要起一轮扫描（实测 learned_rules 里还学错了控件）
            "miui.systemui.plugin",
            // 全局搜索 / 搜索框：长按 Home、上滑搜索都会拉起，
            // 没有开屏广告，但会频繁发窗口事件
            "com.android.quicksearchbox",
            "com.google.android.googlequicksearchbox",
            "com.miui.globalsearch",
            "com.miui.personalassistant",
            "com.miui.home",
            // 相册 / 文件管理：界面全是「关闭 / 更多 / 分享」，且没有开屏广告
            "com.miui.gallery",
            "com.android.documentsui",
            "com.miui.mediaeditor"
        )

        /**
         * 输入法的兜底名单。
         *
         * 键盘弹出/收起会发 TYPE_WINDOW_STATE_CHANGED，包名是输入法而不是当前 App。
         * 旧逻辑把它当成「换了应用」，于是微信聊天时每弹一次键盘就起一轮 3.6 秒扫描，
         * 一天下来几百轮，既费电也容易触发系统的后台活动提醒。
         * 这里除了动态查询已安装输入法，再兜一层常见包名，防止查询失败。
         */
        private val IME_FALLBACK = setOf(
            "com.sohu.inputmethod.sogou",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.baidu.input",
            "com.baidu.input_mi",
            "com.iflytek.inputmethod",
            "com.iflytek.inputmethod.miui",
            "com.tencent.qqpinyin",
            "com.tencent.wetype",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.miui.inputmethod",
            "com.miui.inputmethod.miui"
        )

        /**
         * 同一个包两次开屏扫描的最小间隔（5 秒）。
         *
         * 开屏广告只出现在冷启动，5 秒内重复扫描同一个包基本都是同一次打开，
         * 或者用户在 App 内切页面。给个冷却，避免「倒计时刷新 → 事件 → 重新扫描」自激。
         */
        private const val SAME_PKG_COOLDOWN_MS = 5_000L

        /** 两次点击之间的最小间隔，防止「一轮里连点好几个候选」 */
        private const val CLICK_GAP_MS = 1_200L

        /** 自识别最多只在开屏窗口的头 2.6 秒里用（规则命中不受限） */
        private const val HEURISTIC_WINDOW_MS = 2_600L

        /**
         * 整树遍历扫描的最小间隔。
         *
         * 整树遍历是唯一能匹配纯文本按钮的路径（系统文本检索已从快路径移除），
         * 原来的「第 0/2/5/9 轮」固定触发对 oopz 这类「广告来得很晚」的 App
         * 根本不够——广告 5 秒后才出现，那时全扫窗口早就过了。
         * 改成按时间节流：500ms 一次，开屏页只要几毫秒，代价可忽略。
         */
        private const val FULL_SCAN_GAP_MS = 500L

        /** 事件通道「活着」的判定窗口：这段时间内收到过事件就不急着轮询 */
        private const val EVENT_ALIVE_MS = 5_000L

        /** 事件通道正常时的轮询间隔（只做兜底，尽量省电） */
        private const val POLL_SLOW_MS = 4_000L

        /**
         * 事件通道静默时的轮询间隔。
         *
         * 开屏广告通常只显示 3~5 秒，700ms 一次足够在它消失前抓住按钮，
         * 而单次只读一个窗口的包名（约 1~3ms），CPU 占用可以忽略。
         */
        private const val POLL_FAST_MS = 700L
    }

    private val main = Handler(Looper.getMainLooper())
    private var scanThread: HandlerThread? = null
    private var scanHandler: Handler? = null

    @Volatile
    private var rules: RuleSet? = null
    private val reloadVersion = AtomicInteger(0)

    /**
     * 特殊 ROM 上的手动兜底：worker 线程彻底拿不到窗口时才临时置 true。
     *
     * 绝对不要在「扫描抛异常」时自动把它置 true。主线程读窗口是同步 IPC，
     * 实测会死锁，等于把一次偶发抖动变成整个服务卡死——旧代码就是这么写的，
     * 现在改成异常就地降级、扫描链继续，不再切换线程。
     */
    @Volatile
    private var forceMainThread = false

    private var lastPkg: String? = null
    private var lastWatchEnd = 0L
    private var homePkg: String? = null

    /** 最近一次「回到桌面 / 最近任务」的时间点，用来识别「离开 App 又回来」 */
    private var leftAppAt = 0L

    /** 最近一次真实 App 来到前台的时间点 */
    private var lastAppFgAt = 0L

    /** 已安装输入法包名，onServiceConnected 时查一次 */
    @Volatile
    private var imePackages: Set<String> = emptySet()

    /** 上一次真正起扫描的包与时间，用于同包冷却 */
    private var lastWatchPkg: String? = null
    private var lastWatchAt = 0L

    @Volatile
    private var watching = false

    @Volatile
    private var watchPkg: String? = null
    private var watchToken = 0
    private var watchStart = 0L
    private var watchDeadline = 0L

    /**
     * 扫描互斥闸。
     *
     * 必须是原子 CAS：主线程收到窗口事件会调 requestScan，扫描线程跑完
     * 一轮后也会自排下一轮。普通 volatile 布尔的「先判后写」不是原子操作，
     * 两边同时看到 false 就会各 post 一个扫描任务——同一棵节点树被两个
     * 线程同时遍历、回收，甚至重复点击。
     */
    private val scanBusy = AtomicBoolean(false)

    /** 弹窗扫描同理：主线程触发、扫描线程清标志，用 CAS 避免重复投递 */
    private val popupBusy = AtomicBoolean(false)
    private var lastPopupAt = 0L

    private var coordTapped = false
    private var lastLearnAt = 0L
    private var watchScans = 0
    private var watchCost = 0L
    private var watchHit = false

    /** 这一轮监测是不是「从桌面/后台冷启动」进来的。自识别只在这种场景下启用 */
    private var watchCold = false

    /** 本轮已经用自识别点过几次（上限 1 次，避免在内容页连点） */
    private var heurClicks = 0

    /** 本轮是否已经记录过「取不到界面」，避免整个窗口期刷屏 */
    private var rootFailLogged = false

    /**
     * 上一次做「整树遍历」扫描的时间。
     *
     * 整树遍历是唯一能靠本地正则匹配到纯文本按钮的路径（系统文本检索在
     * WebView 上会阻塞 3.2 秒，已经从快路径里彻底移除）。以前全扫的触发
     * 条件是「第 0/2/5/9 轮」这种固定轮次，而 oopz 的开屏广告要冷启动
     * 约 5 秒才加载出来——那时早就过了第 9 轮，于是只剩快路径可用，
     * 纯文本的「跳过」按钮只能靠运气撞上。
     *
     * 改成按时间节流：只要距上次全扫超过 FULL_SCAN_GAP_MS 就允许再扫一次，
     * 广告什么时候冒出来都能在半秒内被扫到。
     */
    private var lastFullScanAt = 0L

    /**
     * 自识别命中位置的「上一帧」。
     *
     * 内容页在冷启动的头几百毫秒往往是半渲染状态：RecyclerView 还没布局，
     * 整屏看起来就像「一张大图 + 几个入口」，和开屏广告几乎一模一样。
     * 于是第一轮扫描很容易在顶栏图标上猜出一个候选。
     *
     * 对策：同位置的候选必须连续两轮扫描都出现才允许点。真开屏广告的按钮
     * 是稳定存在的，两轮之后还在；半渲染的内容页到第二轮已经布局完，
     * 候选位置就变了（或者整页被内容页闸门挡掉）。
     */
    private var heurRect: android.graphics.Rect? = null
    private var heurSeen = 0

    /** 全局点击闸：两次点击至少间隔 CLICK_GAP_MS */
    private var lastClickAt = 0L

    /** 防重复点击：key -> 上次点击时间 */
    private val clickedAt = HashMap<String, Long>()

    /**
     * 最近一次收到无障碍事件的时间（elapsedRealtime）。
     *
     * 有些 ROM（实测 HyperOS / Android 16）会在服务「已绑定、已启用」的状态下
     * 悄悄停止派发窗口事件：dumpsys accessibility 里 Bound services 仍在，
     * 进程也活着，但切应用、切桌面一条事件都收不到，表现就是
     * 「无障碍显示已开启，但什么都不跳了」——而且重启 App 也不会好，
     * 只有重新绑定（关掉开关再打开）或重新安装才恢复一阵。
     *
     * 这个时间戳用来判断事件通道是否还活着，死了就切到主动轮询兜底。
     */
    @Volatile
    private var lastEventAt = 0L

    /** 上一次轮询看到的前台包名，避免同一个包反复触发 */
    private var lastPollPkg: String? = null

    /** 事件通道静默了多久（毫秒），用于日志 / 自检展示 */
    @Volatile
    var eventSilentMs = 0L
        private set

    /**
     * 主动轮询：事件通道不可用时靠它发现「换应用了」。
     *
     * 两个线程上的讲究（这里踩过坑）：
     *
     * 1. 读窗口必须走 scanHandler，不能在主线程。
     *    rootInActiveWindow / getWindows() 是同步 IPC，服务端在处理窗口时
     *    可能反过来等我们主线程响应，在主线程读就是死锁——实测表现为
     *    「日志停在某一刻，之后整个服务再也没有任何输出，进程还活着、也没被冻结」。
     *    所有节点树操作本来就都在 scanHandler 上，轮询必须遵守同一条规则。
     *
     * 2. 下一次轮询的定时要在主线程上先排好，不能等这次读完再排。
     *    否则某一次读窗口卡住，整条轮询链就断了，事件通道又刚好静默，
     *    那就彻底不会再有任何动作。
     */
    private val poller = object : Runnable {
        override fun run() {
            main.postDelayed(this, nextPollDelay())
            if (!Prefs.masterEnabled || !screenOn()) return
            // 悬浮窗可能是用户后来才授权的，这里兜底补挂（节流 8 秒）
            maybeShowGhost()
            val h = if (forceMainThread) main else scanHandler ?: return
            h.post {
                val pkg = runCatching { activePackage() }.getOrNull() ?: return@post
                main.post { runCatching { handlePolled(pkg) } }
            }
        }
    }

    /**
     * 轮询间隔见下面的 nextPollDelay()。
     */

    // 自检用统计
    @Volatile
    var lastScanMs = 0L
        private set

    @Volatile
    var lastScanNodes = 0
        private set

    @Volatile
    var lastScanPkg = ""
        private set

    // ------------------------------------------------------------------ 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 不再在运行时改写 serviceInfo：
        // accessibility_service_config.xml 里已经把 flagRetrieveInteractiveWindows /
        // flagIncludeNotImportantViews 配好了，重复 setServiceInfo 会触发系统
        // 重新下发配置，在部分 ROM 上反而更容易踩到「事件静默」。
        startScanThread()
        loadImePackages()
        reloadRules()
        showGhostWindow()
        lastEventAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(poller)
        main.postDelayed(poller, POLL_FAST_MS)
        SkipLog.add("无障碍服务已连接")
        Log.i(TAG, "accessibility service connected")
    }

    // ------------------------------------------------------------------ 微窗

    private var ghost: View? = null

    /**
     * 1×1 的透明悬浮窗。
     *
     * 目的不是显示，而是让本进程「有可见窗口」。
     *
     * 这台机器（HyperOS / Android 16）实测：没有 overlay 时，无障碍服务虽然
     * bound 且 enabled，但 rootInActiveWindow 会一直返回 **launcher** 的根节点
     * （前台明明是目标 App），扫描出来的树是旧页面的残留，于是怎么都跳不过；
     * 而一旦打开「自检 / 探测器」（它本来就挂了个 overlay 悬浮球），立刻恢复正常。
     * 根因是系统把本进程当后台缓存应用限制，窗口数据不往下发。
     *
     * 挂一个 1 像素的透明窗，进程就一直是「有可见界面」的状态，
     * 既不挡操作也不影响观感，但把上面这条链路修通了。
     */
    private fun showGhostWindow() {
        if (ghost != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(v, lp)
            ghost = v
            Log.i(TAG, "ghost window added")
        }.onFailure { Log.w(TAG, "ghost window failed: ${it.message}") }
    }

    private var lastGhostTry = 0L

    /**
     * 悬浮窗权限往往是「后来才给的」。
     *
     * 真实场景：用户第一次装好，无障碍开了，但悬浮窗没开。`onServiceConnected`
     * 时 `canDrawOverlays` 是 false，微窗就没加上；等用户照着首页黄字去设置里
     * 把悬浮窗打开再回到 App，服务早已 connected，`ghost` 永远是 null，
     * 再也不会重试——于是**权限明明配齐了，还是一个广告都不跳**，
     * 用户只会觉得这版比上版更差。
     *
     * 所以这里做两件事：MainActivity 回到前台时主动叫一次（见 retryGhostWindow），
     * 另外每轮轮询兜底重试一次（节流 8 秒，一次 Settings 读很便宜）。
     */
    private fun maybeShowGhost() {
        if (ghost != null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastGhostTry < 8_000L) return
        lastGhostTry = now
        showGhostWindow()
    }

    /** 主界面回到前台时调用：用户可能刚去授予了悬浮窗权限 */
    fun retryGhostWindow() {
        lastGhostTry = 0L
        main.post { maybeShowGhost() }
    }

    /**
     * 把用户从广告落地页弹回原 App。三级兜底，逐级加重：
     *
     *   第 1 级（400ms）：按一次返回。大多数 App 有效。
     *   第 2 级（1.6s）：还在落地页上，就直接把原 App 拉回前台。
     *       电商/商店的「欢迎页」普遍会吃掉返回键——实测淘宝 WelcomeActivity
     *       连按两次 BACK 都返回 true 但页面纹丝不动。这时只能重新拉起原 App。
     *   第 3 级（2.8s）：拉不起来（后台启动 Activity 被 ROM 限制）就按 HOME，
     *       至少把用户从广告页带走，他再点一下原 App 就能回来。
     *
     * 每一级都先确认「前台还是那个落地页」才动手，不会误伤用户自己的页面。
     */
    fun backOffLanding(landingPkg: String, originPkg: String?) {
        main.postDelayed({
            val ok = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
            Log.i(TAG, "guard back#1 pkg=$landingPkg ok=$ok")
        }, 400L)

        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            // 读窗口必须在 scanHandler 上做，主线程读会死锁（见 poller 注释）
            h.post {
                val cur = runCatching { activePackage() }.getOrNull()
                if (cur != landingPkg) return@post
                main.post { guardRelaunch(landingPkg, originPkg) }
            }
        }, 1_600L)
    }

    /** 第 2 级：把原 App 拉回前台；1.2 秒后仍是落地页就走第 3 级 */
    private fun guardRelaunch(landingPkg: String, originPkg: String?) {
        val ok = originPkg != null && relaunch(originPkg)
        Log.i(TAG, "guard relaunch origin=$originPkg ok=$ok")
        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            h.post {
                val still = runCatching { activePackage() }.getOrNull()
                if (still != landingPkg) return@post
                main.post {
                    val home = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                        .getOrDefault(false)
                    Log.i(TAG, "guard home fallback pkg=$landingPkg ok=$home")
                    SkipLog.add("已从 ${landingPkg.substringAfterLast('.')} 退出（该页会吞返回键）")
                }
            }
        }, 1_200L)
    }

    /**
     * 用桌面启动意图把某个 App 带回前台。
     *
     * Android 10+ 对「后台启动 Activity」有全局限制，无障碍服务属于有豁免的
     * 少数场景之一；即使被限制，也只会静默失败，下一级会兜住。
     */
    private fun relaunch(pkg: String): Boolean {
        val i = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(i); true }.getOrDefault(false)
    }

    private fun hideGhostWindow() {
        ghost?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        ghost = null
    }

    /** 查出所有已安装输入法，键盘弹出的事件不该被当成「换了应用」。 */
    private fun loadImePackages() {
        val set = HashSet<String>()
        try {
            val intent = Intent(android.view.inputmethod.InputMethod.SERVICE_INTERFACE)
            packageManager.queryIntentServices(intent, 0).forEach { ri ->
                ri.serviceInfo?.packageName?.let { set.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query ime failed: ${t.message}")
        }
        imePackages = if (set.isEmpty()) IME_FALLBACK else set + IME_FALLBACK
        Log.i(TAG, "ime packages=${imePackages.size}")
    }

    /** 桌面、系统页、输入法、浏览器：只当「路过」，不参与换 App 判定。 */
    private fun isPassThrough(pkg: String): Boolean {
        return isHome(pkg) || pkg in NEVER_SCAN || pkg in imePackages ||
            pkg in IME_FALLBACK || AppLists.isBrowser(pkg)
    }

    private fun startScanThread() {
        if (scanHandler != null) return
        val t = HandlerThread("adskip-scan").apply { start() }
        scanThread = t
        scanHandler = Handler(t.looper)
    }

    fun reloadRules() {
        val version = reloadVersion.incrementAndGet()
        val task = Runnable {
            val rs = runCatching { RuleRepository.load(this) }
                .onFailure {
                    Log.w(TAG, "load rules failed", it)
                    SkipLog.add("规则加载失败：${it.message}")
                }
                .getOrNull() ?: return@Runnable
            // 连续同步 / 学习时只让最后一次加载生效，避免旧结果覆盖新规则
            if (version != reloadVersion.get()) return@Runnable
            rules = rs
            val remote = if (Prefs.remoteEnabled) RemoteRules.cachedApps(this) else 0
            val learned = LearnStore.learnedAppCount()
            SkipLog.add("规则已加载：${rs.apps.size} 个应用（远程库 $remote 条 / 学习 $learned）")
            Log.i(TAG, "rules loaded: ${rs.apps.size} apps, remote=$remote learned=$learned")
        }
        scanHandler?.post(task) ?: Thread(task, "adskip-rules").start()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        reloadVersion.incrementAndGet()
        watchToken++
        watching = false
        hideGhostWindow()
        main.removeCallbacks(poller)
        main.removeCallbacksAndMessages(null)
        runCatching { scanThread?.quitSafely() }
        scanThread = null
        scanHandler = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 事件入口

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        lastEventAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "evt type=${e.eventType} pkg=$pkg cls=${e.className}")
        if (pkg.isEmpty() || pkg == packageName) return
        if (!Prefs.masterEnabled) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == "com.android.systemui") return
                // 键盘弹出不是「跳转来源」，让它参与防跳转的时间窗会污染判定
                if (pkg !in imePackages && pkg !in IME_FALLBACK) {
                    JumpGuard.onForeground(pkg, this)
                }
                onForegroundChanged(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (watching && pkg == watchPkg) {
                    // 界面刚发生变化，立刻扫一次，不等下一个轮询周期
                    requestScan(0L)
                }
                // 弹窗扫描必须独立于开屏监测，不能挂在 else 分支上。
                //
                // 旧代码写成 `else if (Prefs.closePopups ...)`，于是开屏监测
                // 进行期间（watching && pkg == watchPkg）只会走 requestScan，
                // 弹窗规则一次都不会执行。而开屏监测窗口长达 8~10 秒，
                // 很多 App 恰恰是「开屏广告 → 紧接着弹一个运营弹层」，
                // 于是弹层就这么顶在那里不被关掉。
                // oopz「多次跳过失败居多」也有这一层原因。
                if (Prefs.closePopups && !AppLists.isBrowser(pkg)) {
                    maybePopup(pkg)
                }
            }
        }
    }

    private fun onForegroundChanged(pkg: String) {
        if (!Prefs.skipSplash && !Prefs.autoLearn) {
            stopWatch()
            lastPkg = pkg
            return
        }
        Log.d(TAG, "fg changed pkg=$pkg lastPkg=$lastPkg passThrough=${isPassThrough(pkg)}")

        // 关键：桌面 / 系统页 / 输入法只是「路过」，不能因此把刚启动的监测停掉。
        // 小米这类 ROM 起 App 时经常先来一条 launcher 的窗口事件，
        // 旧逻辑在这里 stopWatch，结果整轮扫描一次都没跑——表现就是「有时完全没反应」。
        // 所以这里只记录「离开过 App」的时间点，不直接停监测：
        // 真正的判断交给下面「离开又回来 → 重开一轮」，这样两种事件顺序都不会出错。
        if (isPassThrough(pkg)) {
            if (pkg !in imePackages && pkg !in IME_FALLBACK) {
                leftAppAt = SystemClock.uptimeMillis()
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        val prev = lastPkg
        val changed = pkg != lastPkg
        // 包名没变也必须重开一轮：用户从桌面 / 最近任务重新打开同一个 App，
        // 那是全新一次冷启动，同样有开屏广告。
        // 少了这个判断，同一个 App 第二次打开起就永远不再监测，
        // 表现就是「刚装好能用，用一会儿就跳不过了」。
        val cameBack = leftAppAt > lastAppFgAt

        // 「冷启动」= 上一个前台是桌面 / 系统页 / 输入法（或者干脆没有），
        // 或者刚从桌面回来。只有这种情况下才允许自识别——
        // App 内部切页面时用的同样是全屏大图（详情页、评论区），
        // 一旦在这里猜，就会点到用户自己的按钮。
        val cold = cameBack || prev == null || isPassThrough(prev)

        lastPkg = pkg
        lastAppFgAt = now
        // 让轮询知道「这个包刚刚已经处理过」，避免同一包被事件和轮询各触发一次
        lastPollPkg = pkg

        // 换 App 一定重新开始；
        // 同一 App 内切 Activity 不重启（否则微信里点十次聊天就是十轮扫描，
        // 很容易被系统判定成后台活跃），由 SAME_PKG_COOLDOWN_MS 兜住。
        if (changed || cameBack) {
            if (watching && watchPkg != pkg) stopWatch()
            startWatch(pkg, cold)
        }
    }

    // ------------------------------------------------------------------ 主动轮询兜底

    /**
     * 事件通道是否还「活着」：最近 5 秒内收到过事件就算正常。
     */
    private fun eventChannelAlive(): Boolean =
        SystemClock.elapsedRealtime() - lastEventAt < EVENT_ALIVE_MS

    /**
     * 轮询间隔。
     *
     * 前台 App 配了弹窗规则时一律用快节奏（POLL_FAST_MS），不管事件通道
     * 是否活跃。理由：弹窗往往一闪而过，而 TYPE_WINDOW_CONTENT_CHANGED
     * 只在「界面内容真的变了」时才发——纯静态页面弹出来的时候可能一个
     * 内容事件都没有，光靠事件根本发现不了它。
     *
     * 查表判断本身零成本（只是 map 查找），真正贵的 doPopupScan 只有
     * 配了规则、且匹配上 when 条件时才会跑。
     */
    private fun nextPollDelay(): Long {
        val pkg = lastPollPkg
        if (pkg != null && rules?.apps?.get(pkg)?.popups?.isNotEmpty() == true) {
            return POLL_FAST_MS
        }
        return if (eventChannelAlive()) POLL_SLOW_MS else POLL_FAST_MS
    }

    /**
     * 轮询结果落到主线程后的处理。
     *
     * 这里只做判断和派发，绝不读窗口——所有 IPC 都留在 scanHandler 上做，
     * 原因见上面 poller 的注释。
     */
    private fun handlePolled(pkg: String) {
        eventSilentMs = SystemClock.elapsedRealtime() - lastEventAt
        if (!Prefs.masterEnabled) return
        if (pkg == packageName) return
        if (pkg == lastPollPkg) {
            // 弹窗关闭不能只靠事件。
            //
            // 旧逻辑挂在 TYPE_WINDOW_CONTENT_CHANGED 上（界面内容变了才发），
            // 但很多 App 的弹窗是叠在一张静态页面上弹出来的——内容没变，
            // 就没有内容事件，弹窗扫描一次都不会跑，弹窗就一直顶在那里。
            // 现在改成由轮询兜底：配了弹窗规则的 App 会保持快节奏轮询，
            // 静态页面也能被发现。
            if (Prefs.closePopups && !AppLists.isBrowser(pkg)) {
                maybePopup(pkg)
            }
            return
        }
        lastPollPkg = pkg

        Log.d(TAG, "poll fg pkg=$pkg silent=${eventSilentMs}ms")

        if (pkg != "com.android.systemui") JumpGuard.onForeground(pkg, this)
        onForegroundChanged(pkg)

        // 刚切到这个 App，立刻查一次弹窗，别等下一轮轮询。
        // 很多 App 是「打开就弹运营弹层」，晚一拍就错过。
        if (Prefs.closePopups && !AppLists.isBrowser(pkg)) {
            maybePopup(pkg)
        }
    }

    /**
     * 取当前前台应用的包名，完全不依赖事件派发。
     *
     * 只问 rootInActiveWindow（一次 IPC 最快）。
     * 这里刻意**不**用 getWindows() 兜底：在 Android 16 + HyperOS 上，
     * 窗口列表是「上次收到窗口事件时的快照」，事件一断就永远停在旧页面，
     * 拿它当兜底反而会把一个不存在的包名喂给 onForegroundChanged。
     * 拿不到就返回 null，本轮轮询什么都不做，等下一次再问。
     */
    private fun activePackage(): String? {
        val active = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val p = active.packageName?.toString()
        Access.recycle(active)
        return p
    }

    private fun screenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    // ------------------------------------------------------------------ 开屏监测

    private fun startWatch(pkg: String, cold: Boolean = true) {
        // 「跳过开屏广告」关掉就必须真的不跳。
        // 旧逻辑只挡住「关键词全空且没开自动学习」的情况，于是用户把开关关掉、
        // 但只要「自动学习」还开着，自识别照样会去点——开关看起来是失灵的。
        if (!Prefs.skipSplash) return
        if (AppLists.isBrowser(pkg)) return
        val rs = rules
        val app: AppRule? = rs?.apps?.get(pkg)
            ?: if (Prefs.skipAllApps) rs?.generic else null

        // doScan 对「规则库里没有、又关了『对全部应用生效』」的 App 会立刻
        // stopWatch。这里必须先挡住：否则只要「自动学习」开着，就会白起一轮
        // 监测，日志里 watch start / watch end 连着出现，看起来像识别失败。
        // rs == null 是规则还在加载，不能挡，doScan 会自己等下一轮。
        if (rs != null && rs.apps[pkg] == null && !Prefs.skipAllApps) return

        val splash = app?.splash ?: SplashRule.EMPTY
        if (!splash.isActionable && !Prefs.autoLearn) {
            Log.d(TAG, "watch skipped pkg=$pkg actionable=false kw=${splash.keywords.size}")
            return
        }

        // 同包冷却：真正的开屏只出现在冷启动，5 秒内重复命中同一个包
        // 基本都是同一次打开（或者 App 内部切页触发的窗口事件），没必要再起一轮。
        val now = SystemClock.uptimeMillis()
        if (pkg == lastWatchPkg && now - lastWatchAt < SAME_PKG_COOLDOWN_MS) return
        lastWatchPkg = pkg
        lastWatchAt = now

        watchToken++
        watchPkg = pkg
        watchStart = SystemClock.uptimeMillis()
        watchDeadline = watchStart + splash.durationMs
        watching = true
        watchCold = cold
        coordTapped = false
        watchScans = 0
        watchCost = 0L
        watchHit = false
        heurClicks = 0
        rootFailLogged = false
        lastFullScanAt = 0L
        heurRect = null
        heurSeen = 0
        // 已经把「离开过 App」这件事消费掉了，不清零的话，
        // 后续每一条桌面事件都会让 cameBack 永远为真，同一次打开被反复重开监测
        leftAppAt = 0L

        Log.d(TAG, "watch start pkg=$pkg cold=$cold delay=${splash.delayMs} kw=${splash.keywords.size}")
        requestScan(splash.delayMs)
    }

    private fun stopWatch() {
        if (watching) {
            lastWatchEnd = SystemClock.uptimeMillis()
            Log.d(
                TAG,
                "watch end pkg=$watchPkg scans=$watchScans cost=${watchCost}ms hit=$watchHit"
            )
            // 扫了一整轮什么都没点，把原因留在应用内记录里，
            // 用户不用连电脑也能看到「是没找到按钮，还是根本没拿到界面」
            if (!watchHit && watchScans > 0) {
                val n = rules?.apps?.get(watchPkg ?: "")?.name?.ifBlank { null } ?: watchPkg
                SkipLog.add("未找到跳过按钮：$n（扫了 $watchScans 次）")
            }
        }
        watching = false
        watchPkg = null
    }

    private fun currentSplash(): SplashRule? {
        val pkg = watchPkg ?: return null
        val rs = rules ?: return null
        return (rs.apps[pkg] ?: rs.generic).splash
    }

    /**
     * 投递一次扫描。scanBusy 保证同一时刻只有一次扫描在跑，
     * 避免界面频繁变化（比如倒计时每秒刷新）时把队列堆爆。
     */
    private fun requestScan(delay: Long) {
        if (!watching) return
        // 主线程（窗口事件）和扫描线程（上一轮结束后的自排）会同时进来，
        // compareAndSet 保证同一时刻只有一个扫描任务在队列里。
        if (!scanBusy.compareAndSet(false, true)) return
        val handler = if (forceMainThread) main else scanHandler
        if (handler == null) {
            scanBusy.set(false)
            return
        }

        val token = watchToken
        // postDelayed 在 handler 所在线程已退出时返回 false（不抛异常）。
        // 这时如果不管它，scanBusy 会永远停在 true，之后每一次 requestScan
        // 都直接 return ——「监测起来了但一次都没扫」就是这么来的。
        val posted = handler.postDelayed({
            var cont = false
            try {
                if (token == watchToken && watching) cont = doScan()
            } catch (t: Throwable) {
                // 一次偶发异常绝不能把服务永久切回主线程：主线程读窗口
                // 是同步 IPC，实测会把整个服务卡死（见 poller 的注释）。
                // 这里只记日志并让扫描链继续，下一轮自己恢复；
                // 真出现持续异常时 watchDeadline 也会把这一轮收掉。
                Log.w(TAG, "scan failed (main=$forceMainThread)", t)
                cont = true
            } finally {
                scanBusy.set(false)
            }
            if (cont && token == watchToken && watching) {
                // 前 1.6 秒密一点，后面放缓，省电也不影响命中率
                val iv = currentSplash()?.intervalMs ?: 130L
                val elapsed = SystemClock.uptimeMillis() - watchStart
                // 上一轮如果卡了很久（>800ms，多半是撞上 WebView 渲染时
                // 系统侧无障碍 IPC 被挂起），那渲染此刻应该刚结束——
                // 立刻再扫一轮，别再等一个轮询间隔，把命中时机抢回来。
                val nextDelay = if (lastScanMs >= 800L) 0L
                else if (elapsed > 1600L) iv * 2 else iv
                requestScan(nextDelay)
            }
        }, delay.coerceAtLeast(0L))
        if (!posted) scanBusy.set(false)
    }

    /** @return true 表示继续轮询 */
    private fun doScan(): Boolean {
        if (!Prefs.masterEnabled) {
            stopWatch()
            return false
        }
        val pkg = watchPkg ?: return false
        val now = SystemClock.uptimeMillis()
        if (now > watchDeadline) {
            stopWatch()
            return false
        }

        val rs = rules ?: return true
        val configured = rs.apps[pkg]
        val app = configured ?: if (Prefs.skipAllApps) rs.generic else run {
            stopWatch()
            return false
        }
        val splash = app.splash

        val root = pickRoot(pkg) ?: run {
            // 一整轮里只记一次，避免 30 多条重复
            if (!rootFailLogged) {
                rootFailLogged = true
                SkipLog.add("取不到 ${app.name.ifBlank { pkg }} 的界面（系统无障碍没给窗口）")
            }
            return true
        }
        if (runCatching { root.packageName?.toString() }.getOrNull() != pkg) {
            Access.recycle(root)
            return true
        }
        // 自识别（启发式）的启用条件。
        //
        // 走过三次弯路：
        // 1) `configured == null`——只有规则库里没有的 App 才自识别，
        //    抖音、京东在库里有条目但按钮 id 随版本变，规则一落空就彻底没救。
        // 2) `Prefs.autoLearn && ...`——总开关挂在「自动学习」上，而它默认是关的。
        // 3) 只看时间窗（前 3.6 秒）——于是 App 内部切页、进评论区、翻到详情页
        //    也会被当成开屏页去猜，结果点了番茄的「分类」「点赞」、
        //    DeepSeek 的「新对话」、抖音评论区的关闭 ×。
        //
        // 现在三个条件同时成立才允许：① 这一轮是冷启动（从桌面/后台进来）；
        // ② 还在开屏窗口的头 2.6 秒；③ 本轮自识别还没点过。
        // 再加上 SplashScanner 里的 strictAdPage 闸门（整页一张大图、几乎没有
        // 可点元素、没有底部导航栏），才可能真的点下去。
        val allowHeuristic = watchCold && now - watchStart < HEURISTIC_WINDOW_MS
        // 全扫（整树遍历）的触发条件：
        // ① 自识别窗口内（自识别必须靠遍历才能找到没有文字的图标按钮）；
        // ② 本轮前几轮（冷启动瞬间广告可能已经在了，先密集扫几次）；
        // ③ 距上次全扫超过 FULL_SCAN_GAP_MS——这是关键的一条。
        //
        // 旧逻辑是 `watchScans in setOf(0, 2, 5, 9)`，按固定轮次触发。
        // 但不同 App 的广告出现时间差别极大：抖音开屏几乎立刻就有，
        // oopz 的开屏要冷启动约 5 秒才加载出来（实测：t=3.5s 还在 logo 页，
        // t=6s 广告才出现）。等 oopz 广告冒头时早就过了第 9 轮，
        // 全扫再也不会发生，只剩快路径——而快路径已经不做文本检索了，
        // 纯文本的「跳过 5」就再也匹配不到。表现就是「oopz 多次跳过，失败居多」。
        //
        // 整树遍历在开屏页只要几毫秒（oopz 广告页 61 个节点 < 10ms），
        // 真正的大页面才会慢到 150~300ms，而且那种页面本来就是内容页，
        // 不会点。所以按 500ms 节流全扫，代价可以忽略，收益是广告
        // 无论什么时候出现都能在半秒内被抓到。
        val sinceFull = now - lastFullScanAt
        val fullScan = allowHeuristic || watchScans < 3 || sinceFull >= FULL_SCAN_GAP_MS
        if (fullScan) lastFullScanAt = now

        val dm = resources.displayMetrics
        // SplashScanner.scan 内部已经兜住节点异常，这里是第二道保险：
        // 万一真的抛了，必须回收 root，也不能让异常冒泡到 requestScan
        // （那会断掉整条扫描链）。本轮按「没命中」处理，下一轮照常继续。
        val result = runCatching {
            SplashScanner.scan(
                root, splash, dm.widthPixels, dm.heightPixels, allowHeuristic, fullScan
            )
        }.getOrElse {
            Log.w(TAG, "scan failed", it)
            Access.recycle(root)
            return true
        }
        try {
        lastScanMs = result.costMs
        lastScanNodes = result.nodes
        lastScanPkg = pkg
        // 每次 watch 的第一轮、命中、慢扫描都留一条日志，方便连 adb 时看清到底发生了什么
        val first = watchScans == 0
        watchScans++
        watchCost += result.costMs
        if (first || result.best != null || result.costMs >= 120L) {
            Log.d(
                TAG,
                "scan ${result.costMs}ms nodes=${result.nodes} fast=${!result.full} pkg=$pkg " +
                    "best=${result.best?.label}:${result.best?.score}:${result.best?.byRule}"
            )
        }

        val hit = result.best
        // 扫到的是「正常内容页」（有列表 / 频道栏 / 文字密集 / 可点元素多），
        // 说明这个 App 的开屏阶段已经过去。之后它再跳电商/商店，是用户自己点的，
        // 不该按返回。判定逻辑见 JumpGuard.noteContentPage。
        if (hit == null && result.contentPage) JumpGuard.noteContentPage(pkg)
        if (hit != null) {
            val key = "splash:$pkg"
            // 同一轮里点掉之后要给足冷却：很多内容页上也有「跳过」类文字，
            // 1.2 秒的冷却会让它在内容页反复点，风险很高。
            if (isRecent(key, 4_000L)) return true
            // 全局点击闸：一轮扫描里最多点一次，两次点击间隔不小于 CLICK_GAP_MS
            if (now - lastClickAt < CLICK_GAP_MS) return true
            // 自识别是猜的，一轮监测只允许猜中一次
            if (!hit.byRule && heurClicks >= 1) return true

            // 自识别的候选要「连续两轮都在同一位置」才动手，
            // 挡掉冷启动瞬间半渲染页面上的顶栏图标（详见 heurRect 的注释）
            if (!hit.byRule) {
                val prev = heurRect
                val same = prev != null &&
                    kotlin.math.abs(prev.centerX() - hit.rect.centerX()) < dp(48) &&
                    kotlin.math.abs(prev.centerY() - hit.rect.centerY()) < dp(48)
                heurSeen = if (same) heurSeen + 1 else 1
                heurRect = android.graphics.Rect(hit.rect)
                if (heurSeen < 2) {
                    Log.d(TAG, "heur candidate pending pkg=$pkg label=${hit.label} seen=$heurSeen")
                    return true
                }
            }

            val ok = if (splash.action.equals("back", true)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                Access.click(this, hit.node, dm.widthPixels.toLong() * dm.heightPixels)
            }
            if (ok) {
                lastClickAt = now
                if (!hit.byRule) heurClicks++
                markClicked(key)
                watchHit = true
                Prefs.bumpSkip()
                val how = if (hit.byRule) "规则 ${hit.reason}" else "自识别 ${hit.reason}"
                SkipLog.add("跳过 ${app.name.ifBlank { pkg }} · ${hit.label}（$how）")
                Log.i(TAG, "skipped pkg=$pkg label=${hit.label} how=$how score=${hit.score}")
                // 规则没命中、靠自识别点掉的 → 学下来，下次直接命中
                // （只学控件 id / 干净短文本，绝不学坐标，见 LearnStore）
                if (!hit.byRule && Prefs.autoLearn && now - lastLearnAt > 5000L) {
                    lastLearnAt = now
                    runCatching { LearnStore.learn(pkg, app.name, hit) }
                        .onSuccess { if (it != null) reloadRules() }
                }
                // 有些 App 是两层广告，点完再看一会儿
                watchDeadline = maxOf(watchDeadline, now + 700L)
            }
            return true
        }

        // 规则里只有坐标时，等界面稳定一点再按坐标点一次
        if (splash.coords.isNotEmpty() && !coordTapped && now - watchStart > 650L &&
            now - lastClickAt >= CLICK_GAP_MS
        ) {
            coordTapped = true
            val r = splash.coords.first()
            if (Access.tapAt(this, r.exactCenterX(), r.exactCenterY())) {
                lastClickAt = now
                Prefs.bumpSkip()
                SkipLog.add("跳过 ${app.name.ifBlank { pkg }} · 坐标")
                watchDeadline = maxOf(watchDeadline, now + 500L)
            }
        }
        return true
        } finally {
            SplashScanner.recycle(result)
        }
    }

    // ------------------------------------------------------------------ 弹窗

    private fun maybePopup(pkg: String) {
        val app = rules?.apps?.get(pkg) ?: return
        if (app.popups.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPopupAt < 400L) return
        if (!popupBusy.compareAndSet(false, true)) return
        lastPopupAt = now

        val handler = if (forceMainThread) main else scanHandler
        if (handler == null) {
            popupBusy.set(false)
            return
        }
        val posted = handler.post {
            try {
                doPopupScan(pkg, app)
            } catch (t: Throwable) {
                Log.w(TAG, "popup scan failed", t)
            } finally {
                popupBusy.set(false)
            }
        }
        // post 失败（handler 所在线程已退出）时没人会清标志，必须自己复位，
        // 否则这个 App 的弹窗规则从此再也不会执行。
        if (!posted) popupBusy.set(false)
    }

    private fun doPopupScan(pkg: String, app: AppRule) {
        val root = pickRoot(pkg) ?: return
        if (runCatching { root.packageName?.toString() }.getOrNull() != pkg) {
            Access.recycle(root)
            return
        }

        val now = SystemClock.uptimeMillis()

        // 一次遍历拿全树，所有关键词匹配都在本地做。
        //
        // 旧代码用系统的 findAccessibilityNodeInfosByText 做文本检索，
        // 在 WebView 上单次能阻塞 3.2 秒，而一条弹窗规则的 when/action
        // 加起来可能几十个关键词——没有预算的话能把扫描线程卡住一分钟，
        // 期间开屏跳过、防跳转、轮询全部停摆。800ms 预算只能「少做几次」，
        // 挡不住「一次就卡 3.2 秒」。
        //
        // 现在改成 walk + 本地正则匹配：一次遍历，几十个关键词都是纯内存
        // 匹配，既不会阻塞，也天然支持 + / - / = / & 这些复杂条件
        // （系统子串检索反而只支持纯文本，复杂条件以前只能退化成整树遍历）。
        val tree = Access.walk(root, Access.MAX_NODES)

        fun matches(keyword: Matcher.Keyword): Boolean =
            tree.any { Matcher.hit(keyword, it) }

        try {
            for (rule in app.popups) {
                if (rule.whenKw.isEmpty() || (rule.actionKw.isEmpty() && !rule.globalBack)) continue
                val key = "popup:${app.pkg}:${rule.whenAll.joinToString("&")}"
                val last = clickedAt[key] ?: 0L
                if (now - last < rule.cooldownMs) continue
                val whenOk = rule.whenKw.all(::matches)
                // 只在命中时记一条：轮询兜底会让这个方法每秒跑好几次，
                // 不命中的情况记下来只会刷屏。
                if (whenOk) {
                    Log.d(TAG, "popup hit pkg=$pkg when=${rule.whenAll.joinToString("&")}")
                }
                if (!whenOk) continue

                if (rule.globalBack) {
                    if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                        markClicked(key)
                        Prefs.bumpSkip()
                        SkipLog.add("关闭弹窗 · ${app.name.ifBlank { app.pkg }}")
                    }
                    continue
                }

                var clicked = false
                val screenArea =
                    resources.displayMetrics.widthPixels.toLong() * resources.displayMetrics.heightPixels
                for (keyword in rule.actionKw) {
                    val candidates = tree.filter { Matcher.hit(keyword, it) }
                    // 优先点可点击的那个；都没有就点第一个（文案匹配到的）
                    val target = candidates.firstOrNull { it.isClickable }
                        ?: candidates.firstOrNull()
                    // recyclePath=false：祖先节点也在 tree 里，交给 finally 统一释放，
                    // 否则同一轮里后面的规则会读到已回收的节点
                    if (target != null) clicked = Access.click(this, target, screenArea, recyclePath = false)
                    if (clicked) break
                }
                if (clicked) {
                    markClicked(key)
                    Prefs.bumpSkip()
                    SkipLog.add("关闭弹窗 · ${app.name.ifBlank { app.pkg }}")
                }
            }
        } finally {
            // tree[0] 就是 root，正常情况下这里已经把它一起释放了
            Access.recycleAll(tree)
            if (tree.none { it === root }) Access.recycle(root)
        }
    }

    // ------------------------------------------------------------------ 取窗口

    private fun pickRoot(pkg: String): AccessibilityNodeInfo? {
        // 只问 rootInActiveWindow，不走 windows 缓存。
        //
        // 实测 Android 16 + HyperOS：注册 flagRetrieveInteractiveWindows 之后，
        // getWindows() 返回的是「上一次收到 TYPE_WINDOWS_CHANGED 时的快照」。
        // 只要事件一断（这台机器上会周期性断），快照就永远停在桌面上，
        // 前台明明是 AdActivity，窗口列表里只有 launcher——扫描出来的是
        // 上一个 ContentActivity 的残留节点，坐标甚至倒挂，跳过按钮永远找不到。
        //
        // rootInActiveWindow 每次都是现问系统要活动窗口，没有这个问题。
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val p = active.packageName?.toString()
            if (p == pkg && sane(active)) return active
            Log.d(TAG, "root mismatch want=$pkg got=$p")
            Access.recycle(active)
        }
        return null
    }

    /** 窗口/节点是否还算正常：bounds 合法、非空 */
    private fun sane(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(r) }.getOrElse { return false }
        return r.width() > 0 && r.height() > 0 && r.left <= r.right && r.top <= r.bottom
    }

    private fun isHome(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        homePkg?.let { return pkg == it }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = runCatching { packageManager.resolveActivity(intent, 0) }.getOrNull()
        homePkg = resolved?.activityInfo?.packageName
        return pkg == homePkg
    }

    // ------------------------------------------------------------------ 工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isRecent(key: String, windowMs: Long): Boolean {
        val last = clickedAt[key] ?: return false
        return SystemClock.uptimeMillis() - last < windowMs
    }

    private fun markClicked(key: String) {
        if (clickedAt.size > 200) {
            val now = SystemClock.uptimeMillis()
            clickedAt.entries.removeAll { now - it.value > 60_000L }
        }
        clickedAt[key] = SystemClock.uptimeMillis()
    }

    // ------------------------------------------------------------------ 自检 / 探测器

    /** 抓当前前台窗口的完整节点树，供悬浮球展示 */
    fun dumpForegroundWindow(): String {
        val root = foregroundRoot() ?: return "没有取到窗口内容"
        return try {
            val dm = resources.displayMetrics
            buildString {
                append("package: ").append(root.packageName).append('\n')
                append("screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
                append('\n')
                append(Access.dump(root))
            }
        } finally {
            Access.recycle(root)
        }
    }

    private fun foregroundRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /** 自检：用当前屏幕跑一遍完整识别流程，返回人可读的报告 */
    fun analyzeForeground(): String {
        val root = foregroundRoot() ?: return "没有取到窗口内容"
        // scan() 一旦被调用就接管 root 的生命周期（由 NodePool 统一释放）；
        // 只有「还没走到 scan 就 return」的路径要自己释放。
        // 旧写法在「规则还没加载完」这条分支上直接 return，每点一次自检
        // 就泄漏一个跨进程节点——一天点几十次，无障碍进程的内存只增不减。
        var owned = true
        var result: SplashScanner.Result? = null
        try {
            val pkg = root.packageName?.toString() ?: "?"
            val dm = resources.displayMetrics

            val rs = rules
            val configured = rs?.apps?.get(pkg)
            val app = configured ?: rs?.generic ?: return "规则还没加载完，稍后再试"
            val splash = app.splash

            val r = SplashScanner.scan(root, splash, dm.widthPixels, dm.heightPixels, true)
            result = r
            owned = false

            lastScanMs = r.costMs
            lastScanNodes = r.nodes
            lastScanPkg = pkg

            return buildString {
                append("包名  ").append(pkg).append('\n')
                append("规则  ").append(
                    when {
                        configured != null -> "${app.name.ifBlank { "-" }}（${app.origin}，${splash.keywords.size} 条关键词）"
                        else -> "未配置，用通用规则"
                    }
                ).append('\n')
                append("扫描  ").append(r.nodes).append(" 个节点 / ").append(r.costMs).append(" ms\n")
                append("大图  占屏 ").append(
                    if (dm.widthPixels.toLong() * dm.heightPixels > 0) {
                        (r.bigArea * 100 / (dm.widthPixels.toLong() * dm.heightPixels))
                    } else 0
                ).append("%\n")

                append("判定  ").append(
                    if (r.contentPage) "内容页（有滚动列表/入口很多，不做自识别）" else "疑似开屏页"
                ).append('\n')

                val best = r.best
                if (best == null) {
                    append("\n没有命中任何候选。\n")
                    append("如果屏幕上确实有跳过按钮，说明它是自绘的，节点里没有文字。\n")
                    append("可以打开「自动学习」后再进一次这个 App。")
                } else {
                    append("\n结论  ").append(if (best.byRule) "规则命中" else "自识别（会被学习）")
                    append(" · ").append(best.label)
                    append(" · 分数 ").append(best.score).append('\n')
                    val rect = best.rect
                    append("位置  [").append(rect.left).append(',').append(rect.top).append(',')
                        .append(rect.right).append(',').append(rect.bottom).append("]\n")
                }

                if (r.candidates.size > 1) {
                    append("\n候选：\n")
                    r.candidates.forEachIndexed { i, c ->
                        append(i + 1).append(". ")
                        append(if (c.byRule) "[规则] " else "[算法] ")
                        append(c.label).append("  ")
                        append(c.reason).append("  分=").append(c.score)
                        val rect = c.rect
                        append("  [").append(rect.left).append(',').append(rect.top).append(',')
                            .append(rect.right).append(',').append(rect.bottom).append("]\n")
                    }
                }
            }
        } finally {
            result?.let { SplashScanner.recycle(it) }
            if (owned) Access.recycle(root)
        }
    }

    /** 自检面板上的「记住这个按钮」，把当前屏幕上最优的候选写进规则 */
    fun learnForeground(): String {
        val root = foregroundRoot() ?: return "没有取到窗口内容"
        var owned = true
        var result: SplashScanner.Result? = null
        try {
            val pkg = root.packageName?.toString() ?: return "取不到包名"
            val dm = resources.displayMetrics
            val rs = rules ?: return "规则还没加载完"
            val app = rs.apps[pkg] ?: rs.generic

            val r = SplashScanner.scan(root, app.splash, dm.widthPixels, dm.heightPixels, true)
            result = r
            owned = false

            val best = r.best ?: r.candidates.firstOrNull() ?: return "屏幕上没找到可学的按钮"
            val kw = LearnStore.learn(pkg, app.name, best) ?: return "这个按钮已经学过了"
            reloadRules()
            return "已记住：$kw"
        } finally {
            result?.let { SplashScanner.recycle(it) }
            if (owned) Access.recycle(root)
        }
    }
}
