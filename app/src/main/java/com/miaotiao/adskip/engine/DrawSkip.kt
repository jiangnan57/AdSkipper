package com.miaotiao.adskip.engine

/**
 * 「这一页是开屏广告」+「这个控件是自绘的跳过按钮」两条判据。
 *
 * 为什么要单独拎出来：有相当一部分开屏广告的「跳过」**没有文字**。
 * 穿山甲竖屏模板就是这样——一个 150×150 的圆形 View，圆圈和「跳过」
 * 两个字都用 Canvas 画，`AccessibilityNodeInfo` 里 text / content-desc /
 * resource-id 全为空。任何关键词匹配都命中不了，只能靠形状 + 位置认。
 *
 * 实测样本（小红书投放给 WakeUp 课程表的开屏广告，1440×3200）：
 *
 * ```
 * View     clickable=true  150×150 @ [60,2326]    ← 跳过按钮
 * TextView clickable=true  120×53  @ [75,2517]    ← 「广告」小标签
 * id_splash_bottom_logo    (穿山甲固定 id)
 * desc=NativeVideoAdView
 * text="向上滑动" / "跳转至详情页或第三方应用"
 * ```
 *
 * 整页只有 2 个可点节点，都没有文字，跳过按钮靠「方形 + 小 + 贴边」
 * 才能认出来；而那条 120×53 的标签因为长宽比 2.26 被排除。
 *
 * 形状是会撞的（视频 App 主界面也有 150×150 的方形按钮），所以调用方
 * 必须再压一道 [markOf]、[markOfId]、[markOfDesc] 的强标记，
 * 两者同时成立才允许点击。
 */
object DrawSkip {

    /**
     * 开屏广告页上的固定引导语。正常内容页不会出现这几句。
     *
     * 故意收得很窄：只收广告 SDK 的模板文案，不收「跳过」本身
     * （内容页上也有「跳过教程」），也不收「广告」（信息流里到处都是）。
     */
    private val MARK = Regex("""向上滑动|跳转至详情页|点击到落地页|滑动查看详情""")

    /** 广告 SDK 的容器 id。穿山甲用 `id_splash_bottom_logo` 这类命名 */
    fun markOfId(vid: String?): Boolean =
        vid != null && vid.contains("splash", ignoreCase = true)

    /** 广告 SDK 的容器描述。优量汇/穿山甲会留 NativeVideoAdView 之类 */
    fun markOfDesc(desc: CharSequence?): Boolean {
        if (desc.isNullOrBlank() || desc.length > 48) return false
        return desc.contains("NativeVideoAd", ignoreCase = true) ||
            desc.contains("AdView", ignoreCase = true)
    }

    /** 开屏模板的引导文案 */
    fun markOf(text: CharSequence?): Boolean {
        if (text.isNullOrBlank() || text.length > 24) return false
        return MARK.containsMatchIn(text)
    }

    /**
     * 形状判据：接近正方形、尺寸小、贴着左右边、不在状态栏/导航条上。
     *
     * 四个条件缺一不可：
     * · 长宽比 ≥ 0.70 —— 排除「跳过 3s」那种扁条和「广告」标签；
     * · 短边在屏宽的 4%~20% —— 跳过按钮一定小，大的是广告内容区；
     * · 水平中心落在左右各 32% 内 —— 跳过按钮永远贴边，不会在画面正中；
     * · 纵向避开顶部 4% 和底部 3% —— 那里是状态栏和导航条，全是系统图标。
     *
     * @param cx 控件水平中心（像素）
     */
    fun shapeOk(
        w: Int,
        h: Int,
        cx: Int,
        top: Int,
        bottom: Int,
        screenW: Int,
        screenH: Int
    ): Boolean {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false
        val short = minOf(w, h)
        val long = maxOf(w, h)
        val ratio = short.toFloat() / long
        val base = minOf(screenW, screenH).toFloat()
        val onEdge = cx < screenW * 0.32f || cx > screenW * 0.68f
        val notBar = top > screenH * 0.04f && bottom < screenH * 0.97f
        return ratio >= 0.70f &&
            short >= base * 0.04f && short <= base * 0.20f &&
            onEdge && notBar
    }
}
