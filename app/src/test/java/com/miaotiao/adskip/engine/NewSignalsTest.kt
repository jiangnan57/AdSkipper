package com.miaotiao.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 新增的三个纯逻辑部件。
 *
 * 它们都不 import android.*，所以能在这里跑；也正因为能在 JVM 上跑，
 * 改完之后不用先装一次手机才知道有没有把判定改坏。
 */
class NewSignalsTest {

    // ---------------------------------------------------------------- R5 指纹

    @Test
    fun 裸子串不能命中() {
        // 报告原文给的指纹表里有 `ad_` 这种裸子串，会命中 download_ / loading_ /
        // badge_ —— 几乎每个 App 都有。这些误命中会让「有广告」这个信号
        // 变成「永远为真」，等于没有。
        assertFalse(AdFingerprint.hit("com.foo:id/download_btn"))
        assertFalse(AdFingerprint.hit("com.foo:id/loading_view"))
        assertFalse(AdFingerprint.hit("com.foo:id/badge_count"))
        assertFalse(AdFingerprint.hit(null))
        assertFalse(AdFingerprint.hit(""))
    }

    @Test
    fun 已知广告控件id命中() {
        assertTrue(AdFingerprint.hit("com.foo:id/tt_splash_skip"))
        assertTrue(AdFingerprint.hit("com.foo:id/ksad_skip_view"))
        assertTrue(AdFingerprint.hit("com.foo:id/gdt_btn"))
        assertTrue(AdFingerprint.hit("com.foo:id/pangle_close"))
    }

    @Test
    fun 广告SDK类名命中() {
        assertTrue(AdFingerprint.hit("com.bytedance.sdk.openadsdk.core.AdView"))
        assertTrue(AdFingerprint.hit("com.qq.e.comm.managers.GDTAdSdk"))
        assertTrue(AdFingerprint.hit("com.kwad.sdk.widget.KSFrameLayout"))
    }

    // ---------------------------------------------------------------- R10 配对

    @Test
    fun 第一次撤销不熔断第二次才熔断() {
        val l = PairLedger()
        val t0 = 1_000L
        assertFalse("只撤一次说明不了什么", l.noteUndo("a.origin", "b.dest", t0))
        assertFalse(l.isMuted("a.origin", "b.dest", t0 + 1_000L))
        assertTrue("同一对第二次撤销", l.noteUndo("a.origin", "b.dest", t0 + 2_000L))
        assertTrue(l.isMuted("a.origin", "b.dest", t0 + 3_000L))
    }

    @Test
    fun 熔断期内再撤销升级成二十四小时() {
        val l = PairLedger()
        val t0 = 1_000L
        l.noteUndo("a", "b", t0)
        l.noteUndo("a", "b", t0 + 1_000L)
        assertTrue("刚熔断", l.noteUndo("a", "b", t0 + 2_000L))
        val left = l.muteLeftMs("a", "b", t0 + 2_000L)
        assertTrue("这时候该按 24 小时算，实际剩 ${left}ms", left > 20 * 60 * 60_000L)
    }

    @Test
    fun 配对熔断不影响别的来源() {
        val l = PairLedger()
        l.noteUndo("wakeup", "taobao", 1_000L)
        l.noteUndo("wakeup", "taobao", 2_000L)
        assertTrue(l.isMuted("wakeup", "taobao", 3_000L))
        assertFalse("换个来源照样该拦", l.isMuted("douyin", "taobao", 3_000L))
    }

    @Test
    fun 配对账本有上限() {
        val l = PairLedger(maxPairs = 3)
        for (i in 0 until 10) l.noteIntercept("origin$i", "dest", i.toLong())
        assertEquals("上限必须生效，否则长期跑会一直涨", 3, l.size())
    }

    // ---------------------------------------------------------------- R4 输入

    @Test
    fun 全屏点击不算用户输入() {
        InputTrace.clear()
        InputTrace.note("pkg.a", 1_000L, fullscreen = true)
        assertNull("全屏热区上的点击是广告，不是选择", InputTrace.recentUserTap("pkg.a", 1_200L))
    }

    @Test
    fun 时间窗外的点击不算() {
        InputTrace.clear()
        InputTrace.note("pkg.a", 1_000L, fullscreen = false)
        assertNull(InputTrace.recentUserTap("pkg.a", 1_000L + 1_501L))
    }

    @Test
    fun 别的包里的点击不算() {
        InputTrace.clear()
        InputTrace.note("pkg.a", 1_000L, fullscreen = false)
        assertNull(InputTrace.recentUserTap("pkg.b", 1_100L))
    }

    @Test
    fun 正常点击命中() {
        InputTrace.clear()
        InputTrace.note("pkg.a", 1_000L, fullscreen = false)
        val hit = InputTrace.recentUserTap("pkg.a", 1_600L)
        assertTrue(hit != null && hit.at == 1_000L)
        InputTrace.clear()
    }
}
