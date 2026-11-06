package com.miaotiao.adsample

/**
 * 广告形态样本清单。
 * 每个条目对应一种真实 App 开屏广告的「跳过」按钮呈现方式，
 * 用来确定性地检验秒跳的识别引擎，避免依赖真实 App 的广告投放。
 */
object Variants {

    data class V(val id: Int, val name: String, val expectSkip: Boolean)

    val ALL = listOf(
        V(1, "右上角「跳过 5s」（带倒计时文字）", true),
        V(2, "右上角「跳过广告」", true),
        V(3, "右下角圆形「跳过」", true),
        V(4, "右上角纯数字倒计时「5」", true),
        V(5, "右上角关闭图标（无文字，仅 contentDesc）", true),
        V(6, "底部「点击跳过广告 3」", true),
        V(7, "WebView 内「跳过 5」（模拟 oopz）", true),
        V(8, "「跳过」被全屏可点击广告容器包裹", true),
        V(9, "右上角英文「Skip」", true),
        V(10, "右上角「关闭广告」", true),
        V(11, "右上角仅「3s」（秒数+单位，无跳过字样）", true),
        V(12, "「跳过」位于屏幕 78% 高度（下方区域）", true),
        V(13, "「跳过」为不可点击 TextView（靠父容器响应）", true),
        V(14, "含空格分隔「跳 过」（间隔字符）", true),

        // ---- 真实穿山甲/腾讯广告的形态：跳过按钮由触摸事件处理，节点自身
        //      clickable=false；整棵树里唯一的可点击祖先是「全屏广告容器」，
        //      而容器的 onClick 就是跳广告落地页。
        //      QQ 跳 QQ 邮箱、WakeUp 摇一摇跳应用商店都是这一类：
        //      performAction(CLICK) 落到容器上，等于点了广告本体。
        V(15, "跳过按钮不可点，唯一可点祖先是全屏广告容器", true),
        V(99, "对照组：正常内容页（不该被点击）", false),

        // ---- 弹窗样本：不是开屏广告，而是内容页上浮出来的一个弹层，
        //      需要靠「弹窗规则」关闭（when 条件 + action 按钮）。
        V(950, "内容页浮层弹窗：标题「会员特惠」+ 按钮「以后再」", true),

        // ---- 负向样本：这几个页面上都有长得像「跳过/关闭」的控件，
        //      但一个都不许点。对应实测中被误伤的真实场景。
        V(901, "番茄小说式首页：右上角「分类」+底部导航+点赞", false),
        V(902, "抖音式评论区半屏弹层：中部「关闭 ×」", false),
        V(903, "DeepSeek 式欢迎页：右上角「新对话」", false),
        V(904, "QQ 聊天页：长消息含「AdSkipper」被 Skip 子串误命中", false)
    )

    fun byId(id: Int): V = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
