package com.miaotiao.adskip.data

import com.miaotiao.adskip.engine.PopupTeacher

/**
 * 「学弹窗」教出来的规则，按行存成纯文本。
 *
 * 为什么不直接存 JSON：
 *
 *   1. JVM 单测里 android.jar 的 org.json 是个空壳，方法一调用就抛
 *      `Method put in org.json.JSONArray not mocked`。存 JSON 的话，
 *      去重、上限、转义这些最容易出错的逻辑一行都测不了。
 *   2. 出问题时用户能在「规则管理」里直接看这几行，比看嵌套 JSON 快得多。
 *
 * 转成引擎要的 JSON 只在加载规则时做一次（见 [toJson]），且不用 org.json，
 * 靠字符串拼装 + 转义完成——这样它同样在单测覆盖范围内。
 *
 * 行格式（字段之间用制表符，界面文字里不会出现制表符）：
 *
 *     包名 \t 应用名 \t when 条件 \t action 目标 \t cooldown 毫秒
 *
 * 例：
 *
 *     com.xingin.xhs	小红书	会员特惠	id/iv_close	8000
 */
object TaughtRulesStore {

    private const val SEP = '\t'

    /** 单 App 上限，跟 RuleRepository 里的 take(40) 对齐 */
    const val MAX_RULES_PER_APP = 40

    /** 总应用数上限，避免 SharedPreferences 里堆成一座山 */
    const val MAX_APPS = 300

    class Row(
        val pkg: String,
        val name: String,
        val whenText: String,
        val actionText: String,
        val cooldownMs: Long
    )

    // ------------------------------------------------------------------ 读

    fun rows(raw: String): List<Row> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Row>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val f = line.split(SEP)
            if (f.size < 4) continue
            if (f[0].isBlank() || f[2].isBlank() || f[3].isBlank()) continue
            val cd = f.getOrNull(4)?.trim()?.toLongOrNull() ?: 8000L
            out.add(Row(f[0], f[1], f[2], f[3], cd.coerceIn(1500L, 60_000L)))
        }
        return out
    }

    fun count(raw: String): Int = rows(raw).size

    fun appCount(raw: String): Int = rows(raw).map { it.pkg }.distinct().size

    // ------------------------------------------------------------------ 写

    /**
     * 追加一条。返回新的整段文本；返回 null 表示「不该写」，调用方据此提示：
     *   - 已经有完全相同的规则（同一个 App、同样的 when 和 action）
     *   - 这个 App 已经 40 条了
     *   - 应用总数已经 300 个
     *   - 字段里混进了制表符 / 换行（会把行格式撑坏）
     */
    fun add(raw: String, pkg: String, appName: String, rule: PopupTeacher.Rule): String? {
        if (pkg.isBlank() || pkg.length > 200) return null
        if (!safeField(pkg) || !safeField(rule.whenText) || !safeField(rule.actionText)) return null
        if (appName.length > 60) return null

        val list = rows(raw).toMutableList()
        val dup = list.any {
            it.pkg == pkg && it.whenText == rule.whenText && it.actionText == rule.actionText
        }
        if (dup) return null

        val sameApp = list.count { it.pkg == pkg }
        if (sameApp >= MAX_RULES_PER_APP) return null

        val apps = list.map { it.pkg }.distinct()
        if (pkg !in apps && apps.size >= MAX_APPS) return null

        list.add(Row(pkg, clean(appName), rule.whenText, rule.actionText, rule.cooldownMs))
        return render(list)
    }

    fun removeWhere(raw: String, predicate: (Row) -> Boolean): String =
        render(rows(raw).filterNot(predicate))

    // ------------------------------------------------------------------ 转成引擎要的 JSON

    /**
     * 行文本 → `{"apps":{...}}`，喂给 RuleRepository。
     *
     * 没有可用的行时返回 null，调用方据此跳过这一层。
     */
    fun toJson(raw: String): String? {
        val list = rows(raw)
        if (list.isEmpty()) return null

        val byPkg = LinkedHashMap<String, MutableList<Row>>()
        for (r in list) byPkg.getOrPut(r.pkg) { ArrayList() }.add(r)

        val sb = StringBuilder(256)
        sb.append("{\"apps\":{")
        var firstApp = true
        for ((pkg, rules) in byPkg) {
            if (!firstApp) sb.append(',')
            firstApp = false
            sb.append(quote(pkg)).append(":{")

            val name = rules.firstOrNull { it.name.isNotBlank() }?.name.orEmpty()
            if (name.isNotBlank()) sb.append("\"name\":").append(quote(name)).append(',')

            sb.append("\"popups\":[")
            rules.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.append("{\"when\":[").append(quote(r.whenText)).append("],")
                sb.append("\"action\":[").append(quote(r.actionText)).append("],")
                sb.append("\"cooldown\":").append(r.cooldownMs).append('}')
            }
            // 关掉 popups 数组和这个应用的对象；apps 和根对象在循环外统一关
            sb.append("]}")
        }
        sb.append("}}")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 内部

    private fun render(list: List<Row>): String =
        list.joinToString("\n") {
            "${it.pkg}$SEP${it.name}$SEP${it.whenText}$SEP${it.actionText}$SEP${it.cooldownMs}"
        }

    /** 制表符和换行会把行格式撑散，出现即拒绝 */
    private fun safeField(s: String): Boolean =
        s.none { it == '\t' || it == '\n' || it == '\r' }

    private fun clean(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
