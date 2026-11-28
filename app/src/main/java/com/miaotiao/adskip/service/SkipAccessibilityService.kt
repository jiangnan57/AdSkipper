package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.miaotiao.adskip.data.TaughtRulesStore
import com.miaotiao.adskip.engine.Access
import com.miaotiao.adskip.engine.AppRule
import com.miaotiao.adskip.engine.InputTrace
import com.miaotiao.adskip.engine.Matcher
import com.miaotiao.adskip.engine.PopupTeacher
import com.miaotiao.adskip.engine.RuleSet
import com.miaotiao.adskip.engine.ShakeDetector
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
         * 扫描排除名单已经搬到 `AppLists.NEVER_SCAN`（由角色派生）。
         *
         * 以前这里单独维护一份，于是判定链读不到它——「负一屏当来源、
         * 拼多多连拦 5 次」就是第 4 次同类事故。现在只保留一处：
         * `AppLists.SHELL ∪ SYSTEM_UI ∪ INERT`。
         */

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

        /**
         * 自家点击的屏蔽窗：600ms。
         *
         * `Access.click` 走 ACTION_CLICK，系统仍会派发 TYPE_VIEW_CLICKED。
         * 这个窗口内收到的点击一律当成「我们自己点的」，不记进 InputTrace。
         * 不设它，每跳过一次广告就造出一个假用户点击。
         */
        private const val SELF_CLICK_MS = 600L

        /** 冷启动窗口：只有在这段时间内才为「是否全屏」付一次 IPC */
        private const val COLD_CLICK_WINDOW_MS = 3_000L

        /** 熔断观察窗：这段时间内同一位置被点够 BREAKER_MAX_HITS 次就停手 */
        private const val BREAKER_WINDOW_MS = 20_000L

        /** 同一位置在观察窗内被点的次数上限 */
        private const val BREAKER_MAX_HITS = 3

        /** 熔断后停手时长 */
        private const val BREAKER_HOLD_MS = 60_000L

        /** 自识别最多只在开屏窗口的头 2.6 秒里用（规则命中不受限） */
        private const val HEURISTIC_WINDOW_MS = 2_600L

        /**
         * 自绘跳过按钮的点击窗口。
         *
         * 比自识别的 2.6 秒宽：开屏广告的物料是网络加载的，实测有 4 秒
         * 才渲染出来的（这时窗口已过，自识别整段失效）。放宽到 6 秒是
         * 安全的，因为这条路径还压着两个硬条件——页面必须有开屏广告的
         * 强标记、且不是内容页。
         */
        private const val DRAW_SKIP_WINDOW_MS = 6_000L

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

        /** 连续多久拿不到活动窗口就认定服务已哑（见 noteRootFail） */
        private const val ROOT_FAIL_MS = 60_000L

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

    /**
     * 本轮监测的目标包与时间窗。
     *
     * 这三个字段主线程写（窗口事件 → startWatch）、扫描线程读（doScan）。
     * 不加 @Volatile 的话，JMM 允许扫描线程一直读到旧值：典型表现是
     * 「刚切到新 App，扫描线程还在扫上一个包」，点击就落在别的应用界面上。
     * `watching` 是 volatile 只能保证它自己可见，保证不了这三个。
     */
    @Volatile
    private var watchPkg: String? = null
    @Volatile
    private var watchToken = 0
    @Volatile
    private var watchStart = 0L
    @Volatile
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

    /**
     * 本轮是否已经点过「自绘跳过按钮」。
     *
     * 单独一个标志，不跟 [coordTapped] 合用：规则里配了坐标的 App 和
     * 自绘按钮的 App 是两类，共用一个标志会让先跑的那个把后跑的那个挡掉。
     */
    private var drawTapped = false
    private var lastLearnAt = 0L
    private var watchScans = 0
    private var watchCost = 0L

    /** 扫描线程写、主线程读（stopWatch 判断要不要记「看到广告页但没跳过」），必须 volatile */
    @Volatile
    private var watchHit = false

    /**
     * 这一轮监测里有没有真的出现过「开屏广告页」。
     *
     * 只用来决定「未找到跳过按钮」要不要写进应用内记录：
     * 用户从桌面点开一个本来就没广告的 App，也会扫满一整个窗口期，
     * 每次都记一条「未找到跳过按钮」，看起来像功能天天在失效。
     * 只有真的见过广告页却没点掉，那条记录才有意义。
     */
    @Volatile
    private var sawAdPage = false

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

    /**
     * 全局点击闸：两次点击至少间隔 CLICK_GAP_MS。
     *
     * 扫描线程写（每次点击后），主线程读（noteUserClick 用它过滤「是不是我们
     * 自己点的」）。跨线程可见性必须靠 volatile：读不到最新值，我们自己的点击
     * 就会被当成用户输入，随后 1.5 秒内该拦的跳转全部放行。
     */
    @Volatile
    private var lastClickAt = 0L

    // ---------------------------------------------------------------- v1.5 实验：摇一摇前置
    //
    // 摇一摇全程没有点击，事件流里什么都看不到。只能在冷启动窗口里
    // 临时听加速度计——窗口一结束立刻注销，后台零成本。

    private val shakeDetector = ShakeDetector()
    private var shakeOn = false

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!shakeOn) return
            val hit = shakeDetector.feed(
                e.values[0], e.values[1], e.values[2], SystemClock.elapsedRealtime()
            )
            if (hit) onShaken()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 防重复点击：key -> 上次点击时间 */
    private val clickedAt = HashMap<String, Long>()

    // ---------------------------------------------------------------- 误点熔断
    //
    // 语义判断（SafeWords）能挡住已知形态，但总会遇到没见过的文案。
    // 这一层不看语义，只看行为：如果同一个 App 的同一块区域在 20 秒内被
    // 点了 3 次，那它就不可能是「一次性开屏按钮」，一定是认错了控件。
    // 停手 60 秒，代价是这段时间漏跳几个广告，收益是不会再把用户正在看的
    // 内容反复弄丢。
    private data class ClickMark(val pkg: String, val x: Int, val y: Int, val at: Long)

    private val clickMarks = ArrayDeque<ClickMark>()
    private var breakerPkg: String? = null
    private var breakerUntil = 0L

    private fun markClick(pkg: String, r: android.graphics.Rect, now: Long) {
        clickMarks.addLast(ClickMark(pkg, r.centerX(), r.centerY(), now))
        // 只保留窗口内的记录，防止无界增长
        while (clickMarks.isNotEmpty() && clickMarks.first().at < now - BREAKER_WINDOW_MS) {
            clickMarks.removeFirst()
        }
    }

    private fun tripped(pkg: String, r: android.graphics.Rect, now: Long): Boolean {
        while (clickMarks.isNotEmpty() && clickMarks.first().at < now - BREAKER_WINDOW_MS) {
            clickMarks.removeFirst()
        }
        val tol = dp(40)
        var n = 0
        for (m in clickMarks) {
            if (m.pkg != pkg) continue
            if (kotlin.math.abs(m.x - r.centerX()) < tol &&
                kotlin.math.abs(m.y - r.centerY()) < tol
            ) n++
        }
        return n >= BREAKER_MAX_HITS
    }

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
     * 第一次「拿不到活动窗口」的时间点，0 表示当前正常。
     *
     * 为什么需要它：`rootInActiveWindow` 返回 null 时，事件通道、轮询、扫描
     * 三条链会**一起静默**——进程活着、无障碍也 bound 着，但什么都发现不了。
     * 用户看到的就是「无障碍明明开着，一个广告都不跳」。
     * 而 KeepAliveService 的 `instance != null` 判据恰好会把它当成健康状态，
     * 所以必须由服务自己补一个反面信号（见 noteRootFail）。
     */
    private var rootFailSince = 0L

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
                val pkg = runCatching { activePackage() }.getOrNull()
                if (pkg == null) {
                    // 拿不到活动窗口：不能只是悄悄 return。
                    // 这一条如果不统计，「服务已经哑了」就永远没人发现。
                    main.post { runCatching { noteRootFail() } }
                    return@post
                }
                main.post {
                    rootFailSince = 0L
                    runCatching { handlePolled(pkg) }
                }
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
        // 「谁是桌面」交给系统回答，比维护静态名单可靠（见 AppLists.runtimeShell）
        AppLists.loadRuntimeShell(this)
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
     * 微窗有没有挂上，供诊断导出使用。
     *
     * 这个值比「悬浮窗权限有没有给」更接近真相：权限给了但 addView 失败
     * （ROM 拦截、窗口 token 失效），`canDrawOverlays` 仍然返回 true，
     * 而进程拿不到前台窗口数据，照样一个广告都不跳。
     */
    val ghostAlive: Boolean
        get() = ghost != null

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
     *
     * **v1.3.4**：第 1 级原来是无条件按返回（旧代码里这一级根本没复核）。
     * 400ms 里用户完全可能已经切走了——比如广告落地页刚起来、用户手快
     * 点了别的 App，那一发返回就砸在用户自己的界面上。
     * 「用户莫名其妙被弹回去」的第二类投诉就是这么来的。
     */
    fun backOffLanding(landingPkg: String, originPkg: String?) {
        // ---- 第 0 级：80ms 快按（v1.4.0 R8）----
        //
        // 落地页刚出来时，返回键常常被它的欢迎页吃掉，所以要试不止一次。
        // 但也不能像原来那样只等 400ms —— 那 400 毫秒用户是看得见的。
        //
        // 这一级只在「前台确实已经是落地页」时才动手。`activePackage()` 返回
        // null 说明还在转场，此时按返回很可能砸在**来源 App** 上（用户正在
        // 看的那一页就没了），交给 400ms 那级。
        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            h.post {
                val cur = runCatching { activePackage() }.getOrNull()
                if (cur != landingPkg) return@post
                main.post {
                    val ok = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                        .getOrDefault(false)
                    Log.i(TAG, "guard back#0 pkg=$landingPkg ok=$ok")
                }
            }
        }, 80L)

        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            // 读窗口必须在 scanHandler 上做，主线程读会死锁（见 poller 注释）
            h.post {
                val cur = runCatching { activePackage() }.getOrNull()
                // cur == null 表示系统暂时没给窗口（落地页刚冷启动时很常见），
                // 这时按返回是安全的——400ms 内用户几乎不可能刚好切走。
                // 但如果前台明确是**别的**包，说明用户已经自己切走了，绝不能按。
                if (cur != null && cur != landingPkg) {
                    Log.i(TAG, "guard back#1 skip pkg=$landingPkg cur=$cur")
                    return@post
                }
                main.post {
                    val ok = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                        .getOrDefault(false)
                    Log.i(TAG, "guard back#1 pkg=$landingPkg ok=$ok")
                }
            }
        }, 400L)

        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            // 读窗口必须在 scanHandler 上做，主线程读会死锁（见 poller 注释）
            h.post {
                val cur = runCatching { activePackage() }.getOrNull()
                if (cur != landingPkg) return@post
                main.post { guardRelaunch(landingPkg, originPkg) }
            }
        }, 1_200L)
    }

    /** 第 2 级：把原 App 拉回前台；1.2 秒后仍是落地页就走第 3 级 */
    private fun guardRelaunch(landingPkg: String, originPkg: String?) {
        val ok = originPkg != null && relaunch(originPkg)
        Log.i(TAG, "guard relaunch origin=$originPkg ok=$ok")
        main.postDelayed({
            val h = scanHandler ?: return@postDelayed
            h.post {
                val still = runCatching { activePackage() }.getOrNull()
                if (still != null && still != landingPkg) return@post
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
     *
     * flag 的选择（这里试错过两次，都记下来免得再改错）：
     * · **FLAG_ACTIVITY_NEW_TASK** 必须有：来源 App 的任务栈还在后台，
     *   不带它的话新 Activity 会加到**无障碍服务自己**的任务栈里，用户眼前
     *   什么都不会变。
     * · **不要加 FLAG_ACTIVITY_REORDER_TO_FRONT**：它会把「启动 Activity」
     *   顶到任务栈最前面。用户本来在 App 里的第 3 章 / 个人页，
     *   弹回来却落在首页——等于用另一种方式把用户弄丢。
     * · **v1.3.5 起也不要 FLAG_ACTIVITY_RESET_TASK_IF_NEEDED**。
     *   上一次留它的理由是「只在任务栈根 Intent 不一致时才重置」，
     *   但小窗 / 分屏 / 游戏悬浮窗这类多窗口场景下，App 的任务栈会被系统
     *   重新组织，根 Intent 比对**经常判成不一致**，于是任务栈被整体重置、
     *   栈顶回到启动 Activity。
     *
     *   实测表现正是「番茄小说从书页（ReaderActivity）退回书架
     *   （MainFragmentActivity）」——用户的书页和进度全丢。
     *   只带 NEW_TASK 时：任务栈存在就把原栈整体带到前台（**栈顶保留**），
     *   任务栈被系统回收了则等价于新建一个，效果与 RESET 相同。
     *   也就是说去掉它只在「本来就该保留原页面」的场景下改变行为，
     *   不会有任何场景因此变得更差。
     */
    private fun relaunch(pkg: String): Boolean {
        val i = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        // 系统外壳（桌面 / 负一屏 / 控制中心插件 / 语音助手）既不扫、也不当来源。
        // 它们的界面上全是「App 图标 / 卡片」，扫出来的控件没有任何跳过意义，
        // 而且一旦被当成来源 App，「冷启动 + 从没扫到内容页」会让防跳转
        // 把用户从负一屏点开的每个 App 都弹回去（v1.3.8 修的 bug）。
        return isHome(pkg) || pkg in AppLists.NEVER_SCAN || pkg in imePackages ||
            pkg in IME_FALLBACK || AppLists.isBrowser(pkg) || AppLists.isShell(pkg)
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
        hideTeachOverlay()
        main.removeCallbacks(poller)
        main.removeCallbacksAndMessages(null)
        runCatching { scanThread?.quitSafely() }
        scanThread = null
        scanHandler = null
        // 实验组件的清理：屏蔽层是窗口，服务没了还留着就是一块点不动的透明层
        TouchShield.destroy()
        stopShakeWatch()
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
                // cold 必须在本次事件改写状态**之前**算出来。
                // onForegroundChanged 末尾会更新 lastPkg / lastAppFgAt，
                // 提前算就永远是 false，「冷启动」这个信号就废了。
                val cold = calcCold()
                // 键盘弹出不是「跳转来源」，让它参与防跳转的时间窗会污染判定
                if (pkg !in imePackages && pkg !in IME_FALLBACK) {
                    // className 也一起传：systemui 的「最近任务 / 通知栏」
                    // 是用户自己切 App 的地方，要清空来源；而状态栏上弹出的
                    // 通知不是。两者只能靠类名分开（见 GuardDecision.isUserSurface）。
                    JumpGuard.onForeground(pkg, cold, this, e.className?.toString())
                }
                // systemui 只走上面那次「用户切页」判断，不参与监测与来源维护。
                if (pkg == "com.android.systemui") return
                // v1.5 实验：入口证据只记日志，不参与判定
                if (Prefs.expEntry) noteEntry(pkg, e.className?.toString())
                onForegroundChanged(pkg, cold)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 只记时间戳，不做任何判定。真正用它的是 JumpGuard：
                // 「用户点了一下商品卡」和「摇一摇把淘宝拉起来」在前台切换上
                // 完全一样，唯一的区别就是有没有这条事件。
                noteUserClick(pkg, e)
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

    /**
     * 用户点了一下屏幕（v1.4.0 的输入溯源）。
     *
     * 只记时间戳和「是不是全屏热区」，不做任何判定。真正用它的是 JumpGuard：
     * 「用户点了一下商品卡 → 淘宝起来了」和「摇一摇 → 淘宝起来了」在前台
     * 切换上完全一样，唯一的区别就是前者有这条事件。
     *
     * **必须过滤自家点击**。`Access.click` 走的是 `ACTION_CLICK`，系统照样
     * 会派发 `TYPE_VIEW_CLICKED`。不过滤的话，每跳过一次广告都会造出一个
     * 假「用户点击」，之后 1.5 秒内该拦的全都不拦——这比不做还糟。
     *
     * 全屏判断只在冷启动窗口内做：要读 `e.source` 的 bounds，一次 IPC。
     * 用户正常用 App 时点什么都不影响判定（那时来源早已 settled），
     * 没必要为每一巴掌都付这次 IPC。
     */
    private fun noteUserClick(pkg: String, e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (lastClickAt > 0L && now - lastClickAt < SELF_CLICK_MS) {
            Log.d(TAG, "input tap pkg=$pkg ignored=self ${now - lastClickAt}ms")
            return
        }
        val inColdWindow = watching && now - watchStart <= COLD_CLICK_WINDOW_MS
        val fullscreen = inColdWindow && isFullScreenClick(e)
        InputTrace.note(pkg, now, fullscreen, "view")
        Log.d(TAG, "input tap pkg=$pkg fullscreen=$fullscreen cold=$inColdWindow")
    }

    /**
     * 这次点击的目标是不是几乎铺满整块屏幕。
     *
     * 点击型广告（点任意位置跳转）的容器本身就是全屏 View，点它也会派发
     * `TYPE_VIEW_CLICKED`。如果把它当成「用户在选东西」，这类广告会从
     * 「拦」直接变成「不拦」。商品卡、按钮的面积远小于全屏，85% 这条线
     * 能分开两者。
     */
    private fun isFullScreenClick(e: AccessibilityEvent): Boolean {
        val src = runCatching { e.source }.getOrNull() ?: return false
        return try {
            val r = android.graphics.Rect()
            src.getBoundsInScreen(r)
            val dm = resources.displayMetrics
            val screen = dm.widthPixels.toLong() * dm.heightPixels
            if (screen <= 0L) return false
            val w = r.width().toLong().coerceAtLeast(0L)
            val h = r.height().toLong().coerceAtLeast(0L)
            w * h * 100L / screen >= 85L
        } catch (t: Throwable) {
            false
        } finally {
            Access.recycle(src)
        }
    }

    // ---------------------------------------------------------------- v1.5 实验：摇一摇前置

    /** 冷启动窗口开始时挂上加速度计（只在开了实验开关时） */
    private fun startShakeWatch() {
        if (!Prefs.expShake || shakeOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        shakeDetector.reset()
        val ok = runCatching {
            sm.registerListener(shakeListener, s, SensorManager.SENSOR_DELAY_GAME)
        }.getOrDefault(false)
        if (ok) {
            shakeOn = true
            Log.d(TAG, "shake watch start")
        }
    }

    private fun stopShakeWatch() {
        if (!shakeOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        runCatching { sm?.unregisterListener(shakeListener) }
        shakeOn = false
        shakeDetector.reset()
        Log.d(TAG, "shake watch stop")
    }

    /**
     * 摇了一下。只有在「屏幕上确实有广告」时才动手。
     *
     * 没有指纹门禁的话，走路、骑车、掏手机都会触发；而「有指纹但用户
     * 自己想摇」的情况——把广告关掉也无害。
     */
    private fun onShaken() {
        val pkg = watchPkg ?: return
        if (!JumpGuard.adSeenRecent(pkg, SystemClock.uptimeMillis())) {
            Log.d(TAG, "shake ignored pkg=$pkg 没有广告指纹")
            return
        }
        Log.i(TAG, "shake hit pkg=$pkg 落地前处理")
        SkipLog.add("摇一摇：在落地前处理广告（${pkg.substringAfterLast('.')}）")
        requestScan(0L)
    }

    /**
     * R13：目的地的首帧 Activity 是不是它的 launcher Activity。
     *
     * 相等 → 用户点图标 / 通知进来（意图明确）；
     * 不等 → 大概率是 deeplink 进来的（广告）。
     *
     * v1.5 只记日志：很多 App 的 deeplink 也走主 Activity（淘宝 WelcomeActivity），
     * 这个信号的强弱必须先拿真机数据看，不能直接进判定。
     */
    private fun noteEntry(pkg: String, cls: String?) {
        if (cls == null) return
        val launch = runCatching {
            packageManager.getLaunchIntentForPackage(pkg)?.component?.className
        }.getOrNull()
        val kind = when {
            launch == null -> "unknown"
            launch == cls -> "launcher"
            else -> "deeplink"
        }
        Log.d(TAG, "guard entry pkg=$pkg cls=$cls launch=$launch -> $kind")
    }

    /**
     * 这个 App 是不是「冷启动」进来的。
     *
     * 判据和 [onForegroundChanged] 里原本写的一样，但**只读不写**：
     * 必须在本轮事件改写 lastPkg / lastAppFgAt 之前调用，
     * 否则算出来永远是 false。
     *
     * 这个信号对「防跳转」很关键：摇一摇广告只可能发生在冷启动阶段，
     * 而它又是「该不该弹回」的核心判据之一。猜错一次就是一次误回退。
     */
    private fun calcCold(): Boolean {
        val prev = lastPkg
        val cameBack = leftAppAt > lastAppFgAt
        return cameBack || prev == null || isPassThrough(prev)
    }

    private fun onForegroundChanged(pkg: String, cold: Boolean) {
        if (!Prefs.skipSplash && !Prefs.autoLearn) {
            stopWatch()
            lastPkg = pkg
            // 这一支原来不更新 lastAppFgAt，导致 cameBack 永远为真、
            // cold 永远为 true。防跳转现在也要用 cold，必须一起维护。
            lastAppFgAt = SystemClock.uptimeMillis()
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
        val changed = pkg != lastPkg
        // 包名没变也必须重开一轮：用户从桌面 / 最近任务重新打开同一个 App，
        // 那是全新一次冷启动，同样有开屏广告。
        // 少了这个判断，同一个 App 第二次打开起就永远不再监测，
        // 表现就是「刚装好能用，用一会儿就跳不过了」。
        val cameBack = leftAppAt > lastAppFgAt

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

        // 轮询这条路也要算 cold：事件通道断掉时，前台切换只能靠轮询发现，
        // 「冷启动」这个判据缺了就会漏掉摇一摇广告。
        val cold = calcCold()
        // 输入法要过滤掉，和事件路径保持一致。
        // 软键盘弹出时 rootInActiveWindow 在不少 ROM 上返回输入法包，
        // 一旦让它顶替掉来源 App，真实来源就被遗忘 → origin 链被截断 → 漏拦。
        if (pkg != "com.android.systemui" && pkg !in imePackages && pkg !in IME_FALLBACK) {
            JumpGuard.onForeground(pkg, cold, this)
        }
        onForegroundChanged(pkg, cold)

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

    /**
     * 连续拿不到活动窗口 —— 服务已经哑了。
     *
     * 失效链条：HyperOS 冻结 / 解冻之后，系统会停止把窗口给无障碍，
     * `rootInActiveWindow` 开始一直返回 null。此时：
     *   · 事件通道没事件可派发；
     *   · 轮询拿不到前台包名；
     *   · 扫描器 pickRoot 也返回 null（日志里那条「取不到 XXX 的界面」）。
     * 三条链同时断，界面上却一切正常（无障碍显示已开启），用户完全无从判断。
     *
     * 判据用「持续时长」而不是「次数」：轮询间隔会在 700ms 和 4000ms 之间切换，
     * 按次数算会让阈值随场景漂移。持续 60 秒拿不到任何窗口，不可能是转场动画。
     *
     * 恢复动作交给 KeepAliveService 统一执行（它管着自愈冷却和日志）。
     */
    private fun noteRootFail() {
        if (!Prefs.masterEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (rootFailSince == 0L) {
            rootFailSince = now
            return
        }
        val silent = now - rootFailSince
        if (silent < ROOT_FAIL_MS) return
        // 先重置，避免在杀进程前的 500ms 里被重复触发
        rootFailSince = now
        Log.w(TAG, "rootInActiveWindow null for ${silent}ms, service looks deaf")
        SkipLog.add("连续 ${silent / 1000} 秒取不到任何界面，自动重启恢复")
        KeepAliveService.requestHeal(this)
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
        // v1.5 实验：开屏窗口 = 摇一摇唯一可能发生的时间段，只在这里听传感器
        startShakeWatch()
        // 告诉防跳转：来源 App 现在处在「可监测的开屏阶段」。
        // 这是「来源仍在开屏」这个判据的唯一依据——只有真的起了监测窗口
        // 才有资格说它还停在开屏期，没监测过的一律当作「不知道」，宁可不拦。
        JumpGuard.noteSplashStarted(pkg)
        coordTapped = false
        drawTapped = false
        watchScans = 0
        watchCost = 0L
        watchHit = false
        sawAdPage = false
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
            // 监测窗口结束 ≠ 用户已经进入正片，所以这里不清「被监测过」标记，
            // 只有扫到内容页才算（见 JumpGuard.noteContentPage）。
            watchPkg?.let { JumpGuard.noteSplashEnded(it) }
            // 扫了一整轮什么都没点，把原因留在应用内记录里，
            // 用户不用连电脑也能看到「是没找到按钮，还是根本没拿到界面」
            if (!watchHit && sawAdPage) {
                val n = rules?.apps?.get(watchPkg ?: "")?.name?.ifBlank { null } ?: watchPkg
                SkipLog.add("看到广告页但没认出跳过按钮：$n")
            }
        }
        watching = false
        watchPkg = null
        stopShakeWatch()
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

    /**
     * 监测窗口到期前的最后一眼：只看「来源是不是已经进入正片」，不点任何东西。
     *
     * 为什么需要这一眼：监测窗口长度来自规则里的 `splash.duration`
     * （内置默认 8 秒，规则可以配到 10 秒）。有些 App 的开屏广告比这还长，
     * 内容页出现在窗口之后，来源就永远停在「还没进正片」，
     * 用户随后在 App 里点了个商品卡跳淘宝，就会被误判成广告跳走 → 弹回。
     *
     * 这一眼是纯读取：只调 [JumpGuard.noteContentPage]，不产生任何点击。
     * 代价是监测窗口结束后多一次整树扫描（开屏页几毫秒，内容页几百毫秒）。
     *
     * 换来的是「误回退」这个最招骂的 bug 少一类成因——值得。
     */
    private fun checkContentPageOnce(pkg: String) {
        val rs = rules ?: return
        val app = rs.apps[pkg] ?: if (Prefs.skipAllApps) rs.generic else return
        val root = pickRoot(pkg) ?: return
        if (runCatching { root.packageName?.toString() }.getOrNull() != pkg) {
            Access.recycle(root)
            return
        }
        val dm = resources.displayMetrics
        val result = runCatching {
            // allowHeuristic=false：这一眼不允许猜，也不点。
            SplashScanner.scan(root, app.splash, dm.widthPixels, dm.heightPixels, false, true)
        }.getOrNull() ?: return
        try {
            if (result.contentPage) {
                Log.d(TAG, "final content page pkg=$pkg nodes=${result.nodes}")
                JumpGuard.noteContentPage(pkg)
            }
        } finally {
            SplashScanner.recycle(result)
        }
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
            // 到期前最后再看一眼：只判断「来源是不是已经进入正片」，不点任何东西。
            checkContentPageOnce(pkg)
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
                // 只进 logcat，不进应用内记录：冷启动瞬间拿不到窗口是常态
                // （App 还没画第一帧），用户看到只会以为软件坏了。
                // 服务真的哑掉有另一条判定（连续 60 秒取不到窗口）。
                Log.d(TAG, "root null pkg=$pkg（冷启动早期属正常）")
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
        // 熔断：同一个 App 的同一位置在 20 秒内被点第 3 次，几乎可以断定
        // 我们认错了控件——真正的一次性开屏按钮不会被点这么多次。
        // 停手 60 秒并留记录，避免「未知误点」无限重复。
        // 真实事故：番茄阅读页那个「点击关闭广告并退出小说」每 4 秒被点一次，
        // 用户每次重新进书页都被弹回书架。
        if (breakerPkg == pkg && now < breakerUntil) return true
        // 扫到的是「正常内容页」（有列表 / 频道栏 / 文字密集 / 可点元素多），
        // 说明这个 App 的开屏阶段已经过去。之后它再跳电商/商店，是用户自己点的，
        // 不该按返回。判定逻辑见 JumpGuard.noteContentPage。
        if (hit == null && result.contentPage) JumpGuard.noteContentPage(pkg)
        // 广告 SDK 指纹：只记 8 秒，给 JumpGuard 划分证据档位用（v1.4.0）。
        // 即便这一轮没命中跳过按钮也要报 —— 指纹说的是「屏幕上有广告」，
        // 和「找没找到跳过按钮」是两件事。
        if (result.splashMark || result.adFingerprint) sawAdPage = true
        if (result.adFingerprint) {
            JumpGuard.noteAdSeen(pkg)
            // v1.5 实验：确认屏幕上有广告 SDK，就盖一层触摸屏蔽，
            // 把「点任意位置跳转」的点击吃掉。3 秒自动撤除，右下角有解除入口。
            if (Prefs.expShield) {
                TouchShield.cover(this@SkipAccessibilityService)
            }
        }
        if (hit != null) {
            val key = "splash:$pkg"
            // 同一轮里点掉之后要给足冷却：很多内容页上也有「跳过」类文字，
            // 1.2 秒的冷却会让它在内容页反复点，风险很高。
            if (isRecent(key, 4_000L)) return true
            // 全局点击闸：一轮扫描里最多点一次，两次点击间隔不小于 CLICK_GAP_MS
            if (now - lastClickAt < CLICK_GAP_MS) return true
            // 自识别是猜的，一轮监测只允许猜中一次
            if (!hit.byRule && heurClicks >= 1) return true

            // 熔断第二道：这个位置已经被点过太多次，停手
            if (tripped(pkg, hit.rect, now)) {
                breakerPkg = pkg
                breakerUntil = now + BREAKER_HOLD_MS
                SkipLog.add("同一位置连点 ${BREAKER_MAX_HITS} 次，暂停该应用自动点击 60 秒")
                Log.w(TAG, "breaker tripped pkg=$pkg label=${hit.label} rect=${hit.rect}")
                return true
            }

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
                markClick(pkg, hit.rect, now)
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

        // ---- 兜底：纯自绘的「跳过」按钮（无文字 / 无 id / 无描述）
        //
        // 穿山甲竖屏开屏模板把「跳过」两个字画在 Canvas 上，节点树里一个字
        // 都没有。实测小红书那条开屏广告就是这样：整页只有 2 个可点节点，
        // 一个 150×150 的圆形跳过、一个 120×53 的「广告」小标签，
        // 两者的 text / content-desc / resource-id 全是空。
        // 关键词匹配永远命中不了，只能靠「方形 + 小 + 贴边」认形状。
        //
        // 形状是会撞的（视频 App 主界面也有 150×150 的发布按钮），
        // 所以必须同时满足：
        //   ① 有开屏广告强标记（[SplashScanner] 的 SPLASH_MARK / 广告 SDK id）；
        //   ② 页面不是内容页；
        //   ③ 还在开屏窗口内。
        // 三条都成立才动手，宁可漏，不可误点。
        val draw = result.drawSkip
        if (draw != null && result.splashMark && !result.contentPage &&
            !drawTapped && !isRecent("splash:$pkg", 4_000L) &&
            now - lastClickAt >= CLICK_GAP_MS && now - watchStart < DRAW_SKIP_WINDOW_MS
        ) {
            drawTapped = true
            TouchShield.releaseForTap(this)
            if (Access.tapAt(this, draw.exactCenterX(), draw.exactCenterY())) {
                lastClickAt = now
                watchHit = true
                markClicked("splash:$pkg")
                Prefs.bumpSkip()
                SkipLog.add("跳过 ${app.name.ifBlank { pkg }} · 自绘跳过按钮")
                Log.i(TAG, "skipped pkg=$pkg label=自绘 how=shape rect=$draw")
                watchDeadline = maxOf(watchDeadline, now + 700L)
            }
        }

        // 规则里只有坐标时，等界面稳定一点再按坐标点一次
        if (splash.coords.isNotEmpty() && !coordTapped && now - watchStart > 650L &&
            now - lastClickAt >= CLICK_GAP_MS
        ) {
            coordTapped = true
            val r = splash.coords.first()
            // v1.5 实验：屏蔽层会吃掉 dispatchGesture 注入的触摸，
            // 先临时撤掉再点，点完自己会盖回来。
            TouchShield.releaseForTap(this)
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
                    if (clicked) {
                        // 关弹窗也是一次「我们点的」。这一处原来漏了，
                        // 后果是每关一个弹窗就造出一个假用户点击，
                        // 之后 1.5 秒内该拦的跳转全被放过（v1.4.0 R4）。
                        lastClickAt = SystemClock.uptimeMillis()
                        break
                    }
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

    // ------------------------------------------------------------------ 学弹窗

    private var teachOverlay: View? = null
    private var teachTimeout: Runnable? = null
    private var teachCands: List<PopupTeacher.Cand> = emptyList()
    private var teachScreenArea = 0L
    private var teachPkg = ""
    private var teachAppName = ""

    /**
     * 进入「学弹窗」：先把当前界面压成候选列表，再盖一层遮罩让用户指出目标。
     *
     * 顺序不能反。如果先盖遮罩再抓树，用户可能在树还没读完时就点了，
     * 那一击会被白白吃掉，看起来就是「按了没反应」。
     *
     * 抓树必须在 scanHandler 上做：读窗口是同步 IPC，放主线程会和系统的
     * 事件派发互相等，几秒钟就能把整个手机卡住（见 poller 的注释）。
     * 放上去之后主线程只负责最后弹遮罩，中间那 100~300ms 用户是无感的。
     */
    fun beginTeach() {
        if (teachOverlay != null) {
            hideTeachOverlay()
            return
        }
        val h = scanHandler
        if (h == null) {
            toast("服务刚启动，等一秒再试")
            return
        }
        h.post {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            if (root == null) {
                main.post { toast("取不到当前界面") }
                return@post
            }
            val tree = Access.walk(root, Access.MAX_NODES)
            try {
                val pkg = runCatching { tree.firstOrNull()?.packageName?.toString() }
                    .getOrNull().orEmpty()
                val dm = resources.displayMetrics
                val area = dm.widthPixels.toLong() * dm.heightPixels
                val cands = PopupTeacher.collect(tree, area)
                val appName = appLabel(pkg)
                main.post {
                    if (pkg.isBlank() || pkg == packageName) {
                        toast("在秒跳自己里面学不了，切到那个 App 再试")
                        return@post
                    }
                    if (cands.isEmpty()) {
                        toast("这个界面上没有读得出文字的按钮")
                        return@post
                    }
                    teachCands = cands
                    teachScreenArea = area
                    teachPkg = pkg
                    teachAppName = appName
                    showTeachOverlay()
                }
            } finally {
                // Cand 是抽出来的纯数据，节点在这里释放掉不影响后续判定
                Access.recycleAll(tree)
            }
        }
    }

    /**
     * 学习遮罩：一层几乎透明的全屏视图，专门用来截获「用户指的是哪一个按钮」。
     *
     * 为什么不让用户从列表里挑：弹窗上往往有五六个可点节点（卡片、标题、
     * 两个按钮、遮罩），文字列表既长又难分辨；让他在原地戳一下那个按钮，
     * 是唯一不会选错的交互。
     */
    private fun showTeachOverlay() {
        hideTeachOverlay()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("需要悬浮窗权限，去首页把「悬浮窗」打开")
            return
        }
        val dm = resources.displayMetrics
        val root = android.widget.FrameLayout(this)

        val catcher = View(this).apply {
            setBackgroundColor(0x2E000000)
            isClickable = true
            setOnTouchListener { _, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_UP) {
                    onTeachTap(ev.rawX.toInt(), ev.rawY.toInt())
                }
                true
            }
        }
        root.addView(catcher, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val tip = android.widget.TextView(this).apply {
            text = "点一下你要关掉的那个按钮"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE6000000.toInt())
            }
        }
        root.addView(tip, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dm.heightPixels / 6
        })

        val cancel = android.widget.TextView(this).apply {
            text = "取消"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC333333.toInt())
            }
            setOnClickListener { hideTeachOverlay() }
        }
        root.addView(cancel, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(48)
        })

        val lp = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(root, lp)
            teachOverlay = root
            SkipLog.add("学弹窗：等你指按钮")

            // 15 秒自动收。遮罩是全屏可点的，一旦用户忘了点「取消」就去干别的，
            // 整个屏幕会变成一块砖——他连回桌面都得先猜出这层是干什么的。
            // 真机上就是这么把自己锁在外面的，所以这条兜底不能省。
            val t = Runnable { hideTeachOverlay() }
            teachTimeout = t
            main.postDelayed(t, 15_000L)
        }.onFailure {
            Log.w(TAG, "teach overlay failed", it)
            teachOverlay = null
            toast("遮罩没加上：${it.message}")
        }
    }

    private fun hideTeachOverlay() {
        teachTimeout?.let { main.removeCallbacks(it) }
        teachTimeout = null
        teachOverlay?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        teachOverlay = null
    }

    /** 用户戳了遮罩：把坐标还原成节点，再拼成一条 popups 规则 */
    private fun onTeachTap(x: Int, y: Int) {
        val target = PopupTeacher.pickAt(teachCands, x, y, teachScreenArea)
        if (target == null) {
            toast("这里没有可学的按钮，往按钮上点准一点")
            return
        }
        val anchor = PopupTeacher.anchorFor(teachCands, target)
        if (anchor == null) {
            hideTeachOverlay()
            toast("这个弹窗上读不出能当条件的字，暂时学不了")
            return
        }
        val rule = PopupTeacher.buildRule(target, anchor)
        if (rule == null) {
            hideTeachOverlay()
            toast("这个按钮读不出文字，暂时学不了")
            return
        }
        val merged = TaughtRulesStore.add(Prefs.taughtRules, teachPkg, teachAppName, rule)
        hideTeachOverlay()
        if (merged == null) {
            toast("这条规则已经学过了")
            return
        }
        Prefs.taughtRules = merged
        reloadRules()
        val app = teachAppName.ifBlank { teachPkg }
        SkipLog.add("学会弹窗 · $app · 点 ${target.label()}")
        // 最常见的形态是「条件」和「动作」是同一个按钮（如「稍后再看」），
        // 照模板说出来会变成「出现『稍后再看』时点 稍后再看」，读着像重复了两遍
        toast(
            if (anchor == target.label()) "已学会：看到「$anchor」就直接点它"
            else "已学会：出现「$anchor」时点 ${target.label()}"
        )
    }


    /**
     * 学弹窗时回显用的应用名。
     *
     * 先用规则里的 name（内置库和远程库都写了中文名），没有再问系统要。
     * 绝不能直接把包名甩给用户——「com.tencent.mm」没人认得出是微信，
     * 而学完之后弹窗提示里就要显示这个名字。
     */
    private fun appLabel(pkg: String): String {
        rules?.apps?.get(pkg)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("").take(24)
    }

    /** 无障碍服务这边没有 Activity，Toast 只能直接挂到系统窗口上 */
    private fun toast(msg: String) {        runCatching {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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
