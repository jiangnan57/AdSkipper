package com.miaotiao.adskip.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测 [LogExport.composeCompact] 这个纯函数。
 *
 * 它要守住的一件事：**截断必须写出来**。
 * 反馈里带的只是最近 30 条，如果拼装时把「共 200 条」或「只带最近 30 条」
 * 漏掉，我拿到手会以为对方一共就跳了 30 次——这是会直接改错结论的那种错。
 *
 * 采集那半边要 Activity，跑不了纯 JVM 单测，不在这里。
 */
class LogExportTest {

    private val head = "【秒跳诊断】v1.9.9 (99)\n机型   Xiaomi 24122RKC7C"

    private fun lines(n: Int): List<String> =
        (1..n).map { "11-28 20:4$it 跳过 示例应用 · 规则" }

    @Test
    fun `截断时同时写出总条数和实际条数`() {
        val t = LogExport.composeCompact(head, lines(200), 30)

        assertTrue(t.contains("共 200 条"))
        assertTrue(t.contains("这里只带最近 30 条"))
        // 30 条记录 + 头部若干行
        assertTrue(t.lineSequence().count { it.contains("跳过 示例应用") } == 30)
    }

    @Test
    fun `没到上限时不提截断`() {
        val t = LogExport.composeCompact(head, lines(12), 30)

        assertTrue(t.contains("共 12 条"))
        assertFalse(t.contains("只带最近"))
        assertTrue(t.lineSequence().count { it.contains("跳过 示例应用") } == 12)
    }

    /**
     * 一条记录都没有本身是最重要的结论（服务从没连上过）：
     * 不能拼出一段空荡荡的文本让对方以为「导出坏了」。
     */
    @Test
    fun `没有记录时明写一条都没有`() {
        val t = LogExport.composeCompact(head, emptyList(), 30)

        assertTrue(t.contains("共 0 条"))
        assertTrue(t.contains("一条记录都没有"))
    }

    @Test
    fun `头部原样保留在最前面`() {
        val t = LogExport.composeCompact(head, lines(3), 30)

        assertTrue(t.startsWith(head))
    }
}
