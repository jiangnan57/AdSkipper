package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.miaotiao.adskip.data.AppLists
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.engine.GuardDecision
import com.miaotiao.adskip.util.SkipLog

/**
 * 防跳转。
 *
 * 原理很朴素：无障碍服务看不到 Intent，也看不到「摇一摇」的传感器事件，
 * 只能在前台包名已经变化之后补救——检测到跳去了应用商店/浏览器/广告落地页，
 * 就立刻按一次返回。所以真实表现是「闪一下再弹回来」，做不到零延迟拦截。
 *
 * 这一版重点解决「乱返回」和「该返回时没返回」：
 *
 *   1. 微信 / QQ / 支付宝里点小程序、点分享链接，本来就会跳到浏览器或商店，
 *      那是用户自己点的，不该被弹回来。这些 App 双向豁免（AppLists.USER_DRIVEN）。
 *   2. 浏览器作为目的地只在「广告高发的来源」里才拦（从浏览器跳浏览器、
 *      从邮件跳浏览器属于正常上网，一律放过）。
 *   3. 拦截窗口从 4 秒收紧到 2.5 秒，冷却从 1.5 秒放宽到 3 秒，
 *      避免连按返回把用户自己的页面也退掉。
 *   4. 新增「广告落地页」一类目的地（电商 / 内容 App）。这是为了
 *      WakeUp 课程表那类穿山甲「摇一摇」开屏广告：摇一下直接拉起淘宝/商店，
 *      传感器事件无障碍根本看不见，只能落地后按返回。
 *
 * **v1.3.4 的结构性修复**（都是「用户被莫名其妙弹回去」的真实投诉）：
 *
 *   A. **origin 追踪断链**。旧代码第一句就是
 *      `if (isBlocked(pkg)) { ...; return }`，命中就提前 return，
 *      于是「来源」永远记不上被拦名单里的那些包（抖音、番茄小说、淘宝……）。
 *      后果是 dt 一直拿一个错的时间戳算，判定全乱。现在改成
 *      **先用上一段来源做判定，判完再把来源换成当前 App**，两个职责彻底分开。
 *   B. **同包内切页被当成跳转**。旧代码每来一次窗口事件就重判一次，
 *      不管包名有没有变。「番茄小说打开个人主页」包名不变，
 *      却被拿上一个 App 的时间戳判成「跳到了落地页」→ 弹回。
 *      现在只有**包名真的变了**才判定。
 *   C. **支付宝被当成广告落地页**。它同时出现在「用户主动打开的名单」和
 *      「广告落地页名单」里，而落地页判断在前，NFC 唤起支付宝就被弹回去。
 *      现在用户主动打开的名单优先级最高，而且支付宝已经从落地页名单里移除。
 *   D. **内容型 App 与电商落地页拆开**。番茄小说、抖音、小红书这些
 *      用户点进去就是「想看」，误弹回代价远大于漏弹回，单独成组、门槛更高。
 *   E. 判定逻辑本身抽到了 `GuardDecision`（纯 Kotlin，无 Android 依赖），
 *      可以写单元测试覆盖各种时序组合，不用再靠真机碰运气复现。
 */
object JumpGuard {

    /**
     * 当前「来源 App」——上一个真正在前台的 App。
     *
     * 判定回退时用的就是它。注意它是**判定之前**的快照：
     * 先拿它判「这次跳转该不该弹回」，判完才把它换成新前台，
     * 否则来源会变成目的地自己，dt 永远算成 -1。
     *
     * 下面几个字段是跨线程共享的：主线程的 onForeground 读写它们，
     * 扫描线程的 noteContentPage 也写其中两个。所以都要 @Volatile，
     * 并且所有读写都放在 @Synchronized 方法里，避免
     * 「主线程刚换来源、扫描线程顺手把新来源标成已进正片」这种竞态。
     */
    @Volatile
    private var originPkg: String? = null

    /** 来源 App 进入前台的时刻 */
    @Volatile
    private var originAt = 0L

