package com.miaotiao.adskip.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.miaotiao.adskip.BuildConfig
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.data.RuleRepository
import com.miaotiao.adskip.service.SkipAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断头部：把「对方的现场」压成十几行文本。
 *
 * 为什么要它：用户反馈「装了没用」时，能被远程看到的信息只有一张首页截图，
 * 而截图里没有机型、ROM、三项权限、微窗状态、规则条数——每缺一项，
 * 排查就多一轮「你再看一下 / 还是不行 / 那我再想想」。
 *
 * 三条设计原则：
 *
 * 1. **只报读得到的事实，读不到就写「读不到」。**
 *    诊断工具最容易骗人的地方就是给个默认值：`getOrDefault(true)` 会把
 *    「查询抛异常」伪装成「一切正常」，于是拿着诊断结果继续猜。
 * 2. **不联网、不写文件、不要新权限。** 拼好交给系统分享，发到哪由用户决定。
 * 3. **排版是纯函数**（[render]），采集（[collect]）只负责取值，
 *    这样单测能覆盖「读不到该怎么显示」，不用起 Android 环境。
 */
object Diagnostics {

    /** 三态：有 / 没有 / 读不到。布尔值表达不了「查询失败」 */
    enum class Tri { YES, NO, UNKNOWN }

    data class Info(
        val versionName: String,
        val versionCode: Int,
        val time: String,
        val device: String,
        val system: String,
        val rom: String,
        val access: String,
        val notify: Tri,
        val overlay: Tri,
        val battery: Tri,
        val ghost: Tri,
        val master: Boolean,
        val skipSplash: Boolean,
        val popups: Boolean,
        val guard: Boolean,
        val ruleApps: Int?,
        val lastConnect: String?,
        val lastRuleLoad: String?,
        val todayCount: Int
    )

    private const val TAG_CONNECTED = "无障碍服务已连接"
    private const val TAG_RULES = "规则已加载"

    /** 完整诊断文本（头部，不含运行记录） */
    fun build(ctx: Context): String = render(collect(ctx))

    // ------------------------------------------------------------------ 采集

    fun collect(ctx: Context): Info {
        val lines = SkipLog.snapshot()

        // 日志是「新 → 旧」，第一条命中就是最近一次
        val lastConnect = lines.firstOrNull { it.contains(TAG_CONNECTED) }?.take(14)
        val lastRuleLoad = lines.firstOrNull { it.contains(TAG_RULES) }
            ?.substringAfter(TAG_RULES)
            // 原文是「规则已加载：50 个应用…」，截断后自带一个冒号，去掉
            ?.trimStart('：', ':', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val today = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())
        val todayCount = lines.count {
            it.length > 5 && it.startsWith(today) &&
                !it.contains(TAG_RULES) && !it.contains(TAG_CONNECTED)
        }

        val svc = SkipAccessibilityService.instance
        val ghost = when {
            svc == null -> Tri.UNKNOWN          // 服务没连，问不到
            svc.ghostAlive -> Tri.YES
            else -> Tri.NO
        }

        return Info(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            device = deviceName(),
            system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            rom = romName(),
            access = accessText(AccessUtil.state(ctx)),
            notify = safe { NotificationManagerCompat.from(ctx).areNotificationsEnabled() },
            overlay = safe { Settings.canDrawOverlays(ctx) },
            battery = safe {
                val pm = ctx.getSystemService(PowerManager::class.java)
                    ?: error("no power manager")
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            },
            ghost = ghost,
            master = Prefs.masterEnabled,
            skipSplash = Prefs.skipSplash,
            popups = Prefs.closePopups,
            guard = Prefs.guardJump,
            // 磁盘上的规则条数：代表「这次打开时能加载到什么」，
            // 和服务上次上报的条数不一致，往往说明规则文件坏了或被回滚了
            ruleApps = runCatching { RuleRepository.load(ctx).apps.size }.getOrNull(),
            lastConnect = lastConnect,
            lastRuleLoad = lastRuleLoad,
            todayCount = todayCount
        )
    }

    private fun accessText(s: AccessState): String = when (s) {
        AccessState.CONNECTED -> "已连接"
        AccessState.AUTHORIZED_NOT_CONNECTED -> "授权还在，服务没连上"
        AccessState.OFF -> "未开启"
    }

    /** 查询失败不能被当成「正常」，一律落到 UNKNOWN */
    private fun safe(block: () -> Boolean): Tri =
        runCatching { if (block()) Tri.YES else Tri.NO }.getOrDefault(Tri.UNKNOWN)

    private fun deviceName(): String {
        val brand = Build.MANUFACTURER.orEmpty()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return listOf(brand, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "未知" }
    }

    /**
     * ROM 名。小米上 `ro.miui.ui.version.name` 是 "V816" 这种，
     * 能直接分辨「是不是 HyperOS」；拿不到就退回 `Build.DISPLAY`。
     * 两者都拿不到就写「未知」——**不靠机型猜**。
     */
    private fun romName(): String {
        val miui = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            (get.invoke(null, "ro.miui.ui.version.name") as? String).orEmpty()
        }.getOrDefault("")

        val parts = ArrayList<String>(2)
        if (miui.isNotBlank()) parts += "MIUI/HyperOS $miui"
        Build.DISPLAY?.takeIf { it.isNotBlank() && it != "unknown" }?.let { parts += it }
        return if (parts.isEmpty()) "未知" else parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ 排版

    fun render(i: Info): String = buildString {
        append("【秒跳诊断】v").append(i.versionName)
            .append(" (").append(i.versionCode).append(")\n")
        append("时间   ").append(i.time).append('\n')
        append("机型   ").append(i.device).append('\n')
        append("系统   ").append(i.system).append('\n')
        append("ROM    ").append(i.rom).append('\n')
        append("无障碍  ").append(i.access).append('\n')
        append("通知   ").append(tri(i.notify, "有", "没有")).append('\n')
        append("悬浮窗  ").append(tri(i.overlay, "有", "没有")).append('\n')
        append("电池   ").append(tri(i.battery, "已加白名单", "没加白名单")).append('\n')
        append("微窗   ").append(
            when (i.ghost) {
                Tri.YES -> "已挂上"
                Tri.NO -> "没挂上"
                Tri.UNKNOWN -> "读不到（服务未连接）"
            }
        ).append('\n')
        append("开关   总").append(b(i.master))
            .append(" / 开屏").append(b(i.skipSplash))
            .append(" / 弹窗").append(b(i.popups))
            .append(" / 防跳转").append(b(i.guard)).append('\n')
        append("规则   ").append(
            i.ruleApps?.let { "磁盘 $it 个应用" } ?: "读不到"
        ).append('\n')
        append("服务   ").append(
            buildString {
                append("上次连上 ").append(i.lastConnect ?: "没有记录")
                append(" · 上次加载 ").append(i.lastRuleLoad ?: "没有记录")
            }
        ).append('\n')
        append("今天   ").append(i.todayCount).append(" 条\n")
    }

    private fun tri(t: Tri, yes: String, no: String): String = when (t) {
        Tri.YES -> yes
        Tri.NO -> no
        Tri.UNKNOWN -> "读不到"
    }

    private fun b(v: Boolean) = if (v) "开" else "关"
}
