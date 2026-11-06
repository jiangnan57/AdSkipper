package com.miaotiao.adskip.util

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.miaotiao.adskip.service.SkipAccessibilityService

object AccessUtil {

    /** 判断无障碍服务是否已启用 */
    fun isServiceEnabled(ctx: Context): Boolean {
        SkipAccessibilityService.instance?.let { return true }

        val enabled = runCatching {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.getOrDefault(0)
        if (enabled != 1) return false

        // 少数 ROM 会在权限收紧时对 Secure 表抛 SecurityException，
        // 这里不能让「查状态」把首页或保活自检带崩。
        val services = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false

        val target = SkipAccessibilityService::class.java.name
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(services)
        for (item in splitter) {
            if (item.contains(ctx.packageName) && item.contains(target)) return true
        }
        return false
    }

    fun canDrawOverlay(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
}
