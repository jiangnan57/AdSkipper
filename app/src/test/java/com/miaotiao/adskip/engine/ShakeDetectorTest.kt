package com.miaotiao.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇一摇检测（v1.5 · R12）。
 *
 * 这一层如果判错，代价是「用户正在看的页面被点掉」——比漏拦严重。
 * 所以判据不是「加速度大」，而是「冲击 + 反方向回弹」这个只有人手
 * 甩动才有的形状。下面每条都是这个形状的边界。
 */
class ShakeDetectorTest {

    private val g = ShakeDetector.GRAVITY

    @Test
    fun 静止不出触发() {
        val d = ShakeDetector()
        var t = 0L
        repeat(200) {
            assertFalse(d.feed(0f, 0f, g, t))
            t += 20
        }
    }

    @Test
    fun 单次冲击没有回弹不算摇() {
        val d = ShakeDetector()
        assertFalse("先来一下冲击", d.feed(25f, 0f, g, 0L))
        // 之后回到静止，没有反向峰
        var t = 40L
        repeat(30) {
            assertFalse(d.feed(0f, 0f, g, t))
            t += 20
        }
    }

    @Test
    fun 同方向的第二次冲击不算摇() {
        val d = ShakeDetector()
        assertFalse(d.feed(25f, 0f, g, 0L))
        assertFalse("同方向只是被甩了两次，手腕没有收回来", d.feed(25f, 0f, g, 500L))
    }

    @Test
    fun 冲击加反向回弹才算摇() {
        val d = ShakeDetector()
        assertFalse(d.feed(25f, 0f, g, 0L))
        assertTrue("一甩一收，这才是摇", d.feed(-15f, 0f, g, 500L))
    }

    @Test
    fun 回弹太晚不算() {
        val d = ShakeDetector()
        d.feed(25f, 0f, g, 0L)
        assertFalse("1.5 秒后才回弹，中间早就不是同一次动作了", d.feed(-15f, 0f, g, 1_500L))
    }

    @Test
    fun 回弹太早不算() {
        val d = ShakeDetector()
        d.feed(25f, 0f, g, 0L)
        assertFalse("100ms 内反向，是同一个振动周期，不是摇", d.feed(-15f, 0f, g, 100L))
    }

    @Test
    fun 触发后有冷却() {
        val d = ShakeDetector()
        d.feed(25f, 0f, g, 0L)
        assertTrue(d.feed(-15f, 0f, g, 500L))
        // 冷却期内同样的动作不再触发
        assertFalse(d.feed(25f, 0f, g, 600L))
        assertFalse(d.feed(-15f, 0f, g, 700L))
    }

    @Test
    fun 竖直方向也能识别() {
        val d = ShakeDetector()
        assertFalse(d.feed(0f, 0f, 25f, 0L))
        assertTrue("上下甩也是摇", d.feed(0f, 0f, -2f, 500L))
    }
}
