package com.miaotiao.adskip.util

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.miaotiao.adskip.service.SkipAccessibilityService

/**
 * 无障碍的三种状态。
 *
 * 「开着」和「连上了」是两件事，这一点在小米系上是**最常见的故障源**：
 * 覆盖安装、清理后台、省电策略之后，`Settings.Secure` 里的服务名还在，
 * 但进程早被杀掉了——系统照样显示「已开启」。
 *
 * 之前 `isServiceEnabled()` 把这两种混成同一个 true，于是：
 * · 用户看到绿色的「服务运行中」，以为没问题，只能来问「为什么一个广告都不跳」；
 * · 开发者拿到截图和导出记录，也看不出任何异常，只能隔空猜。
 *
 * 现在拆开。中间态 [AUTHORIZED_NOT_CONNECTED] 是唯一能解释
 * 「权限明明给了却没用」的状态，它的处理办法很明确：关掉再打开一次。
 */
enum class AccessState {
    /** 服务实例还活着：事件真的会送到我们这里 */
    CONNECTED,

    /** 系统授权表里还有名字，但服务实例已经没了：假开 */
    AUTHORIZED_NOT_CONNECTED,

    /** 授权表里也没有 */
    OFF
}

object AccessUtil {

    /**
     * 判定当前状态。
     *
     * 顺序很重要：先看实例。实例存在就是 CONNECTED，不需要再查 Settings
     * （某些 ROM 在权限收紧时读 Secure 表会抛 SecurityException，
     * 而实例本身就是最强证据）。
     */
    fun state(ctx: Context): AccessState {
        if (SkipAccessibilityService.instance != null) return AccessState.CONNECTED

        val enabled = runCatching {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.getOrDefault(0)
        if (enabled != 1) return AccessState.OFF

        // 少数 ROM 会在权限收紧时对 Secure 表抛 SecurityException，
        // 这里不能让「查状态」把首页或保活自检带崩。
        val services = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return AccessState.OFF

        val target = SkipAccessibilityService::class.java.name
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(services)
        for (item in splitter) {
            if (item.contains(ctx.packageName) && item.contains(target)) {
                // 名字在，实例不在 → 假开
                return AccessState.AUTHORIZED_NOT_CONNECTED
            }
        }
        return AccessState.OFF
    }

    /**
     * 兼容旧语义：只要「系统认为开过」就返回 true。
     *
     * 保活自检、按钮跳转这类场景只关心「要不要引导用户去设置」，
     * 不需要区分中间态。要判断「现在到底能不能干活」，用 [state]。
     */
    fun isServiceEnabled(ctx: Context): Boolean = state(ctx) != AccessState.OFF

    fun canDrawOverlay(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
}
