package com.miaotiao.adskip.engine

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「学弹窗」的核心：用户点一下那个关不掉的按钮，就把它记成一条弹窗规则。
 *
 * 和自动学习（LearnStore）的分工：
 *   - 自动学习是启发式**猜**开屏的「跳过」，写进 splash.keywords_append
 *   - 这里是用户**指**的，写进 popups 的 when/action，能关内容页浮层
 *
 * 只处理节点里读得出「控件 id / 内容描述 / 文字」的按钮。
 * 纯图标（id、text、desc 全空，只剩 bounds）暂不支持：那种只能靠几何猜，
 * 而内容页右上角的「分类」「扫一扫」跟它长得一模一样，误伤率远高于收益。
 * 本机实测的裸叉号样本（variant 951）在无障碍树里就是这样一行：
 *   View | id='' | text='' | desc='' | [1155,1392][1305,1542] | clickable
 * 它一个字段都没有，任何靠文字/id 的路子都够不着。
 *
 * 这里所有方法都不碰 Android 的窗口 API，只吃已经抽成纯数据的 [Cand]，
 * 所以整套判定可以在 JVM 单测里跑，不用连真机。
 */
object PopupTeacher {

    /** 一次最多收集多少个候选，防止 WebView 里几千个节点把界面撑死 */
    const val MAX_CANDIDATES = 240

    /**
     * 目标按钮的面积上限（占屏幕的比例）。
     *
     * 超过这个值的节点不会是「关闭」这种按钮：弹窗卡片本身约 16%、
     * 全屏遮罩 100%，都不该被当成可学目标。底部整行按钮实测约 4.7%，
     * 所以 12.5% 这条线留得够宽。
     */
    private const val MAX_TARGET_RATIO = 0.125

    /** 从节点树里抽出来的纯数据：节点回收之后它仍然可用 */
    class Cand(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String,
        val desc: String,
        val viewId: String,
        val clickable: Boolean
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height

        fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

        /** 界面上给它起的名字，用来在提示里回显「你点的是哪个」 */
        fun label(): String {
            val s = when {
                viewId.isNotBlank() -> viewId.substringAfterLast('/')
                desc.isNotBlank() -> desc
                text.isNotBlank() -> text
                else -> "这个按钮"
            }
            return s.take(16)
        }

        override fun toString(): String = "Cand(${label()}, $left,$top,$right,$bottom)"
    }

    // ------------------------------------------------------------------ 采集

    /**
     * 把整棵树压成候选列表。
     *
     * 收进来的条件：有 id / 有描述 / 有文字，**或者**自身可点。
     * 最后那条是为了裸图标——它一个标签都没有，但 clickable=true，
     * 得让它进列表，用户点上去才能得到「读不出文字」这句准确提示，
     * 而不是含糊的「这里没有按钮」。
     */
    fun collect(tree: List<AccessibilityNodeInfo>, screenArea: Long): List<Cand> {
        val out = ArrayList<Cand>(64)
        for (n in tree) {
            if (out.size >= MAX_CANDIDATES) break
            val r = Access.bounds(n)
            if (r.width() <= 0 || r.height() <= 0) continue
            val area = r.width().toLong() * r.height()
            // 全屏容器（遮罩、广告本体）不是按钮，进来只会被误选
            if (screenArea > 0 && area > screenArea * 4 / 5) continue

            val ck = runCatching { n.isClickable }.getOrDefault(false)
            val t = runCatching { n.text?.toString() }.getOrNull().orEmpty().trim()
            val d = runCatching { n.contentDescription?.toString() }.getOrNull().orEmpty().trim()
            val v = runCatching { n.viewIdResourceName }.getOrNull().orEmpty()
            if (!ck && t.isEmpty() && d.isEmpty() && v.isEmpty()) continue

            out.add(Cand(r.left, r.top, r.right, r.bottom, t.take(80), d.take(80), v, ck))
        }
        return out
    }

    /**
     * 屏幕上一个点落在哪个按钮上：取包含该点、面积最小、**且自身可点**的那个。
     *
     * 「自身可点」这一条是被真机教出来的。加它之前，用户戳右上角的叉号，
     * 拿到的是标题「会员特惠」——叉号读不出任何标签所以没进候选，而标题
     * 刚好压在手指底下、面积又小。于是生成 `when:会员特惠 → action:会员特惠`，
     * 执行时去点一段不可点的文字，Access.click 顺着父链找到可点的卡片，
     * 一点就跑到了广告落地页。用户看到的是「学完之后弹窗没关，反而跳走了」。
     *
     * 现在宁可返回 null 让用户重新点，也不接受「点在文字上」。
     */
    fun pickAt(cands: List<Cand>, x: Int, y: Int, screenArea: Long): Cand? {
        val limit = if (screenArea > 0) (screenArea * MAX_TARGET_RATIO).toLong() else Long.MAX_VALUE
        var best: Cand? = null
        for (c in cands) {
            if (!c.clickable) continue
            if (c.area > limit) continue
            if (!c.contains(x, y)) continue
            if (best == null || c.area < best.area) best = c
        }
        return best
    }

