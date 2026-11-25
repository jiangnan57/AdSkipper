package com.miaotiao.adskip.data

/**
 * 拦截账本：记最近几次拦截，用来回答一个很朴素的问题——
 * **「刚拦完，用户是不是又自己点回去了？」**
 *
 * ## 为什么要记
 *
 * 判定链能给出的是一组条件（来源冷启动、还没进正片、落地很快……），
 * 它不是事实。真正的事实只有一个：用户被弹回去之后，如果 10 秒内又点开
 * 同一个 App，那这次拦截十有八九是错的。
 *
 * 这条信号有三个用途：
 *
 *   1. 立刻在「最近记录」里写一行，用户反馈时不用再猜是哪次拦错了；
 *   2. 影子模式跑一周，用它算出「误拦率」，再决定要不要收紧判定；
 *   3. 后续（v1.4.0）可以直接拿它当反馈信号，对同一对 (来源, 目的地) 熔断。
 *
 * ## 为什么在内存里，不落盘
 *
 * 它只关心「刚才那一下」。进程重启之后旧账没有意义，落盘反而会让下次
 * 启动时冒出一条莫名其妙的「你可能拦错了」。
 *
 * ## 为什么不用 android 的东西
 *
 * 这个文件必须能在 JVM 单测里跑（见 `InterceptLedgerTest`）。时间由调用方
 * 传进来，不读 `SystemClock`。
 */
object InterceptLedger {

    /**
     * 「回访」的时间窗：10 秒。
     *
     * 比这更久就不能证明什么了——用户可能就是过一会儿想用那个 App。
     * 10 秒内回去，几乎只有一种解释：他刚才点开的就是它。
     */
    const val VISIT_WINDOW_MS = 10_000L

    /** 只留最近 5 条。够覆盖「连着拦了几次」的场景，又不会无限涨。 */
    private const val MAX = 5

    /** 一次拦截 */
    data class Intercept(
        val dest: String,
        val origin: String?,
        val at: Long
    )

    private val ring = ArrayDeque<Intercept>()

    /** 记一次**真的动手了**的拦截。影子模式不记账，否则基线会被污染。 */
    @Synchronized
    fun record(dest: String, origin: String?, at: Long) {
        if (dest.isEmpty()) return
        ring.addLast(Intercept(dest, origin, at))
        while (ring.size > MAX) ring.removeFirst()
    }

    /**
     * 前台变了，看看是不是「刚被拦过的地方又回来了」。
     *
     * @param selfRelaunch 是不是**我们自己**把它拉回前台的（三级兜底的第 2 级）。
     *   是的话不算回访——那正是拦截成功的表现。
     * @return 命中的那条拦截；没命中返回 null。命中后这条记录会被消费掉，
     *   避免同一次回访在日志里刷很多遍。
     */
    @Synchronized
    fun noteForeground(pkg: String, at: Long, selfRelaunch: Boolean): Intercept? {
        if (pkg.isEmpty() || selfRelaunch) return null
        val hit = ring.lastOrNull { it.dest == pkg && at >= it.at && at - it.at <= VISIT_WINDOW_MS }
            ?: return null
        ring.remove(hit)
        return hit
    }

    /** 单元测试与「清空记录」用 */
    @Synchronized
    fun clear() {
        ring.clear()
    }

    /** 单元测试用：当前记账条数 */
    @Synchronized
    fun size(): Int = ring.size
}
