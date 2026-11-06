package com.miaotiao.adskip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 规则匹配核心。
 *
 * 文字匹配默认「包含」，支持四个修饰符：
 *   +xxx  必须以 xxx 开头
 *   -xxx  必须以 xxx 结尾
 *   =xxx  必须完全等于
 *   a&b   多个条件同时满足（且）
 *
 * 另外支持坐标写法 "900,160,1170,250"：当关键词长这样时，按屏幕区域命中。
 *
 * 性能说明：v2 开始把关键词预编译成 Keyword/Pred，匹配时直接吃 CharSequence，
 * 不再为每个节点拼 List<String>、也不在每次匹配时 split('&')。
 * 这是从「扫描一次几十毫秒」降到「几毫秒」的关键。
 */
object Matcher {

    private val COORD = Regex("""^-?\d+\s*,\s*-?\d+\s*,\s*-?\d+\s*,\s*-?\d+$""")

    /** 单个条件，例如 "+跳过" 里的 "+" 和 "跳过" */
    class Pred(val op: Char, val text: String) {
        fun test(s: CharSequence): Boolean = when (op) {
            '+' -> s.startsWith(text)
            '-' -> s.endsWith(text)
            '=' -> s.contentEquals(text)
            else -> (if (ASCII_WORD.matches(text)) containsWord(s, text) else s.contains(text)) ||
                looseContains(s, text)
        }
    }

    /**
     * 纯英文关键词。这类词必须按「词边界」匹配，不能直接 contains。
     *
     * 真实事故：QQ 里「QQ邮箱提醒」的一条 GitHub Actions 通知，正文写着
     * `[jiangnan57/AdSkipper] Run failed`，规则里的关键字 `Skip` 直接 contains
     * 命中了 `AdSkip` 里的 `Skip` → 秒跳把这条**聊天消息**当成跳过按钮点了下去，
     * 于是用户打开 QQ 就跳进邮件详情 / QQ 邮箱，还回不来。
     *
     * 中文关键词不存在这个问题（「跳过」不会嵌在别的词里），所以只对纯英文加边界。
     */
    private val ASCII_WORD = Regex("""^[A-Za-z][A-Za-z ]*$""")

    /** 按词边界找 t：前后都不能紧贴字母/数字 */
    private fun containsWord(s: CharSequence, t: String): Boolean {
        if (s.length < t.length) return false
        var from = 0
        while (true) {
            val i = s.indexOf(t, from)
            if (i < 0) return false
            val before = if (i == 0) null else s[i - 1]
            val after = if (i + t.length >= s.length) null else s[i + t.length]
            val okBefore = before == null || !before.isLetterOrDigit()
            val okAfter = after == null || !after.isLetterOrDigit()
            if (okBefore && okAfter) return true
            from = i + 1
        }
    }

    /**
     * 容忍「跳 过」「Skip Ad」这类中间夹空白的写法：
     * 有些广告为了排版会把字距拉开，节点文本里就带上了空格。
     *
     * 只多出 1~4 个字符时才尝试（长度差先卡死），
     * 也就是说绝大多数不匹配的节点在第一行就返回了，不会拖慢扫描。
     */
    private fun looseContains(s: CharSequence, t: String): Boolean {
        if (s.length <= t.length || s.length > t.length + 4) return false
        val compact = StringBuilder(s.length)
        for (i in 0 until s.length) {
            val c = s[i]
            if (c != ' ' && c != '\t' && c != '\u00A0' && c != '\u3000') compact.append(c)
        }
        // 没有真的去掉空白，说明长度差来自其它字符，不是我们要的情况
        if (compact.length == s.length) return false
        return compact.contains(t)
    }

    /** 预编译后的关键词，raw 保留原写法用于日志与规则回写 */
    class Keyword(val raw: String, val preds: List<Pred>) {
        fun hit(s: CharSequence): Boolean {
            for (i in preds.indices) {
                if (!preds[i].test(s)) return false
            }
            return true
        }
    }

    fun isCoord(expr: String): Boolean = COORD.matches(expr.trim())

    fun coordRect(expr: String): Rect? {
        val p = expr.split(',')
        if (p.size != 4) return null
        val v = p.map { it.trim().toIntOrNull() ?: return null }
        if (v[2] <= v[0] || v[3] <= v[1]) return null
        return Rect(v[0], v[1], v[2], v[3])
    }

    // ------------------------------------------------------------------ 编译

