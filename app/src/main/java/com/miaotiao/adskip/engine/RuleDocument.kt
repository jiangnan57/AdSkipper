package com.miaotiao.adskip.engine

import org.json.JSONArray
import org.json.JSONObject

/** 统一识别「秒跳对象格式」和「李跳跳哈希数组格式」。 */
object RuleDocument {

    data class Stats(
        val records: Int,
        val splashApps: Int,
        val popupApps: Int
    )

    fun parse(raw: String): Any {
        val clean = cleanJson(raw)
        return if (clean.startsWith('[')) JSONArray(clean) else JSONObject(clean)
    }

    fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(cleanJson(value)) }.getOrNull()
        else -> null
    }

    fun stats(raw: String): Stats {
        when (val doc = parse(raw)) {
            is JSONObject -> {
                val apps = doc.optJSONObject("apps")
                    ?: throw IllegalArgumentException("缺少 apps 对象")
                var splash = 0
                var popup = 0
                for (pkg in apps.keys()) {
                    val app = apps.optJSONObject(pkg) ?: continue
                    if (hasSplash(app)) splash++
                    if (hasPopups(app)) popup++
                }
                // 自己的库里还夹着一层「上游快照」（legacy），按李跳跳哈希数组存的。
                // 不算进去的话，界面上会显示「共 19 条」，用户以为库缩水了。
                val legacy = countArray(doc.optJSONArray("legacy"))
                if (apps.length() == 0 && legacy.records == 0) {
                    throw IllegalArgumentException("apps 是空对象")
                }
                return Stats(
                    apps.length() + legacy.records,
                    splash + legacy.splashApps,
                    popup + legacy.popupApps
                )
            }
            is JSONArray -> {
                val s = countArray(doc)
                if (s.records == 0) throw IllegalArgumentException("数组里没有可识别的应用规则")
                return s
            }
            else -> throw IllegalArgumentException("不支持的规则格式")
        }
    }

    private fun countArray(doc: JSONArray?): Stats {
        if (doc == null) return Stats(0, 0, 0)
        var valid = 0
        var splash = 0
        var popup = 0
        for (i in 0 until doc.length()) {
            val entry = doc.optJSONObject(i) ?: continue
            for (key in entry.keys()) {
                val app = objectValue(entry.opt(key)) ?: continue
                valid++
                if (hasSplash(app)) splash++
                if (hasPopups(app)) popup++
            }
        }
        return Stats(valid, splash, popup)
    }

    private fun hasSplash(app: JSONObject): Boolean {
        val source = app.optJSONObject("splash") ?: app
        return source.optJSONArray("keywords") != null ||
            source.optJSONArray("keywords_append") != null
    }

    private fun hasPopups(app: JSONObject): Boolean =
        app.optJSONArray("popups") != null || app.optJSONArray("popup_rules") != null

    /**
     * 社区 AllRules / BasicRules 中存在 `,]`、`,}`，标准 JSON 解析器会直接失败。
     * 只在字符串外移除尾随逗号，不改动规则文本里的逗号。
     */
    fun cleanJson(raw: String): String {
        val src = raw.removePrefix("\uFEFF")
        val out = StringBuilder(src.length)
        var inString = false
        var escaped = false
        for (i in src.indices) {
            val c = src[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                }
                ',' -> {
                    var j = i + 1
                    while (j < src.length && src[j].isWhitespace()) j++
                    if (j >= src.length || (src[j] != ']' && src[j] != '}')) out.append(c)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
