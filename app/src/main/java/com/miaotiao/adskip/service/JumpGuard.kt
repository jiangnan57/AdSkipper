package com.miaotiao.adskip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.miaotiao.adskip.data.AppLists
import com.miaotiao.adskip.data.Prefs
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
 *      那是用户自己点的，不该被弹回来。这些来源直接不参与防跳转。
 *   2. 浏览器作为目的地只在「广告高发的来源」里才拦（短视频、小说、资讯类）。
 *      从浏览器跳浏览器、从邮件跳浏览器属于正常上网，一律放过。
 *   3. 拦截窗口从 4 秒收紧到 2.5 秒，冷却从 1.5 秒放宽到 3 秒，
 *      避免连按返回把用户自己的页面也退掉。
 *   4. 新增「广告落地页」一类目的地（电商 / 内容 App）。这是为了
 *      WakeUp 课程表那类穿山甲「摇一摇」开屏广告：摇一下直接拉起淘宝/商店，
 *      传感器事件无障碍根本看不见，只能等落地后按返回。
 *      为了不误伤「用户自己点商品卡」，这类只拦「来源 App 刚打开就跳走」的情形
 *      （originCold，见下面的注释）。
 */
object JumpGuard {

    /** 只有在这个时间窗内发生的跳转才认为是广告触发 */
    private const val GUARD_WINDOW_MS = 2_500L

    /** 浏览器类目的地要求更短：正常点链接也常在这个时间内 */
    private const val BROWSER_WINDOW_MS = 1_800L

    /** 应用商店：从 App 里点「检查更新」也常见，给 5 秒 */
    private const val STORE_WINDOW_MS = 5_000L

    /**
     * 广告落地页（电商 / 内容 App）的窗口。
     *
     * 给到 8 秒是因为摇一摇广告的真实时序比想象的长：
     * 冷启动 → 约 2.8 秒广告才渲染出来 → 用户摇手机 1~2 秒 → 落地页冷启动 1~2 秒，
     * 落到 App 时距离开源 App 前台已经 5 秒上下。窗口设 2.5 秒会全部漏掉。
     * 误伤由 originCold 兜住（只拦「刚打开 App 就被弹走」）。
     */
    private const val LANDING_WINDOW_MS = 8_000L

    /** 两次返回之间的最小间隔，避免连点 */
    private const val BACK_COOLDOWN_MS = 3_000L

    private var originPkg: String? = null
    private var originAt = 0L
    private var lastBackAt = 0L
    private var launcherPkg: String? = null

    /**
     * 来源 App 是否已经显示出「正常内容页」（见 noteContentPage）。
     *
     * 这是把「摇一摇广告」和「用户自己点商品卡」分开的唯一可靠依据。
     * 光看时间分不开：实测 WakeUp 冷启动的启动页能停 4 秒，摇一摇落到淘宝时
     * dt 普遍在 5 秒上下，而「用了几秒后自己点进淘宝」同样落在 5 秒附近。
     */
    private var originContent = false

    /**
     * 当前来源是不是「刚从桌面/后台冷启动进来的」。
     *
     * 摇一摇广告只可能发生在这个阶段：用户点图标打开 App → 开屏广告渲染 →
     * 手一晃 → 直接拉起电商。此时来源 App 的前台时间通常不到 3 秒。
     * 反过来，用户用了十分钟再点商品卡跳淘宝，originCold 早就是 false 了，
     * 不会被误返回。
     */
    private var originCold = false

    /** 拦截名单缓存，避免每次事件都重新解析字符串 */
    private var cachedRaw: String? = null
    private var cachedList: List<String> = emptyList()