    /**
     * 来源 App 是否是**冷启动**进来的。
     *
     * 由 SkipAccessibilityService 直接告知（它知道上一个前台是不是桌面）。
     * 摇一摇广告只可能发生在冷启动阶段：用户点图标打开 App → 开屏广告渲染 →
     * 手一晃 → 直接拉起电商。
     */
    @Volatile
    private var originCold = false

    /**
     * 来源 App 是否已经「进入正片」。
     *
     * 由扫描器在来源 App 上扫到了正常内容页时置位（见 noteContentPage）。
     * 已经进入正片 = 用户正在正常使用它 = 之后的跳转多半是用户自己点的。
     *
     * **这个信号过去对被拦名单里的 App 完全失效**——因为旧代码的 origin
     * 断链，noteContentPage 里 `pkg == originPkg` 永远不成立。
     * 修好断链之后它才真正起作用，这是「番茄小说个人主页被弹回」的第二层根因。
     */
    @Volatile
    private var originSettled = false

    /**
     * 来源 App 是否确实被开屏监测覆盖过。
     *
     * 「仍在开屏阶段」这个结论必须有观察依据，不能靠时间猜：
     * 只有我们真的对来源 App 起过监测窗口、并且在那个窗口里
     * **始终没扫到内容页**，才能说它还停在开屏阶段。
     *
     * 反过来，压根没被监测过的来源（比如用户关掉了「跳过开屏广告」，
     * 或者该 App 没有任何规则），一律当作「不知道」，由
     * GuardDecision 的降级窗口兜底。
     */
    @Volatile
    private var originWatched = false

    /**
     * 上一次记录的「真正的前台 App 包名」。
     *
     * 用来判断这一次事件是不是**换了 App**。同一个 App 内部切 Activity
     * （个人主页、评论区、详情页）不算跳转。
     */
    @Volatile
    private var lastRealPkg: String? = null

    /** 上一次真正按下返回的时刻，用来防连按 */
    @Volatile
    private var lastBackAt = 0L

    /**
     * 上一次被拦截时用的来源 App。
     *
     * 拦截之后三级兜底会把用户**重新拉回**这个 App，那是一次全新的冷启动
     * （进程可能已经被杀，开屏广告会重播一遍）。如果这时还按「不是冷启动」
     * 来判，紧接着的第二次摇一摇就漏拦了。
     *
     * 所以记下来，等它回到前台时重新武装成冷启动 + 仍在开屏期。
     */
    @Volatile
    private var restoringPkg: String? = null

    /** restoringPkg 的有效期：超过这个时间还没回去就作废 */
    private var restoringAt = 0L

    /** 弹回来源 App 的有效期：15 秒 */
    private const val RESTORE_TTL_MS = 15_000L

    private var launcherPkg: String? = null

    /** 拦截名单缓存，避免每次事件都重新解析字符串 */
    private var cachedRaw: String? = null
    private var cachedList: List<String> = emptyList()

