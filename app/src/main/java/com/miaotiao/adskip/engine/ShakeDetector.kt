package com.miaotiao.adskip.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「摇一摇」检测（v1.5 的 R12）。
 *
 * ## 为什么需要
 *
 * 摇一摇广告全程没有一次点击，`TYPE_VIEW_CLICKED` 帮不上忙；
 * 现有的传感器方案只用来「提速」，不做前置拦截。结果是它永远只能
 * 在落地之后按一次返回——用户已经看见淘宝了。
 *
 * 检测到摇动 + 屏幕上确实有广告指纹 → 直接点掉广告。抢赢了，
 * 一次跳转都不会发生；抢输了，现有兜底照旧。
 *
 * ## 判据：冲击 + 回弹
 *
 * 单手晃一下手机的加速度曲线有一个很固定的形状：一个方向的尖峰，
 * 紧接着反方向的尖峰（手腕甩出去又收回来）。走路、骑车是**持续**
 * 的小幅振动，没有这种「一正一反」。
 *
 * 所以不是「超过阈值就算」，而是：
 *
 *   1. 先等到一个大于 [hitThreshold] 的偏差；
 *   2. 在 [minGapMs]..[maxGapMs] 之内出现反方向的、大于 [reboundThreshold] 的偏差。
 *
 * 两条都满足才算一次。这个形状骗不过走路，也骗不过掏手机。
 *
 * ## 边界
 *
 * 这个类不许 import android.*，传感器注册在 service 里做，
 * 这样判定能在 JVM 单测里跑。时间由调用方传。
 */
class ShakeDetector(
    private val hitThreshold: Float = 12f,
    private val reboundThreshold: Float = 7f,
    private val minGapMs: Long = 300L,
    private val maxGapMs: Long = 1_200L
) {

    // 用 -1 表示「还没等到冲击」。0 是合法时间（单测里就从 0 开始），
    // 拿 0 当哨兵会让「第一次冲击恰好发生在 t=0」永远记不上。
    private var firstAt = -1L
    private var firstSign = 0

    /** 一次摇动之后冷却，避免同一晃被算成好几次 */
    private var quietUntil = 0L

    /**
     * 喂一个采样。
     *
     * @param t 时刻（elapsedRealtime 或 uptimeMillis，只要单调即可）
     * @return true 表示「这一次采样刚好补全了一次摇动」
     */
    @Synchronized
    fun feed(x: Float, y: Float, z: Float, t: Long): Boolean {
        if (t < quietUntil) return false

        val mag = sqrt(x * x + y * y + z * z)
        val dev = abs(mag - GRAVITY)
        // 主导轴：摇动方向。取绝对值最大的那一轴，用它的符号判「反方向」
        val axis = when {
            abs(x) >= abs(y) && abs(x) >= abs(z) -> x
            abs(y) >= abs(z) -> y
            else -> z
        }
        val sign = if (axis >= 0f) 1 else -1

        if (firstAt < 0L) {
            if (dev >= hitThreshold) {
                firstAt = t
                firstSign = sign
            }
            return false
        }

        // 超时：这一次冲击没等到回弹，重新开始
        if (t - firstAt > maxGapMs) {
            firstAt = -1L
            firstSign = 0
            if (dev >= hitThreshold) {
                firstAt = t
                firstSign = sign
            }
            return false
        }

        if (t - firstAt < minGapMs) return false
        if (dev < reboundThreshold) return false
        if (sign == firstSign) return false

        // 冲击 + 回弹，且方向相反 —— 这才是一次「摇」
        firstAt = -1L
        firstSign = 0
        quietUntil = t + COOLDOWN_MS
        return true
    }

    @Synchronized
    fun reset() {
        firstAt = -1L
        firstSign = 0
        quietUntil = 0L
    }

    companion object {
        /** 标准重力 */
        const val GRAVITY = 9.80665f

        /** 一次判定之后的冷却：800ms 内不再重复触发 */
        const val COOLDOWN_MS = 800L
    }
}
