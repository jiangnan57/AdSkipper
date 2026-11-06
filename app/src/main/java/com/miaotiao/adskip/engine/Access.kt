package com.miaotiao.adskip.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** 节点树遍历、系统检索、点击与节点生命周期管理。 */
object Access {

    const val MAX_NODES = 700

    /**
     * 一次遍历的时间预算。
     *
     * 节点数上限挡不住「节点少但每个都很慢」的情况：界面切换那一瞬间，
     * 单次 childCount / getChild 的跨进程 IPC 可能挂起几百毫秒甚至几秒。
     * 实测淘宝首页切换时一趟 182 个节点的遍历跑了 5099ms，把
     * adskip-scan 这个 HandlerThread 整条占死——新 App 的开屏监测
     * 头 5 秒一次都扫不了，等于全盲（test_guard.sh 场景 D 就栽在这里）。
     *
     * 所以除了节点数上限，再加一道时间闸：超时就带着已经拿到的节点返回，
     * 本轮按「没命中」处理，下一次轮询马上重来。少扫几十个节点，
     * 远好过整条扫描链停工五秒。
     */
    private const val WALK_BUDGET_MS = 900L

    /** 每访问这么多个节点看一次时钟，避免频繁调用 */
    private const val BUDGET_CHECK_MASK = 0x3F

