package com.miaotiao.adskip.engine

/**
 * 「这个词能不能点」的最后一道闸门。
 *
 * 起因：规则和学习记录里只要有一个「关闭」，评论区右上角的 ×、浏览器标签页的
 * 关闭按钮、番茄的「分类」、DeepSeek 的「新对话」全都会中招——它们的无障碍描述
 * 就是这些通用词。开屏广告真正需要的词其实只有「跳过 / 关闭广告」这一类。
 *
 * 这里做两件事：
 *   1. 命中「跳过」系词的一律放行（这是广告语义，不会出现在正常界面）；
 *   2. 其余落在界面通用词表里的，一律拒绝点击。
 */
object SafeWords {

    /** 广告专属语义，命中即放行 */
    private val AD_TOKEN = Regex(
        "(跳过|关闭广告|关闭开屏|关闭弹窗|跳过广告|skip|dismiss|sponsor)",
        RegexOption.IGNORE_CASE
    )

    /**
     * 普通界面控件词。
     *
     * 这些词几乎只出现在「功能按钮」上，不是广告关闭键。加进黑名单的代价是
     * 极少数把关闭键写成「关闭」的广告跳不过；收益是不再乱点用户的东西。
     */
    private val UI_WORD = Regex(
        "(分类|新对话|新会话|评论|点赞|喜欢|收藏|分享|关注|已关注|搜索|消息|首页|我的|" +
            "书架|书城|排行|榜单|短剧|小说|听书|直播|购物车|任务|设置|发布|发表|" +
            "下载|安装|领取|签到|退出|返回|上一页|下一页|目录|章节|投票|打赏|举报|" +
            "客服|会员|充值|投币|弹幕|倍速|全屏|清晰度|换一换|展开|收起|查看全部|" +
            "作者|主页|私信|通知|扫一扫|收付款|朋友圈|发现|关闭|取消|确定|更多)"
    )

    /** true 表示这个文案不能点 */
    fun isUnsafe(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (AD_TOKEN.containsMatchIn(text)) return false
        return UI_WORD.containsMatchIn(text)
    }

    /** 学习规则时用：明显是界面按钮的文案不要学成「跳过」规则 */
    fun isLearnable(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return !isUnsafe(text)
    }
}
