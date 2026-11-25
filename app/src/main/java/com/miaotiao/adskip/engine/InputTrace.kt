package com.miaotiao.adskip.engine

/**
 * 记录「用户刚刚在这里点了一下」。
 *
 * ## 为什么需要它
 *
 * 防跳转的所有判据都在看**来源侧**：来源冷不冷、开屏结束没、落地快不快。
 * 但「用户点了一下商品卡，然后淘宝起来了」和「摇一摇把淘宝拉起来了」
 * 在前台切换上**完全一样**——时间、包名、来源全同。
 *
 * 唯一的区别在事件流里：用户点击会派发 `TYPE_VIEW_CLICKED`，摇一摇不会。
 *
 * ## 为什么只能当「否决」，不能当「证据」
 *
 * 点击型广告（点屏幕任意位置跳转）的容器也是一个普通 View，
 * 点它同样派发 click。如果把「有点击」直接理解成「用户想跳」，
 * 这类广告会从「拦」变成「不拦」——不是漏一点，是整整一类失效。
 *
 * 所以调用方还要传 [Tap.fullscreen]：冷启动 3 秒内命中且点击目标
 * 覆盖屏幕 85% 以上时，判为「点在广告热区上」，不作为用户输入。
 * 商品卡、按钮的面积远小于全屏。
 *
 * ## 这个文件不许 import android.*
 *
 * 它要能在 JVM 单测里直接跑。时间由调用方给（`SystemClock.uptimeMillis`），
 * 不自己取。
 */
object InputTrace {

    /**
     * 一次点击。
     *
     * @param pkg 点击发生时前台的包名
     * @param at 时刻（uptimeMillis）
     * @param fullscreen 点击目标是否几乎铺满屏幕（按面积比的判断在 service 侧做，
     *   因为要读 `e.source` 的 bounds）
     * @param source 调试用：`node` = 无障碍节点点击，`gesture` = 手势注入，
     *   空 = 未知。日志里能看出一次点击是从哪条路来的
     */
    data class Tap(
        val pkg: String,
        val at: Long,
        val fullscreen: Boolean,
        val source: String = ""
    )

    /** 只留最后一次：判定只关心「刚才那一下」，历史没有意义 */
    @Volatile
    private var lastTap: Tap? = null

    fun note(pkg: String, at: Long, fullscreen: Boolean, source: String = "") {
        if (pkg.isEmpty()) return
        lastTap = Tap(pkg, at, fullscreen, source)
    }

    fun last(): Tap? = lastTap

    /**
     * 是否「刚刚在 [pkg] 里点过」。
     *
     * @param maxAgoMs 时间窗。超过这个时长的点击与本次跳转无关。
     * @return 命中的 Tap；没命中返回 null。全屏点击**不**算命中。
     */
    fun recentUserTap(pkg: String?, now: Long, maxAgoMs: Long = 1_500L): Tap? {
        val t = lastTap ?: return null
        if (pkg == null || t.pkg != pkg) return null
        if (t.fullscreen) return null
        val ago = now - t.at
        if (ago < 0L || ago > maxAgoMs) return null
        return t
    }

    /** 单元测试与「清空来源」时用 */
    fun clear() {
        lastTap = null
    }
}
