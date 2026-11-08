package com.miaotiao.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个词能不能点」的回归测试。
 *
 * 这一组测试的存在理由很具体：v1.3.5 用户反馈「番茄小说还是从书页退回书架」，
 * 而日志里的元凶是我们自己点的——
 *
 *     跳过 com.dragon.read · 点击关闭广告并退出小说（规则 关闭广告）
 *
 * 原因：SafeWords 先判「含广告词就放行」，这条文案含「关闭广告」，
 * 于是「退出」这个危险动词被短路掉了。点下去 ReaderActivity 直接 finish。
 *
 * 所以这里用真实的无障碍文案做断言，锁死「否决词优先于放行词」这个顺序。
 */
class SafeWordsTest {

    @Test
    fun 番茄阅读页那条退出文案绝对不许点() {
        // 真实抓到的 contentDescription，原文一字不差
        assertTrue(
            "这条一点就把书页退到书架",
            SafeWords.isUnsafe("点击关闭广告并退出小说")
        )
    }

    @Test
    fun 含退出语义的变体一律否决() {
        val bad = listOf(
            "点击关闭广告并退出小说",
            "关闭广告并退出",
            "关闭广告，返回书架",
            "跳过广告并退出阅读",
            "关闭书籍",
            "跳过广告后返回",
            "退出阅读",
            "返回书架",
            "目录",
            "上一页",
            "下一页",
            "Skip and exit",
            "Close ad and go back"
        )
        for (t in bad) {
            assertTrue("不该点：$t", SafeWords.isUnsafe(t))
        }
    }

    @Test
    fun 真正的跳过按钮必须放行() {
        val ok = listOf(
            "跳过",
            "跳过 5",
            "跳过5s",
            "跳过 5 秒",
            "跳过广告",
            "关闭广告",
            "关闭开屏",
            "Skip",
            "Skip Ad",
            "skip 3s",
            "DISMISS",
            // 允许中间被排版拉开空白
            "跳 过",
            "点击跳过"
        )
        for (t in ok) {
            assertFalse("应该能点：$t", SafeWords.isUnsafe(t))
        }
    }

    @Test
    fun 普通界面控件词仍然拒绝() {
        val bad = listOf("分类", "新对话", "评论", "点赞", "关闭", "取消", "我的")
        for (t in bad) {
            assertTrue("不该点：$t", SafeWords.isUnsafe(t))
        }
    }

    @Test
    fun 空文案不拦() {
        assertFalse(SafeWords.isUnsafe(null))
        assertFalse(SafeWords.isUnsafe(""))
        assertFalse(SafeWords.isUnsafe("   "))
    }

    @Test
    fun 英文广告词必须带词边界() {
        // 独立的 Skip / Dismiss 放行
        assertFalse(SafeWords.isUnsafe("Skip Ad"))
        assertFalse(SafeWords.isUnsafe("Dismiss"))
        // 但 English 里夹带的 exit / back 一样要否决
        assertTrue(SafeWords.isUnsafe("Skip and exit"))
        assertTrue(SafeWords.isUnsafe("close ad, go back"))
    }

    @Test
    fun 学习规则时不许把界面按钮学成跳过词() {
        assertFalse(SafeWords.isLearnable("点击关闭广告并退出小说"))
        assertFalse(SafeWords.isLearnable("目录"))
        assertTrue(SafeWords.isLearnable("跳过 5s"))
    }

    @Test
    fun 只判否决词时不牵连普通关闭键() {
        // 已经确认是关闭控件的节点走 isVetoed：它的文案本来就是「关闭 / ✕」，
        // 不能因为 UI_WORD 里有「关闭」就把真的 × 也挡掉（样本 #5）
        assertFalse(SafeWords.isVetoed("关闭"))
        assertFalse(SafeWords.isVetoed("✕"))
        assertFalse(SafeWords.isVetoed(null))
        // 但含退出语义的必须挡住
        assertTrue(SafeWords.isVetoed("点击关闭广告并退出小说"))
        assertTrue(SafeWords.isVetoed("关闭广告并返回书架"))
    }
}