    /**
     * 前台 App 变了。
     *
     * @param pkg 新的前台包名
     * @param cold 这个 App 是不是冷启动进来的（由 service 提供，它知道
     *   上一个前台是不是桌面）。这个信号只有 service 手里有，
     *   JumpGuard 自己猜不出来——猜错一次就是一次误回退。
     */
    @Synchronized
    fun onForeground(pkg: String, cold: Boolean, service: SkipAccessibilityService) {
        if (!Prefs.guardJump) return
        val now = SystemClock.uptimeMillis()

        // 回到桌面 = 清空上下文，避免从桌面打开商店被误拦
        if (pkg == launcherOf(service)) {
            reset()
            lastRealPkg = pkg
            return
        }
        if (pkg == service.packageName || pkg == "com.android.systemui") return

        // 同一个 App 内部切 Activity（个人主页、详情页、评论区）：
        // 包名没变 = 不是一次跳转，压根不该进判定。
        // 「番茄小说打开个人主页时回退」就是被这一条挡住的。
        val changed = pkg != lastRealPkg
        lastRealPkg = pkg
        if (!changed) {
            // 这一条也要留日志。用户反馈「莫名其妙被弹回去」时，
            // 得能区分「判定为 KEEP」和「压根没进判定」——
            // 否则日志里一片空白，根本看不出发生过什么。
            android.util.Log.d(
                "AdSkip",
                "guard skip pkg=$pkg 同一应用内切换，不参与跳转判定"
            )
            return
        }

        // ---- 拦截后弹回来源 App：重新武装成冷启动 ----
        //
        // 三级兜底会把用户重新拉回来源 App，那是一次全新的冷启动，
        // 开屏广告会重播一遍。这时候如果还按「不是冷启动」来判，
        // 紧接着的第二次摇一摇就漏拦了。
        var effectiveCold = cold
        var forceWatched = false
        val restoring = restoringPkg
        if (restoring != null && restoring == pkg && now - restoringAt < RESTORE_TTL_MS) {
            restoringPkg = null
            effectiveCold = true
            forceWatched = true
            android.util.Log.d("AdSkip", "guard restore pkg=$pkg 重新按冷启动处理")
        }

        // ---- 判定用「上一段来源」的快照 ----
        //
        // 旧代码在这里犯了一个更隐蔽的错：先更新 origin，再拿 origin 判定，
        // 于是 origin 变成了目的地自己，dt 永远算成 -1。
        // 正确顺序是：快照 → 判定 → 再把来源换成当前前台。
        val origin = originPkg
        val originAtSnapshot = originAt
        val originColdSnapshot = originCold
        val originSettledSnapshot = originSettled
        val originWatchedSnapshot = originWatched

        val isStore = isStore(pkg)
        val isBrowser = AppLists.isBrowser(pkg)
        val isLanding = AppLists.isAdLanding(pkg)
        val isContent = AppLists.isContentApp(pkg)
        val isCustom = isInUserBlocklist(pkg)
        val destIsUserDriven = pkg in AppLists.USER_DRIVEN

        val dt = if (origin != null && origin != pkg) now - originAtSnapshot else -1L
        val ctx = GuardDecision.Context(
            destPkg = pkg,
            destChanged = changed,
            originPkg = origin,
            originCold = originColdSnapshot,
            originSettled = originSettledSnapshot,
            // 「仍在开屏阶段」= 监测过 + 一直没扫到内容页
            originShowingSplash = originWatchedSnapshot && !originSettledSnapshot,
            dtMs = dt,
            sinceLastBackMs = if (lastBackAt > 0L) now - lastBackAt else Long.MAX_VALUE,
            destIsStore = isStore,
            destIsBrowser = isBrowser,
            destIsLanding = isLanding,
            destIsContent = isContent,
            destIsCustom = isCustom,
            destIsUserDriven = destIsUserDriven,
            originIsUserDriven = origin != null && origin in AppLists.USER_DRIVEN,
            originIsAdProne = origin != null && origin in AppLists.AD_PRONE
        )

        val verdict = GuardDecision.decide(ctx)

        // ---- 判定结束，把「来源」换成当前前台 App ----
        //
        // 无论这次切换是拦还是放，来源都要更新——用户此刻就在这个 App 里。
        // 新来源的状态全部重置：冷不冷启动由 service 给，
        // 「已进正片」「监测过」都要重新积累。
        originPkg = pkg
        originAt = now
        originCold = effectiveCold
        originSettled = false
        // 被弹回来的来源 App 此刻正在重播开屏，直接算「仍在开屏期」。
        originWatched = forceWatched

        // 无论拦不拦都留一条日志：用户反馈「莫名其妙弹回去了」时，
        // 这一行就是唯一能说清原因的证据。
        android.util.Log.d(
            "AdSkip",
            "guard pkg=$pkg store=$isStore browser=$isBrowser landing=$isLanding " +
                "content=$isContent custom=$isCustom userDriven=$destIsUserDriven " +
                "origin=$origin dt=${dt}ms cold=$originColdSnapshot settled=$originSettledSnapshot " +
                "watched=$originWatchedSnapshot -> ${verdict.action} ${verdict.reason}"
        )

        if (!verdict.shouldBack) return

        lastBackAt = now
        // 记下要弹回哪个 App：等它回到前台时重新按冷启动 + 开屏期处理。
        if (origin != null) {
            restoringPkg = origin
            restoringAt = now
        }
        // 不要在这里直接 performGlobalAction：落地页此刻多半还在冷启动，
        // 返回键会被它的欢迎页吃掉（淘宝实测连按两次都没反应）。
        // 交给 service 做三级兜底：返回 → 拉起原 App → 回桌面。
        service.backOffLanding(pkg, origin)
        SkipLog.add("拦截跳转 ${pkg.substringAfterLast('.')}：${verdict.reason}")
    }