    /** 应用商店：正常用户不会在冷启动 2 秒内自己点进商店，误触概率极高 */
    private val stores = listOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.wandoujia.phoenix2",
        "com.miui.packageinstaller"
    )

    fun onForeground(pkg: String, service: SkipAccessibilityService) {
        if (!Prefs.guardJump) return
        val now = SystemClock.uptimeMillis()

        // 回到桌面 = 清空上下文，避免从桌面打开商店被误拦
        if (pkg == launcherOf(service)) {
            reset()
            return
        }
        if (pkg == service.packageName || pkg == "com.android.systemui") return

        if (isBlocked(pkg)) {
            val origin = originPkg
            val isStore = isStore(pkg)
            val isBrowser = AppLists.isBrowser(pkg)
            val isLanding = AppLists.isAdLanding(pkg)

            // 目的地不同，允许的「起疑窗口」也不同（见上面三个常量的注释）
            val window = when {
                isBrowser -> BROWSER_WINDOW_MS
                isStore -> STORE_WINDOW_MS
                isLanding -> LANDING_WINDOW_MS
                else -> GUARD_WINDOW_MS
            }
            val dt = if (origin != null) now - originAt else -1L
            val inWindow = origin != null && dt <= window
            val originOk = origin != null &&
                origin !in AppLists.NO_GUARD_ORIGIN &&
                !AppLists.isBrowser(origin) &&
                when {
                    // 商店：任何来源都拦，正常人不会在 2 秒内主动去商店
                    isStore -> true
                    // 广告落地页：必须是「来源 App 刚打开就被弹走」，
                    // 且屏幕上从来没出现过正常内容页（内容页一出现就说明
                    // 开屏广告已经结束，后面的跳转是用户自己点的）
                    isLanding -> originCold && !originContent
                    // 浏览器 / 用户自定义名单：沿用「广告高发来源」判定
                    else -> origin in AppLists.AD_PRONE
                }
            // 只在看着像广告跳转时打一行，方便用户自己连 adb 排查「为什么没弹回来」
            android.util.Log.d(
                "AdSkip",
                "guard pkg=$pkg store=$isStore browser=$isBrowser landing=$isLanding " +
                    "origin=$origin dt=${dt}ms cold=$originCold content=$originContent " +
                    "window=$window inWindow=$inWindow originOk=$originOk"
            )

            if (inWindow && originOk && now - lastBackAt >= BACK_COOLDOWN_MS) {
                lastBackAt = now
                // 不要在这里直接 performGlobalAction：落地页此刻多半还在冷启动，
                // 返回键会被它的欢迎页吃掉（淘宝实测连按两次都没反应）。
                // 交给 service 做三级兜底：返回 → 拉起原 App → 回桌面。
                service.backOffLanding(pkg, origin)
                SkipLog.add("拦截跳转 ${pkg.substringAfterLast('.')}，已返回")
                // 一次拦截只对应一次跳转。不清掉来源的话，用户被弹回来之后
                // originPkg 还停在原地，下一次摇一摇会算成「非冷启动」而漏拦。
                // lastBackAt 要保留，那是防止连按返回的冷却。
                originPkg = null
                originAt = 0L
                originCold = false
                originContent = false
            }
            return
        }

        // 微信 / QQ / 浏览器 / 支付类：这是用户自己在浏览，不能当成「上一步」
        if (pkg in AppLists.NO_GUARD_ORIGIN || AppLists.isBrowser(pkg)) {
            reset()
            return
        }

        // 同一个 App 内部切 Activity 不算「换了来源」。
        //
        // 这一条是实测踩出来的：WakeUp 课程表冷启动会连发两条窗口事件
        // （SplashActivity → ScheduleActivity）。如果每次都重算，
        // 第一条把 originCold 置 true，第二条立刻覆盖成 false，
        // 于是摇一摇跳转永远拦不到——日志里 origin 是对的，就是 cold=false。
        //
        // 只有真的换了 App 才更新来源和计时；同一 App 内保持「进入时刻」不变，
        // 这样 dt 才是「从这个 App 冷启动到现在过了多久」。
        if (pkg != originPkg) {
            originCold = originPkg == null
            originPkg = pkg
            originAt = now
            originContent = false
        }
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
    fun noteContentPage(pkg: String) {
        if (pkg != originPkg) return
        if (originContent) return
        originContent = true
        android.util.Log.d("AdSkip", "guard note content page pkg=$pkg")
    }

    fun reset() {
        originPkg = null
        originAt = 0L
        originCold = false
        originContent = false
        lastBackAt = 0L
    }

    private fun isStore(pkg: String): Boolean = stores.any { pkg == it || pkg.startsWith("$it.") }

    /**
     * 是不是「需要判定要不要返回」的目的地。
     *
     * 注意这里必须包含广告落地页（AD_LANDING）：只在 originOk 的分支里加
     * isLanding 是没用的——那一步根本进不来，等于规则写了但不生效。
     * 这是实测踩到的：test_guard.sh 的场景 A/B 一开始全红。
     */
    private fun isBlocked(pkg: String): Boolean =
        isStore(pkg) || AppLists.isBrowser(pkg) || AppLists.isAdLanding(pkg) ||
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
