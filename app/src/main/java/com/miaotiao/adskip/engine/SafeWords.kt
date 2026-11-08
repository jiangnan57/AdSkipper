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

    /**
     * 广告专属语义，命中即放行。
     *
     * 英文词必须带词边界：否则一条正文写着 `[jiangnan57/AdSkipper] Run failed`
     * 的聊天消息会因为含 `Skip` 被当成「广告关闭键」，一路放行到点击。
     */
    private val AD_TOKEN = Regex(
        "(跳过|关闭广告|关闭开屏|关闭弹窗|跳过广告|" +
            "(?<![A-Za-z])skip(?![A-Za-z])|(?<![A-Za-z])dismiss(?![A-Za-z])|sponsor)",
        RegexOption.IGNORE_CASE
    )

    /**
     * **绝对否决词**：出现即拒绝，优先级高于下面所有「放行」判断。
     *
     * 起因（v1.3.6，番茄小说「书页退回书架」）：阅读页上有个节点的无障碍描述是
     * 「点击关闭广告并退出小说」。它含「关闭广告」→ AD_TOKEN 命中 → 直接放行 →
     * 一点下去，ReaderActivity 就被 finish 了，用户看到的就是「书页退回书架」。
     * 这个节点每 4 秒被点一次，用户重新进书页再被弹回去，连看到第几章都丢。
     *
     * 教训：`AD_TOKEN 命中即放行` 是个错误的短路。文案里**同时**出现广告词和
     * 退出语义时，它不是跳过按钮，而是「关闭广告（顺便退出）」这类描述性文案。
     * 所以这里必须先判否决词，再谈放行。
     *
     * 这些词在任何正常的「跳过广告」按钮上都不可能出现——只要出现，宁可漏跳。
     */
    private val VETO_WORD = Regex(
        "(退出|关闭书籍|关闭小说|退出阅读|返回|上一页|下一页|目录|书架|书城|" +
            "(?<![A-Za-z])back(?![A-Za-z])|(?<![A-Za-z])exit(?![A-Za-z])|" +
            "(?<![A-Za-z])quit(?![A-Za-z])|(?<![A-Za-z])leave(?![A-Za-z]))",
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
        // 先否决，再看放行——顺序反了就会放过「点击关闭广告并退出小说」
        if (VETO_WORD.containsMatchIn(text)) return true
        if (AD_TOKEN.containsMatchIn(text)) return false
        return UI_WORD.containsMatchIn(text)
    }

    /**
     * 只判「绝对否决词」，不管界面通用词。
     *
     * 给已经确认是关闭控件的节点用：它们的文案本来就可能是「关闭」「×」，
     * 走 isUnsafe 会被 UI_WORD 拦掉（样本 #5「只有 ✕ 图标、描述是关闭」）。
     * 但「退出 / 返回 / 书架」这类词即便长得再像关闭键，也绝不能放过。
     */
    fun isVetoed(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return VETO_WORD.containsMatchIn(text)
    }

    /** 学习规则时用：明显是界面按钮的文案不要学成「跳过」规则 */
    fun isLearnable(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return !isUnsafe(text)
    }
}
