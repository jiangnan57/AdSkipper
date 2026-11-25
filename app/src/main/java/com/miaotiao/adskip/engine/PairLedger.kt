package com.miaotiao.adskip.engine

/**
 * (来源, 目的地) 配对账本 —— v1.4.0 的 R10。
 *
 * ## 它回答的问题
 *
 * `RepeatGuard` 只看**目的地**：同一个目的地连拦三次就熔断。
 * 但真正的误拦是有方向的——「WakeUp → 淘宝」被误拦，不代表
 * 「抖音 → 淘宝」也会被误拦。把两者混在一起算，会让一次误判
 * 污染掉一整个目的地的判定。
 *
 * ## 两级熔断
 *
 * 撤销是用户**明确表态**，比「连拦三次」硬得多。所以：
 *
 *   · 同一对撤销 2 次 → 熔断 30 分钟
 *   · 熔断期内再撤销 → 熔断 24 小时
 *
 * 24 小时这一档是给「用户就是想要这个跳转」用的。它不是惩罚，
 * 是把决定权交回去：这一天里这一对不再拦，广告多显示几次，
 * 换用户不用每点一次就被弹一次。
 *
 * ## 边界
 *
 * 只影响「拦不拦」，不碰任何名单，也不落盘。上限 200 对，
 * 超出按最久未用淘汰——它是运行期缓存，不是统计报表。
 */
class PairLedger(private val maxPairs: Int = 200) {

    data class Pair(
        val origin: String?,
        val dest: String,
        var intercepts: Int = 0,
        var undos: Int = 0,
        var lastAt: Long = 0L,
        var mutedUntil: Long = 0L
    )

    private val pairs = LinkedHashMap<String, Pair>()

    private fun key(origin: String?, dest: String) = "${origin ?: "-"}>$dest"

    @Synchronized
    fun noteIntercept(origin: String?, dest: String, now: Long) {
        if (dest.isEmpty()) return
        val k = key(origin, dest)
        val p = pairs.getOrPut(k) { Pair(origin, dest) }
        p.intercepts++
        p.lastAt = now
        trim()
    }

    /**
     * 用户点了一次撤销。
     *
     * @return 本次是否**新触发**了熔断（调用方据此写日志/提示）
     */
    @Synchronized
    fun noteUndo(origin: String?, dest: String, now: Long): Boolean {
        if (dest.isEmpty()) return false
        val k = key(origin, dest)
        val p = pairs.getOrPut(k) { Pair(origin, dest) }
        p.undos++
        p.lastAt = now
        val alreadyMuted = now < p.mutedUntil
        val duration = when {
            alreadyMuted -> STRONG_MUTE_MS
            p.undos >= 2 -> MUTE_MS
            else -> 0L
        }
        if (duration == 0L) return false
        p.mutedUntil = now + duration
        return true
    }

    @Synchronized
    fun isMuted(origin: String?, dest: String, now: Long): Boolean {
        val p = pairs[key(origin, dest)] ?: return false
        if (now >= p.mutedUntil) return false
        return true
    }

    @Synchronized
    fun muteLeftMs(origin: String?, dest: String, now: Long): Long {
        val p = pairs[key(origin, dest)] ?: return 0L
        return (p.mutedUntil - now).coerceAtLeast(0L)
    }

    /** 单元测试与「清空记录」用 */
    @Synchronized
    fun clear() {
        pairs.clear()
    }

    @Synchronized
    fun size(): Int = pairs.size

    @Synchronized
    fun snapshot(): List<Pair> = pairs.values.map { it.copy() }

    private fun trim() {
        while (pairs.size > maxPairs) {
            val oldest = pairs.entries.minByOrNull { it.value.lastAt } ?: return
            pairs.remove(oldest.key)
        }
    }

    companion object {
        /** 同一对撤销 2 次后的熔断时长：30 分钟 */
        const val MUTE_MS = 30 * 60_000L

        /** 熔断期内再撤销：24 小时 */
        const val STRONG_MUTE_MS = 24 * 60 * 60_000L
    }
}