    /**
     * 扫描器在来源 App 上看到了「正常内容页」。
     *
     * 判据来自 SplashScanner 的 contentPage：有可滚动列表 / 可点元素多 /
     * 底部有首页·推荐·我的这类频道栏 / 文字密集。开屏广告页不满足这些条件，
     * 所以「contentPage 出现过」等价于「开屏广告已经结束，App 进入正片」。
     *
     * 为什么要它：test_guard.sh 的场景 D（打开 App 用 5 秒再跳淘宝）原来会被
     * 误判成广告按返回。日志里 dt=5350ms、cold=true，和真实摇一摇的时序
     * 完全重叠，只靠时间窗分不开；但那时屏幕上已经扫到 526 个节点的课表页，
     * 有了这个信号就能放过。
     *
     * 只对同一个来源 App 生效：换 App 时会话已经重置，旧标记不能挪用。
     */
    @Synchronized
    fun noteContentPage(pkg: String) {
        if (pkg != originPkg) return
        if (originSettled) return
        originSettled = true
        android.util.Log.d("AdSkip", "guard note content page pkg=$pkg")
    }

    /**
     * 来源 App 的开屏监测开始了。
     *
     * 只有真正起了监测窗口的 App 才会被标上「被监测过」。
     * 这条信号是「来源仍在开屏阶段」的**唯一依据**——
     * 没有它就只能靠时间猜，而时间猜不出来（摇一摇落地是 5 秒，
     * 用户自己点商品卡也是 5 秒，两个场景的时间窗完全重叠）。
     */
    @Synchronized
    fun noteSplashStarted(pkg: String) {
        if (pkg != originPkg) return
        originWatched = true
        android.util.Log.d("AdSkip", "guard watch started pkg=$pkg")
    }

    /**
     * 来源 App 的开屏期正常结束了。
     *
     * 两种结束方式：跳过了广告，或者压根没有广告但监测窗口走完。
     *
     * 注意这里**不**清掉「被监测过」标记——监测窗口结束不等于用户
     * 已经进入正片，只有扫到内容页才算（见 noteContentPage）。
     * 摇一摇广告落地时源 App 的监测窗口多半已经结束，但广告确实还在播，
     * 这时仍要能识别出「来源还停在开屏阶段」。
     */
    @Synchronized
    fun noteSplashEnded(pkg: String) {
        if (pkg != originPkg) return
        android.util.Log.d("AdSkip", "guard watch ended pkg=$pkg")
    }

    @Synchronized
    fun reset() {
        originPkg = null
        originAt = 0L
        originCold = false
        originSettled = false
        originWatched = false
        // lastBackAt **不**清零：回一次桌面不该把「防连按」的冷却也一起清掉，
        // 否则用户回桌面再被广告弹一次，返回键就会连着按下去。
        lastRealPkg = null
        restoringPkg = null
        restoringAt = 0L
    }

    private fun isStore(pkg: String): Boolean = AppLists.isStore(pkg)

    private fun isInUserBlocklist(pkg: String): Boolean =
        blockedList().any { pkg == it || pkg.startsWith("$it.") }

    private fun blockedList(): List<String> {
        val raw = Prefs.guardBlocklist
        if (raw != cachedRaw) {
            cachedRaw = raw
            cachedList = raw
                .split('\n', ',', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return cachedList
    }

    private fun launcherOf(ctx: Context): String? {
        launcherPkg?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching { ctx.packageManager.resolveActivity(intent, 0) }.getOrNull()
        launcherPkg = resolved?.activityInfo?.packageName
        return launcherPkg
    }
}
