package com.miaotiao.adskip.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断头的排版测试。
 *
 * 只测 [Diagnostics.render]：采集那半边要 Context / Build，跑不了纯 JVM 单测；
 * 而真正容易出错的恰恰是「某个值拿不到时显示成什么」——
 * 一个诊断工具把「读不到」伪装成「没问题」，比没有诊断更坏。
 */
class DiagnosticsTest {

    private fun info(
        access: String = "已连接",
        notify: Diagnostics.Tri = Diagnostics.Tri.YES,
        overlay: Diagnostics.Tri = Diagnostics.Tri.YES,
        battery: Diagnostics.Tri = Diagnostics.Tri.YES,
        ghost: Diagnostics.Tri = Diagnostics.Tri.YES,
        master: Boolean = true,
        skipSplash: Boolean = true,
        popups: Boolean = true,
        guard: Boolean = true,
        ruleApps: Int? = 128,
        lastConnect: String? = "11-25 21:10:32",
        lastRuleLoad: String? = "128 个应用（远程库 0 条 / 学习 12）",
        todayCount: Int = 3
    ) = Diagnostics.Info(
        versionName = "1.7.1",
        versionCode = 22,
        time = "2026-11-28 20:35",
        device = "Xiaomi 24122RKC7C",
        system = "Android 17 (API 37)",
        rom = "MIUI/HyperOS V816",
        access = access,
        notify = notify,
        overlay = overlay,
        battery = battery,
        ghost = ghost,
        master = master,
        skipSplash = skipSplash,
        popups = popups,
        guard = guard,
        ruleApps = ruleApps,
        lastConnect = lastConnect,
        lastRuleLoad = lastRuleLoad,
        todayCount = todayCount
    )

    /**
     * 取以 key 开头的那一行，去掉 key 之后返回。
     *
     * 不直接断言整行字符串：标签后面的空格数量是排版细节，
     * 测试不该因为它改一次就红一片。
     */
    private fun value(t: String, key: String): String {
        val row = t.lineSequence().firstOrNull { it.startsWith(key) }
            ?: throw AssertionError("没有「$key」这一行，实际输出：\n$t")
        return row.removePrefix(key).trim()
    }

    @Test
    fun 头部带版本号和版本代码() {
        assertTrue(Diagnostics.render(info()).startsWith("【秒跳诊断】v1.7.1 (22)"))
    }

    @Test
    fun 假开的措辞不能是未开启() {
        // 「未开启」会让用户去系统里看一眼「明明是开的」然后认为 App 坏了，
        // 中间态必须明说「授权还在，服务没连上」
        val t = Diagnostics.render(info(access = "授权还在，服务没连上"))
        assertEquals("授权还在，服务没连上", value(t, "无障碍"))
        assertFalse(t.contains("未开启"))
    }

    @Test
    fun 读不到不能写成没有() {
        val t = Diagnostics.render(
            info(
                notify = Diagnostics.Tri.UNKNOWN,
                overlay = Diagnostics.Tri.UNKNOWN,
                battery = Diagnostics.Tri.UNKNOWN
            )
        )
        // 三项都必须写「读不到」，不能退化成「没有」这种断言性结论
        assertEquals("读不到", value(t, "通知"))
        assertEquals("读不到", value(t, "悬浮窗"))
        assertEquals("读不到", value(t, "电池"))
        assertFalse(t.contains("没加白名单"))
    }

    @Test
    fun 悬浮窗缺失要报没有() {
        val t = Diagnostics.render(info(overlay = Diagnostics.Tri.NO))
        assertEquals("没有", value(t, "悬浮窗"))
    }

    @Test
    fun 电池已加白名单与没加的措辞要区分() {
        val yes = Diagnostics.render(info(battery = Diagnostics.Tri.YES))
        val no = Diagnostics.render(info(battery = Diagnostics.Tri.NO))
        assertEquals("已加白名单", value(yes, "电池"))
        assertEquals("没加白名单", value(no, "电池"))
    }

    @Test
    fun 微窗在服务没连上时写读不到() {
        val t = Diagnostics.render(info(ghost = Diagnostics.Tri.UNKNOWN))
        assertEquals("读不到（服务未连接）", value(t, "微窗"))
    }

    @Test
    fun 没有任何连接记录时要说没有记录而不是留空() {
        val t = Diagnostics.render(info(lastConnect = null, lastRuleLoad = null))
        assertEquals("上次连上 没有记录 · 上次加载 没有记录", value(t, "服务"))
    }

    @Test
    fun 规则读不到时不能显示成零个应用() {
        val t = Diagnostics.render(info(ruleApps = null))
        assertEquals("读不到", value(t, "规则"))
        assertFalse(t.contains("磁盘 0 个"))
    }

    @Test
    fun 开关按总_开屏_弹窗_防跳转的顺序输出() {
        val t = Diagnostics.render(info(master = true, skipSplash = false, popups = true, guard = false))
        assertEquals("总开 / 开屏关 / 弹窗开 / 防跳转关", value(t, "开关"))
    }

    @Test
    fun 机型与ROM为空时要有兜底() {
        val t = Diagnostics.render(info().copy(device = "未知", rom = "未知"))
        assertEquals("未知", value(t, "机型"))
        assertEquals("未知", value(t, "ROM"))
    }
}
