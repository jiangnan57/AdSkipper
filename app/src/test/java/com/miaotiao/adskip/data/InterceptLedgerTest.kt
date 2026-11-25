package com.miaotiao.adskip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 拦截账本的回归测试。
 *
 * 它只回答一个问题：「刚拦完，用户是不是又自己点回去了？」
 * 这条信号将来要拿来定「误拦率」和熔断阈值，所以两件事必须钉死：
 *
 *   1. 时间窗是 10 秒，多一秒都不算；
 *   2. **自家 relaunch 不算回访**——三级兜底第 2 级会把来源 App 拉回前台，
 *      那是拦截成功的表现，算成回访会把误拦率高估到没法看。
 */
class InterceptLedgerTest {

    private val taobao = "com.taobao.taobao"
    private val tomato = "com.dragon.read"

    @Before
    fun clear() {
        InterceptLedger.clear()
    }

    @Test
    fun 十秒内回访能命中() {
        InterceptLedger.record(taobao, tomato, 1_000L)
        val hit = InterceptLedger.noteForeground(taobao, 4_000L, selfRelaunch = false)
        assertNotNull("3 秒后回到同一个 App，就是回访", hit)
        assertEquals(taobao, hit!!.dest)
        assertEquals(tomato, hit.origin)
    }

    @Test
    fun 超过十秒不算回访() {
        InterceptLedger.record(taobao, tomato, 1_000L)
        assertNull(
            "11 秒后才回去，更可能是用户过一会儿想用，不能算拦错",
            InterceptLedger.noteForeground(taobao, 12_000L, selfRelaunch = false)
        )
    }

    @Test
    fun 自家拉回前台不算回访() {
        InterceptLedger.record(taobao, tomato, 1_000L)
        assertNull(
            "三级兜底把来源 App 拉回来是拦截成功，不是用户回访",
            InterceptLedger.noteForeground(taobao, 1_200L, selfRelaunch = true)
        )
        // 而且不能把记录消费掉：用户之后真的点回来，还要能认出来
        assertNotNull(
            InterceptLedger.noteForeground(taobao, 2_000L, selfRelaunch = false)
        )
    }

    @Test
    fun 回到别的地方不算回访() {
        InterceptLedger.record(taobao, tomato, 1_000L)
        assertNull(InterceptLedger.noteForeground("com.jingdong.app.mall", 2_000L, false))
    }

    @Test
    fun 同一次回访只上报一次() {
        InterceptLedger.record(taobao, tomato, 1_000L)
        assertNotNull(InterceptLedger.noteForeground(taobao, 2_000L, false))
        assertNull(
            "命中后就该消费掉，否则同一个 App 每来一次窗口事件都要刷一行日志",
            InterceptLedger.noteForeground(taobao, 3_000L, false)
        )
    }

    @Test
    fun 只保留最近五条() {
        repeat(7) { i ->
            InterceptLedger.record("com.example.app$i", tomato, 1_000L + i)
        }
        assertEquals(5, InterceptLedger.size())
        assertNull("最早的两条应该被挤掉", InterceptLedger.noteForeground("com.example.app0", 2_000L, false))
        assertNull(InterceptLedger.noteForeground("com.example.app1", 2_000L, false))
        assertNotNull(InterceptLedger.noteForeground("com.example.app6", 2_000L, false))
    }
}
