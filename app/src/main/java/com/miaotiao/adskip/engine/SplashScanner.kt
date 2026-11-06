package com.miaotiao.adskip.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 开屏广告识别器。
 *
 * 一次遍历（Access.walk）拿全树，规则匹配和启发式判断都用这同一棵树。
 * 所有扫描都通过 NodePool 追踪跨进程节点：结果最多保留 6 个候选，
 * 其余节点在 scan 返回前释放，候选节点由调用方用 recycle(result) 释放。
 */
object SplashScanner {

    class Candidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val byRule: Boolean,
        val reason: String,
        val label: String,
        val rect: Rect
    )

    class Result(
        val nodes: Int,
        val costMs: Long,
        val best: Candidate?,
        val candidates: List<Candidate>,
        val bigArea: Long,
        val full: Boolean = true,
        val contentPage: Boolean = false
    )

    private val COUNTDOWN = Regex("""^\d{1,2}\s*[sS秒]?$""")

    /**
     * 「这是个关闭广告的控件」的判据。
     *
     * 以前这里还包含单独的 `close` 和 `关闭`，后果很严重：抖音评论区的关闭×、
     * 浏览器标签页的关闭、各类弹窗的关闭，无障碍描述全是「关闭」，
     * 于是每进一次评论区就被自动关掉。现在只认广告专属语义。
     */
    private val CLOSE_TOKEN = Regex("""(?i)skip|dismiss|跳过|关闭广告|关闭开屏""")

    /** 页面上有没有「这是广告」的佐证，用来给纯图标×兜底 */
    private val AD_WORD = Regex("""(?i)\bAD\b|广告|推广|赞助|sponsor""")

    /** 只由 × ✕ ✖ 这类字符组成的节点，就是关闭图标本身 */
    private val CLOSE_GLYPH = Regex("""^[×✕✖✗xX✘]+$""")

    /** 底部导航 / 频道栏的词。命中说明这是内容页，不是开屏页 */
    private val TAB_WORDS = Regex(
        "^(首页|推荐|我的|关注|消息|发现|书架|书城|分类|短剧|视频|小说|听书|直播|" +
            "购物|赚钱|任务|搜索|排行|榜单|完本|书单|追更)$"
    )

    /** 打开后会把整树里每个带文字/id 的节点打进 logcat，抓广告控件时用 */
    const val DEBUG_NODES = false

    private const val REASON_X = "右上角×"

    /**
     * 对外入口：包住整轮遍历，保证「节点一定被释放」且「异常不会冒泡」。
     *
     * 无障碍节点是跨进程对象，界面切换的瞬间整棵树可能失效，读属性会抛
     * IllegalStateException。以前这种异常会一路冒到 requestScan，被当成
     * 「worker 线程不行」而永久切回主线程扫描——而主线程读窗口本来就是
     * 死锁高发路径，等于把一次偶发抖动变成整个服务卡死。
     *
     * 现在异常就地降级成「本轮没命中」，节点在 finally 里统一回收，
     * 扫描链按正常间隔继续，下一轮自己就恢复了。
     */
    fun scan(
        root: AccessibilityNodeInfo?,
        splash: SplashRule,
        screenW: Int,
        screenH: Int,
        allowHeuristic: Boolean = false,
        full: Boolean = true
    ): Result {
        val t0 = System.nanoTime()
        val pool = NodePool()
        return try {
            scanTree(root, splash, screenW, screenH, allowHeuristic, full, t0, pool)
        } catch (t: Throwable) {
            Log.w("AdSkip", "scan aborted: ${t.javaClass.simpleName}", t)
            Result(0, elapsed(t0), null, emptyList(), 0L)
        } finally {
            // scanTree 正常返回前已经 releaseExcept(top) 并把 all 清空，
            // 这里就是空操作；只有抛异常时 all 还满着，由它兜底释放。
            // 这样 root 以及遍历到一半拿到的节点都不会泄漏。
            pool.releaseExcept(emptyList())
        }
    }

    private fun scanTree(
        root: AccessibilityNodeInfo?,
        splash: SplashRule,
        screenW: Int,
        screenH: Int,
        allowHeuristic: Boolean,
        full: Boolean,
        t0: Long,
        pool: NodePool
    ): Result {
        if (root == null) return Result(0, 0L, null, emptyList(), 0L)
        val screenArea = screenW.toLong() * screenH
        if (screenArea <= 0L) {
            Access.recycle(root)
            return Result(0, 0L, null, emptyList(), 0L)
        }
        pool.track(root)

        // ---------------- 一次遍历拿全树
        //
        // 这里刻意不用系统的 findAccessibilityNodeInfosByViewId / ByText。
        // 那两个 API 在服务端做递归遍历，命中 WebView 页面时实测：
        //   ByViewId（3 个关键词 = 3 次全树遍历）→ 1269ms
        //   ByText                         → 3200ms
        // 而开屏广告整段只有 5 秒，卡一次就等于这一轮白扫。
        // 更糟的是按关键词逐个调用，等于把同一棵树重复遍历 N 次。
        //
        // 自己 walk + 本地正则匹配：oopz 广告页 61 个节点只要 114ms，
        // 而且整轮只遍历一次，viewId 和文字一起匹配完。
        val tree = Access.walk(root, Access.MAX_NODES)
        tree.forEach(pool::track)

        // ---------------- 规则命中（本地匹配，零额外 IPC）
        val fast = ruleHits(tree, splash, screenW, screenH, screenArea)
        if (fast.isNotEmpty()) {
            val top = rank(fast)
            val result = Result(tree.size, elapsed(t0), top.firstOrNull(), top, 0L, full = false)
            pool.releaseExcept(top)
            return result
        }
        if (!full && !allowHeuristic) {
            val result = Result(tree.size, elapsed(t0), null, emptyList(), 0L, full = false)
            pool.releaseExcept(emptyList())
            return result
        }

        // ---------------- 慢路径：在同一棵树上做启发式判断
        val maxAlgoArea = (screenArea * 0.06f).toLong()
        val ex = splash.ex

        val found = ArrayList<Candidate>(8)
        val algo = ArrayList<Candidate>(6)
        var bigArea = 0L
        var scrollArea = 0L
        var clickable = 0
        var count = 0
        var textCount = 0
        var tabHits = 0
        var adSignal = false

        for (n in tree) {
            count++

            val r = Rect()
            n.getBoundsInScreen(r)
            val visible = r.width() > 0 && r.height() > 0
            val area = if (visible) r.width().toLong() * r.height() else 0L

            val text = n.text
            val desc = n.contentDescription
            val vid = n.viewIdResourceName
            val hasText = !text.isNullOrBlank() || !desc.isNullOrBlank()

            if (visible) {
                if (n.isClickable) clickable++
                if (n.isScrollable && area > scrollArea) scrollArea = area

                // 内容页的两个特征：文字多、底部有导航/频道栏
                if (hasText) {
                    textCount++
                    val t = text
                    if (t != null && t.length <= 24) {
                        if (AD_WORD.containsMatchIn(t)) adSignal = true
                        if (r.top > screenH * 0.80f && TAB_WORDS.matches(t.trim())) tabHits++
                    }
                    val d = desc
                    if (d != null && d.length <= 24 && AD_WORD.containsMatchIn(d)) adSignal = true
                }

                // 诊断：把带文字 / 带 id 的节点原样打出来，用来确认
                // 「跳过」按钮到底在不在树里、是什么形态
                if (DEBUG_NODES && (hasText || !vid.isNullOrBlank())) {
                    Log.d(
                        "AdSkip",
                        "node t=${text} d=${desc} id=${vid} clk=${n.isClickable} " +
                            "area=${area} r=${r.top},${r.left},${r.right},${r.bottom}"
                    )
                }

                if (!hasText && vid.isNullOrBlank()) {
                    if (area > bigArea) bigArea = area
                    // 无文字、无 id 的右上角 X 也要有机会进入启发式判断
                    if (allowHeuristic) {
                        heuristic(n, text, desc, vid, r, area, screenW, screenH, screenArea, maxAlgoArea)
                            ?.let(algo::add)
                    }
                } else if (!(ex.isNotEmpty() && Matcher.hitAny(ex, n))) {
                    // 规则命中已经在 ruleHits 里处理过了——能走到这里说明
                    // 规则没命中，这里只需要补启发式候选。
                    if (allowHeuristic) {
                        heuristic(n, text, desc, vid, r, area, screenW, screenH, screenArea, maxAlgoArea)
                            ?.let(algo::add)
                    }
                }
            }
        }

        // 内容页判定：可滚列表 / 入口多 / 底部有导航栏 / 文字密集，
        // 任一命中都不认为这是开屏页（阈值比以前更严，误点的代价大于漏跳）
        val contentPage = scrollArea >= screenArea * 0.22f ||
            clickable > 6 || tabHits > 0 || textCount > 55

        // 启发式是「猜」，所以要求更硬：整页一张大图、几乎没有可点元素
        val strictAdPage = bigArea >= screenArea * 0.70f && clickable <= 4 && !contentPage
        if (allowHeuristic && strictAdPage) {
            for (a in algo) {
                if (a.score > 1100) continue
                // 纯图标 ×：页面上没出现「广告」字样就当它是顶栏图标，不点
                if (a.reason == REASON_X && !adSignal) continue
                found.add(a)
            }
        }

        val top = rank(found)
        val result = Result(
            count, elapsed(t0), top.firstOrNull(), top, bigArea,
            full = true, contentPage = contentPage
        )
        pool.releaseExcept(top)
        return result
    }

    /** 调用方使用完 best / candidates 后调用，幂等且不会重复释放同一对象。 */
    fun recycle(result: Result) {
        val seen = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        for (candidate in result.candidates) {
            if (seen.add(candidate.node)) Access.recycle(candidate.node)
        }
    }

    /**
     * 追踪本轮扫描拿到的所有节点。
     *
     * 用 IdentityHashMap 去重是必须的：同一节点可能被「id 检索」和「文本检索」
     * 各返回一次，也可能被多个关键词命中，`track` 就会被调用多次。
     * 旧实现直接 addAll 到 ArrayList，`releaseExcept` 于是对同一个节点
     * recycle 两遍——虽然 runCatching 吞掉了 IllegalStateException，
     * 但被提前回收的节点在后续规则里再读属性就只能拿到 null，
     * 表现为「弹窗规则明明配了却不生效」，排查起来毫无线索。
     *
     * releaseExcept 调用后会清空 all / seen，所以可以重复调用（scan 的
     * finally 就靠这个特性做异常兜底），不会把已经交给调用方的候选释放掉。
     */
    private class NodePool {
        private val seen = Collections.newSetFromMap(
            IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        )
        private val all = ArrayList<AccessibilityNodeInfo>(128)

        fun track(node: AccessibilityNodeInfo) {
            if (seen.add(node)) all.add(node)
        }

        fun releaseExcept(keep: List<Candidate>) {
            val retained = Collections.newSetFromMap(
                IdentityHashMap<AccessibilityNodeInfo, Boolean>()
            )
            keep.forEach { retained.add(it.node) }
            all.forEach { node ->
                if (!retained.contains(node)) Access.recycle(node)
            }
            all.clear()
            seen.clear()
        }
    }

    /**
     * 规则命中：在已经遍历好的节点树上做本地匹配。
     *
     * 这里不做任何系统检索（findAccessibilityNodeInfosByViewId / ByText），
     * 全部用 Matcher 在本地比对 text / contentDescription / viewIdResourceName。
     *
     * 为什么：那两个 API 在服务端做递归遍历，命中 WebView 页面时实测
     * ByViewId（oopz 的 3 个关键词 = 3 次全树遍历）要 1269ms，ByText 要 3200ms，
     * 而开屏广告整段只有 5 秒。改成本地匹配后，同样 61 个节点只要 114ms，
     * 而且只遍历一次树——viewId 和文字一起匹配完。
     */
    private fun ruleHits(
        tree: List<AccessibilityNodeInfo>,
        splash: SplashRule,
        screenW: Int,
        screenH: Int,
        screenArea: Long
    ): List<Candidate> {
        val kw = splash.kw
        if (kw.isEmpty()) return emptyList()
        val maxRuleArea = (screenArea * 0.22f).toLong()
        val out = ArrayList<Candidate>(4)
        val r = Rect()

        for (n in tree) {
            val hit = Matcher.firstHit(kw, n) ?: continue
            if (splash.ex.isNotEmpty() && Matcher.hitAny(splash.ex, n)) continue
            n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (area <= 0L || area > maxRuleArea) continue
            // 系统文本检索是「包含」匹配，容易把「关闭评论」「分类」带出来，
            // 这里做一次同样的通用词拦截
            if (SafeWords.isUnsafe(Access.label(n))) continue
            out.add(
                Candidate(
                    n,
                    ruleScore(r, area, screenArea, screenW, screenH, n, hit, n.viewIdResourceName),
                    true, hit.raw, Access.label(n),
                    // 必须拷贝：r 是循环外复用的临时对象，直接传引用会让所有
                    // 候选共享同一个 Rect（值等于最后一个节点的边界）。
                    // 后果是 rank 的 distinctBy 把所有候选判成同一个位置、
                    // 只留 1 条，自检面板显示的按钮位置也全是错的。
                    Rect(r)
                )
            )
        }
        return out
    }

    private fun ruleScore(
        r: Rect,
        area: Long,
        screenArea: Long,
        screenW: Int,
        screenH: Int,
        n: AccessibilityNodeInfo,
        hit: Matcher.Keyword,
        vid: String?
    ): Int {
        var score = 0
        val onId = vid != null && hit.hit(vid)
        if (!onId) score += 40
        score += (area * 400L / screenArea).toInt()
        score += (r.exactCenterY() / screenH * 120f).toInt()
        if (r.exactCenterX() < screenW * 0.45f) score += 24
        if (!n.isClickable) score += 12
        return score
    }

    private fun rank(list: List<Candidate>): List<Candidate> =
        list.sortedWith(compareBy<Candidate>({ if (it.byRule) 0 else 1 }, { it.score }))
            .distinctBy { "${it.rect.left},${it.rect.top},${it.rect.right},${it.rect.bottom}" }
            .take(6)

    private fun heuristic(
        n: AccessibilityNodeInfo,
        text: CharSequence?,
        desc: CharSequence?,
        vid: String?,
        r: Rect,
        area: Long,
        screenW: Int,
        screenH: Int,
        screenArea: Long,
        maxArea: Long
    ): Candidate? {
        if (area < 120L || area > maxArea) return null

        val cx = r.exactCenterX()
        val cy = r.exactCenterY()

        // 开屏广告的关闭/跳过只可能落在两个角：右上角（×）和右下角（跳过）。
        // 评论区、详情页、弹窗的关闭按钮大多在屏幕中部或左上——
        // 这一刀能挡掉之前绝大部分误点（抖音评论区、番茄点赞都是这么中招的）。
        val inTopRight = cx > screenW * 0.74f && cy < screenH * 0.20f
        val inBottomRight = cx > screenW * 0.58f && cy > screenH * 0.78f
        if (!inTopRight && !inBottomRight) return null
        if (inTopRight && area > screenArea * 0.035f) return null
        if (area > screenArea * 0.05f) return null

        val label = Access.label(n)

        val countdown = text != null && COUNTDOWN.matches(text.trim())

        // 严格判据：文案里带「跳过 / 关闭广告」这类广告专属词
        val strictClose = (text != null && CLOSE_TOKEN.containsMatchIn(text)) ||
            (desc != null && CLOSE_TOKEN.containsMatchIn(desc)) ||
            (vid != null && CLOSE_TOKEN.containsMatchIn(vid))

        // 宽松判据：只在右上角成立的「× 图形 / 关闭描述」。
        // 开屏广告里很常见「只有一个 ✕ 图标、描述是关闭」，这类按钮必须留一条路，
        // 否则整版只剩一个图标就永远跳不过（测试用例 #5）。
        // 位置限死在右上角，就是为了不碰评论区的关闭 ×（那在屏幕中部）。
        val textStr = text?.toString()?.trim()
        val descStr = desc?.toString()?.trim()
        val softClose = inTopRight && (
            (textStr != null && CLOSE_GLYPH.matches(textStr)) ||
                textStr == "关闭" ||
                descStr == "关闭" ||
                descStr.equals("close", ignoreCase = true)
            )

        val closeWord = strictClose || softClose
        val xIcon = text.isNullOrBlank() && desc.isNullOrBlank() &&
            inTopRight && area <= screenArea * 0.02f

        if (!countdown && !closeWord && !xIcon) return null
        // 通用界面词（分类 / 新对话 / 评论…）一律不点；
        // 已经判定成关闭控件的除外（它的文案本身就是「关闭 / ×」）
        if (!closeWord && SafeWords.isUnsafe(label)) return null

        var score = 1000
        if (countdown) score -= 130
        if (closeWord) score -= 70
        if (xIcon) score -= 10
        score += (area * 200L / screenArea).toInt()
        score += (cy / screenH * 100f).toInt()
        if (cx > screenW * 0.72f) score -= 25
        // 左上角的「返回」和右下角的「跳过」长得像，位置分要拉开
        if (cy > screenH * 0.70f) score -= 15
        if (!n.isClickable && !Access.hasClickableAncestor(n, recyclePath = false)) score += 150

        val reason = when {
            closeWord -> "跳过词"
            countdown -> "倒计时"
            else -> REASON_X
        }
        return Candidate(n, score, false, reason, label, r)
    }

    private fun elapsed(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000L
}
