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
 * 只能在前台包名已经变化之后补救——检测到跳去了应用商店/浏览器，就立刻按一次返回。
 * 所以真实表现是「闪一下再弹回来」，做不到零延迟拦截。
 *
 * 这一版重点解决「乱返回」：
 *
 *   1. 微信 / QQ / 支付宝里点小程序、点分享链接，本来就会跳到浏览器或商店，
 *      那是用户自己点的，不该被弹回来。这些来源直接不参与防跳转。
 *   2. 浏览器作为目的地只在「广告高发的来源」里才拦（短视频、小说、资讯类）。
 *      从浏览器跳浏览器、从邮件跳浏览器属于正常上网，一律放过。
 *   3. 拦截窗口从 4 秒收紧到 2.5 秒，冷却从 1.5 秒放宽到 3 秒，
 *      避免连按返回把用户自己的页面也退掉。
 */
object JumpGuard {

    /** 只有在这个时间窗内发生的跳转才认为是广告触发 */
    private const val GUARD_WINDOW_MS = 2_500L

    /** 浏览器类目的地要求更短：正常点链接也常在这个时间内 */
    private const val BROWSER_WINDOW_MS = 1_800L

    /** 两次返回之间的最小间隔，避免连点 */
    private const val BACK_COOLDOWN_MS = 3_000L

    private var originPkg: String? = null
    private var originAt = 0L
    private var lastBackAt = 0L
    private var launcherPkg: String? = null

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
        "com.wandoujia.phoenix2"
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

            // 浏览器目的地：只有广告高发来源在过去 1.8 秒内的跳转才算误触
            val window = if (isBrowser) BROWSER_WINDOW_MS else GUARD_WINDOW_MS
            val inWindow = origin != null && now - originAt <= window
            val originOk = origin != null &&
                origin !in AppLists.NO_GUARD_ORIGIN &&
                !AppLists.isBrowser(origin) &&
                (isStore || origin in AppLists.AD_PRONE)

            if (inWindow && originOk && now - lastBackAt >= BACK_COOLDOWN_MS) {
                lastBackAt = now
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                SkipLog.add("拦截跳转 ${pkg.substringAfterLast('.')}，已返回")
            }
            return
        }

        // 微信 / QQ / 浏览器 / 支付类：这是用户自己在浏览，不能当成「上一步」
        if (pkg in AppLists.NO_GUARD_ORIGIN || AppLists.isBrowser(pkg)) {
            reset()
            return
        }

        originPkg = pkg
        originAt = now
    }

    fun reset() {
        originPkg = null
        originAt = 0L
        lastBackAt = 0L
    }

    private fun isStore(pkg: String): Boolean = stores.any { pkg == it || pkg.startsWith("$it.") }

    private fun isBlocked(pkg: String): Boolean =
        isStore(pkg) || AppLists.isBrowser(pkg) || blockedList().any { pkg == it || pkg.startsWith("$it.") }

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