    /**
     * AccessibilityNodeInfo 持有跨进程资源。API 33 起 recycle 虽被标记废弃，
     * 在旧版本上仍必须调用；统一在这里处理，调用方不判断系统版本。
     */
    @Suppress("DEPRECATION")
    fun recycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        runCatching { node.recycle() }
    }

    fun recycleAll(nodes: Iterable<AccessibilityNodeInfo>?) {
        nodes?.forEach(::recycle)
    }

    /** 广度优先遍历。调用方用完后必须 recycleAll 返回列表。 */
    fun walk(
        root: AccessibilityNodeInfo?,
        limit: Int = MAX_NODES,
        budgetMs: Long = WALK_BUDGET_MS
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val t0 = SystemClock.uptimeMillis()
        val out = ArrayList<AccessibilityNodeInfo>(96)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && out.size < limit) {
            if ((out.size and BUDGET_CHECK_MASK) == 0 &&
                SystemClock.uptimeMillis() - t0 > budgetMs
            ) break
            val n = queue.removeFirst()
            out.add(n)
            // 界面正在切换时整棵树可能瞬间失效：childCount / getChild 会抛
            // IllegalStateException 或返回 null。不能让一个坏节点把整轮扫描
            // 带崩（异常冒泡会触发服务降级），也不能把 out 里已拿到的节点丢掉。
            val children = runCatching { n.childCount }.getOrDefault(0)
            for (i in 0 until children) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.addLast(it) }
            }
        }
        // 达到上限时队列里可能还有未访问节点，它们同样要释放
        recycleAll(queue)
        return out
    }

    /**
     * 点击自身或最近的可点击祖先；中间 parent 节点全部释放。
     *
     * 这里有两个必须守住的边界，都是被真实广告教育出来的：
     *
     * 1. 不能盲目用「可点击祖先」的位置来点。
     *    开屏广告几乎是「一个铺满全屏的可点击容器（onClick = 跳广告落地页）
     *    + 里面一个不可点击的『跳过』文字」。点容器中心就是点广告图。
     *
     * 2. 更不能对「可点击祖先」直接 performAction(CLICK)。
     *    这一条比第 1 条更隐蔽：旧代码先看 cur.isClickable，是就 performAction，
     *    只用 tooBig 限制坐标兜底——于是遇到「跳过」自身不可点、祖先正好是
     *    全屏广告容器时，performAction 会**在容器上**执行，等于点了广告本体，
     *    容器 onClick 直接把用户送进广告落地页（QQ 跳 QQ 邮箱、WakeUp 跳应用商店
     *    都是这个形态），而日志还老老实实打「skipped 成功」，完全看不出问题。
     *
     *    现在规定：祖先覆盖超过整屏 25% 就视为「广告本体」，既不许 performAction，
     *    也不许用它的位置，只点节点自己的中心。
     *
     * recyclePath=false 时不释放沿途的祖先节点，交给调用方统一管理。
     * 弹窗扫描把整棵树一次性取出来复用，祖先节点也在树里，
     * 这里释放掉的话，同一轮里后面的规则就会读到已回收的节点
     * （表现为「明明配了两条规则只生效一条」）。
     */
    fun click(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        screenArea: Long = 0L,
        recyclePath: Boolean = true
    ): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        var tapRect: Rect? = null

        val ownRect = bounds(node)
        val ownArea = ownRect.width().toLong() * ownRect.height()
        // 自己有可点区域就直接用它，比祖先可靠
        if (ownRect.width() > 0 && ownRect.height() > 0) tapRect = ownRect

        // WebView（H5）里的虚拟节点走不了 performAction：
        // 它通常返回 true，但页面毫无反应——实测 oopz 开屏广告就是这样，
        // 日志显示「跳过成功」，实际上广告还在。
        // 这类节点只能按坐标点，所以先判断是不是 H5 节点，是就直接点击坐标。
        if (ownRect.width() > 0 && ownRect.height() > 0 && isWebNode(node, recyclePath)) {
            return tapAt(service, ownRect.exactCenterX(), ownRect.exactCenterY())
        }

        // 祖先面积的「广告本体」阈值。screenArea 未知（0）时退化成「不能比节点
        // 大 8 倍以上」，仍然能挡住全屏容器，又不会因为没传参数就完全失效。
        val tooBigThreshold: Long =
            if (screenArea > 0L) (screenArea * 0.25f).toLong() else Long.MAX_VALUE

        while (current != null && depth < 8) {
            val cur = current
            val isInput = cur === node
            if (cur.isClickable && cur.isEnabled) {
                val r = bounds(cur)
                val curArea = r.width().toLong() * r.height()
                val tooBig = if (screenArea > 0L) {
                    curArea > tooBigThreshold
                } else {
                    ownArea > 0L && curArea > ownArea * 8L
                }
                if (tooBig && !isInput) {
                    // 广告本体：跳过它，继续向上找（可能上面还有个真正的小按钮），
                    // 找不到就用节点自身坐标兜底，绝不在它身上 performAction。
                } else if (runCatching {
                        cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                ) {
                    if (!isInput && recyclePath) recycle(cur)
                    return true
                } else if (!tooBig && (tapRect == null || curArea <= ownArea * 6L)) {
                    // 祖先可点但 performAction 失败：只在它不比节点大太多时才用它的位置
                    tapRect = r
                }
            }

            val parent = runCatching { cur.parent }.getOrNull()
            if (!isInput && recyclePath) recycle(cur)
            current = parent
            depth++
        }
        // depth 用尽而 parent 还不为空时，最后一个包装对象没进过循环体，
        // 会漏掉一次 recycle。常驻服务里这种泄漏是累积的，补上。
        if (recyclePath && current !== node) recycle(current)

        val r = tapRect ?: ownRect
        return r.width() > 0 && r.height() > 0 &&
            tapAt(service, r.exactCenterX(), r.exactCenterY())
    }

    /**
     * 判断节点有没有可点击的祖先。
     *
     * recyclePath=false 时不回收沿途的祖先：调用方（SplashScanner 的启发式
     * 判定）拿到的是一棵统一管理的节点树，这里把祖先回收掉，
     * 同一轮里后续的匹配就会读到空节点，表现为「规则配了却不生效」。
     */
    fun hasClickableAncestor(
        node: AccessibilityNodeInfo,
        maxDepth: Int = 4,
        recyclePath: Boolean = true
    ): Boolean {
        var current: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < maxDepth) {
            // 先赋给 val：current 是会被重新赋值的 var，直接在 runCatching
            // 的 lambda 里用它会因为「被可变闭包捕获」而无法智能转非空。
            val c = current
            val hit = c.isClickable && c.isEnabled
            val parent = if (hit) null else runCatching { c.parent }.getOrNull()
            if (recyclePath) recycle(c)
            if (hit) return true
            current = parent
            depth++
        }
        // 同 click：depth 用尽时最后一个 parent 还没被回收
        if (recyclePath) recycle(current)
        return false
    }

    fun bounds(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        return r
    }

    /**
     * 判断节点是不是 WebView 里的 H5 元素。
     *
     * 两个判据，任一成立即可：
     * 1) viewIdResourceName 不带 ":"——原生控件的 id 一定是 "包名:id/名字" 的形式，
     *    H5 元素的 id 是 DOM 里的裸 id（例如 oopz 广告里的 "skip"）。
     * 2) 祖先里有 android.webkit.WebView。
     *
     * recyclePath=false 时不能回收沿途的祖先：它们可能属于调用方统一管理的
     * 节点树，提前回收会让同一轮里后续的匹配读到空节点。
     */
    private fun isWebNode(node: AccessibilityNodeInfo, recyclePath: Boolean): Boolean {
        val vid = runCatching { node.viewIdResourceName }.getOrNull()
        if (!vid.isNullOrBlank() && !vid.contains(':')) return true
        val cls = runCatching { node.className?.toString() }.getOrNull().orEmpty()
        if (cls.contains("WebView", ignoreCase = true)) return true

        var cur: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (cur != null && depth < 6) {
            val c = cur
            val name = runCatching { c.className?.toString() }.getOrNull().orEmpty()
            val next = runCatching { c.parent }.getOrNull()
            val hit = name.contains("WebView", ignoreCase = true)
            if (recyclePath) recycle(c)
            if (hit) {
                if (recyclePath) recycle(next)
                return true
            }
            cur = next
            depth++
        }
        // depth 用尽时最后一个 parent 同样要释放
        if (recyclePath) recycle(cur)
        return false
    }

    fun tapAt(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 45L))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    fun label(node: AccessibilityNodeInfo): String {
        val t = runCatching { node.text?.toString() }.getOrNull()
        if (!t.isNullOrBlank()) return t.take(14)
        val d = runCatching { node.contentDescription?.toString() }.getOrNull()
        if (!d.isNullOrBlank()) return d.take(14)
        val v = runCatching { node.viewIdResourceName }.getOrNull()
        if (!v.isNullOrBlank()) return v.substringAfterLast('/')
        return runCatching { node.className?.toString() }.getOrNull()
            ?.substringAfterLast('.') ?: "节点"
    }

    /** dump 是手动调试功能；子节点读完立即释放，根节点由调用方释放。 */
    fun dump(node: AccessibilityNodeInfo, maxDepth: Int = 28): String {
        val sb = StringBuilder(4096)
        val r = Rect()
        var count = 0

        fun rec(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > maxDepth || count >= MAX_NODES) return
            count++
            repeat(depth) { sb.append("  ") }
            sb.append(runCatching { n.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "?")
            val vid = runCatching { n.viewIdResourceName }.getOrNull()
            if (!vid.isNullOrBlank()) sb.append("  #").append(vid.substringAfterLast('/'))
            val t = runCatching { n.text?.toString() }.getOrNull()
            if (!t.isNullOrBlank()) sb.append("  text=\"").append(t).append('"')
            val d = runCatching { n.contentDescription?.toString() }.getOrNull()
            if (!d.isNullOrBlank()) sb.append("  desc=\"").append(d).append('"')
            if (runCatching { n.isClickable }.getOrDefault(false)) sb.append("  [clickable]")
            runCatching { n.getBoundsInScreen(r) }
            sb.append("  [").append(r.left).append(',').append(r.top).append(',')
                .append(r.right).append(',').append(r.bottom).append("]\n")
            val children = runCatching { n.childCount }.getOrDefault(0)
            for (i in 0 until children) {
                val child = runCatching { n.getChild(i) }.getOrNull() ?: continue
                try {
                    rec(child, depth + 1)
                } finally {
                    // 节点树中途失效时 rec 会抛，子节点仍必须释放
                    recycle(child)
                }
            }
        }

        rec(node, 0)
        if (count >= MAX_NODES) sb.append("\n... 节点过多，已截断 ...")
        return sb.toString()
    }
}