    fun compile(exprs: List<String>): List<Keyword> {
        val out = ArrayList<Keyword>(exprs.size)
        for (e in exprs) compileOne(e)?.let { out.add(it) }
        return out
    }

    fun compileOne(raw: String): Keyword? {
        val e = raw.trim()
        if (e.isEmpty() || isCoord(e)) return null
        val parts = e.split('&')
        val preds = ArrayList<Pred>(parts.size)
        for (p0 in parts) {
            val p = p0.trim()
            if (p.isEmpty()) continue
            // 李跳跳部分规则用 | 标记点击方式；这里按普通文本匹配，仍只执行安全点击
            if (p.length > 1 && p[0] == '|') {
                val body = p.substring(1).trim()
                if (body.isNotEmpty()) preds.add(Pred('*', body))
            } else if (p.length > 1 && (p[0] == '+' || p[0] == '-' || p[0] == '=')) {
                preds.add(Pred(p[0], p.substring(1)))
            } else {
                preds.add(Pred('*', p))
            }
        }
        if (preds.isEmpty()) return null
        return Keyword(e, preds)
    }

    // ------------------------------------------------------------------ 匹配

    /** 对单个节点的 text / desc / viewId 做一遍预编译关键词匹配，零额外分配 */
    fun hitAny(kws: List<Keyword>, node: AccessibilityNodeInfo): Boolean {
        if (kws.isEmpty()) return false
        val t = node.text
        val d = node.contentDescription
        val v = node.viewIdResourceName
        for (i in kws.indices) {
            val k = kws[i]
            if (t != null && k.hit(t)) return true
            if (d != null && k.hit(d)) return true
            if (v != null && k.hit(v)) return true
        }
        return false
    }

    /** 单条预编译规则匹配，避免弹窗热点路径里反复 listOf 分配。 */
    fun hit(keyword: Keyword, node: AccessibilityNodeInfo): Boolean {
        val t = node.text
        if (t != null && keyword.hit(t)) return true
        val d = node.contentDescription
        if (d != null && keyword.hit(d)) return true
        val v = node.viewIdResourceName
        return v != null && keyword.hit(v)
    }

    /** 命中任意一个关键词时返回它，用于日志里显示「是被哪条规则点掉的」 */
    fun firstHit(kws: List<Keyword>, node: AccessibilityNodeInfo): Keyword? {
        val t = node.text
        val d = node.contentDescription
        val v = node.viewIdResourceName
        for (i in kws.indices) {
            val k = kws[i]
            if (t != null && k.hit(t)) return k
            if (d != null && k.hit(d)) return k
            if (v != null && k.hit(v)) return k
        }
        return null
    }

    fun hitAnyString(kws: List<Keyword>, s: CharSequence?): Boolean {
        if (s == null || kws.isEmpty()) return false
        for (i in kws.indices) if (kws[i].hit(s)) return true
        return false
    }

    // ------------------------------------------------------------------ 兼容旧接口

    fun matchSingle(raw: String, target: String): Boolean {
        val p = raw.trim()
        if (p.isEmpty()) return false
        val op = p[0]
        if (p.length > 1 && (op == '+' || op == '-' || op == '=')) {
            val body = p.substring(1)
            return when (op) {
                '+' -> target.startsWith(body)
                '-' -> target.endsWith(body)
                else -> target == body
            }
        }
        return target.contains(p)
    }

    fun matchGroup(expr: String, target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val k = compileOne(expr) ?: return false
        return k.hit(target)
    }

    fun labels(node: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(3)
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /** 单个关键词（或坐标）是否命中某个节点 */
    fun matchNode(expr: String, node: AccessibilityNodeInfo): Boolean {
        val e = expr.trim()
        if (e.isEmpty()) return false

        if (isCoord(e)) {
            val area = coordRect(e) ?: return false
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return false
            if (area.contains(r.centerX(), r.centerY())) return true
            return area.contains(r.left, r.top) && area.contains(r.right, r.bottom)
        }

        val k = compileOne(e) ?: return false
        return hitAny(listOf(k), node)
    }

    /** 是否命中排除词（防止点到「跳过登录」这类按钮） */
    fun excluded(labels: List<String>, excludes: List<String>): Boolean {
        if (excludes.isEmpty() || labels.isEmpty()) return false
        val kws = compile(excludes)
        return labels.any { l -> hitAnyString(kws, l) }
    }

    fun excludedNode(kws: List<Keyword>, node: AccessibilityNodeInfo): Boolean =
        hitAny(kws, node)
}