    // ------------------------------------------------------------------ 写规则

    /**
     * 给目标按钮挑一个「定位写法」，优先级：控件 id > 内容描述 > 文字。
     *
     * 返回 null 表示这个按钮读不出任何能写成规则的东西，此时调用方应当
     * 明确告诉用户「学不了」，而不是退化成坐标——坐标换个分辨率就废，
     * 而且会在完全无关的页面上生效（LearnStore 里记着这个事故）。
     */
    fun locatorFor(c: Cand): String? {
        if (c.viewId.contains('/') && c.viewId.length < 96) return c.viewId
        return normalize(c.desc) ?: normalize(c.text)
    }

    /**
     * 挑一个「弹窗锚点」，也就是 when 条件：屏幕上出现这句话，才认为弹窗在。
     *
     * 两条路：
     *   1. 目标按钮自己的文字够特异（例如「以后再说」），它本身就是最好的锚点：
     *      「出现『以后再说』就点它」是一条完全成立的规则。
     *   2. 否则从同屏文字里找标题：靠上的、短句、且自身不可点。
     *      不可点这一条很关键——可点的多半是另一个按钮，拿它当锚点会互相干扰。
     */
    fun anchorFor(cands: List<Cand>, target: Cand): String? {
        val own = normalize(target.text)
        if (own != null && standalone(own)) return own

        val ownText = target.text.trim()
        val pool = cands.asSequence()
            .filter { it !== target && it.text.isNotBlank() }
            .filter { !it.clickable }
            .filter { it.top <= target.bottom }
            .sortedWith(compareBy({ it.top }, { it.left }))

        for (c in pool) {
            if (c.text.trim() == ownText) continue
            val t = normalize(c.text) ?: continue
            if (!standalone(t)) continue
            return t
        }
        return null
    }

    /**
     * 拼一条弹窗规则。anchor 与 action 都就绪才返回，否则 null。
     *
     * cooldown 固定 8 秒：同一个弹窗关掉之后不该马上再点一次，
     * 否则用户点到「以后再说」后新页面里若还有同样的字，会连点。
     */
    fun buildRule(target: Cand, anchor: String): Rule? {
        val action = locatorFor(target) ?: return null
        if (anchor.isBlank()) return null
        return Rule(anchor, action)
    }

    /** 一条待写入的弹窗规则，不掺任何存储格式 */
    class Rule(
        val whenText: String,
        val actionText: String,
        val cooldownMs: Long = 8000L
    )

    // ------------------------------------------------------------------ 文字判定

    /**
     * 把一段界面文字变成可写进规则的表达式。
     *
     * 规则语法里 `+ - =` 是前缀修饰符、`&` 是「且」，所以这些字符出现在
     * 文字开头或中间时必须放弃——没有转义机制，硬写进去会被解析成别的意思。
     */
    private fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 16) return null
        if (t.contains('&')) return null
        if (t[0] == '+' || t[0] == '-' || t[0] == '=') return null
        // 单个 ASCII 字符（×、x、>）太泛，不写进规则
        if (t.length == 1 && t[0].code < 128) return null
        // 「5」「12s」这种倒计时每天都变，学了也没用
        if (t.all { it.isDigit() || it == 's' || it == 'S' || it == '秒' }) return null
        return t
    }

    /**
     * 这段文字够不够「特异」，能不能单独当锚点。
     *
     * 不能用 SafeWords.isUnsafe 来判：那是「这个按钮能不能点」的标准，
     * 凡是带「会员 / 关闭 / 取消 / 下载」的文案一律拒绝点击。可「会员特惠」
     * 恰恰是最理想的锚点——它保证只在这个弹窗上出现。用错标准的后果是
     * 大部分正常标题都被判成不可用，最后一条规则都学不出来。
     *
     * 实际要防的只有两件事：
     *   1. 太短且是通用界面词（「关闭」「取消」）——换个 App 满地都是；
     *   2. 倒计时（「5」「12s」）——每天都不一样，学了等于没学。
     * 三个字以上的中文标题天然足够特异，直接放行。
     */
    private fun standalone(t: String): Boolean {
        if (t.length < 2) return false
        if (t.length >= 3) return !t.all { it.isDigit() || it == 's' || it == 'S' }
        return t !in GENERIC_WORDS
    }

    /** 两个字的通用界面词：出现在任何页面上都不奇怪，不能当锚点 */
    private val GENERIC_WORDS = setOf(
        "关闭", "取消", "确定", "好的", "知道", "以后", "暂不", "跳过", "返回",
        "更多", "设置", "首页", "我的", "评论", "点赞", "分享", "收藏", "关注",
        "搜索", "消息", "下载", "安装", "领取", "签到", "会员", "充值", "客服",
        "举报", "退出", "同意", "拒绝", "允许", "继续", "完成", "下一步", "上一步"
    )
}
