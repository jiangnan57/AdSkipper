package com.miaotiao.adskip.data

import android.content.Context
import com.miaotiao.adskip.R
import com.miaotiao.adskip.engine.AppRule
import com.miaotiao.adskip.engine.PopupRule
import com.miaotiao.adskip.engine.RuleDocument
import com.miaotiao.adskip.engine.RuleSet
import com.miaotiao.adskip.engine.SplashRule
import com.miaotiao.adskip.net.RemoteRules
import com.miaotiao.adskip.util.SkipLog
import org.json.JSONArray
import org.json.JSONObject

/** 内置 → 远程 → 自动学习 → 自定义，后者覆盖前者。 */
object RuleRepository {

    fun builtinJson(ctx: Context): String =
        ctx.resources.openRawResource(R.raw.default_rules).bufferedReader().use { it.readText() }

    fun load(ctx: Context): RuleSet {
        val defaults = Defaults()
        val builders = LinkedHashMap<String, AppBuilder>()

        // 老版本可能把「坐标 / 关闭」学成了规则，加载前先清一遍，
        // 否则包一升级照样会乱点
        LearnStore.sanitize()

        applyLayer(ctx, builtinJson(ctx), "内置", builders, defaults)
        if (Prefs.remoteEnabled) {
            RemoteRules.cachedJson(ctx)?.let { applyLayer(ctx, it, "远程", builders, defaults) }
        }
        Prefs.learnedRules.takeIf { it.isNotBlank() }
            ?.let { applyLayer(ctx, it, "自动学习", builders, defaults) }
        Prefs.customRules.takeIf { it.isNotBlank() }
            ?.let { applyLayer(ctx, it, "自定义", builders, defaults) }

        val apps = builders.mapValues { (_, b) -> build(b, defaults) }
        val generic = AppRule(
            pkg = "*",
            name = "通用",
            splash = SplashRule(
                keywords = defaults.keywords.distinct(),
                excludes = defaults.excludes,
                delayMs = defaults.delay,
                durationMs = defaults.duration,
                intervalMs = defaults.interval
            ),
            popups = emptyList(),
            origin = "默认"
        )
        return RuleSet(apps, generic)
    }

    private fun applyLayer(
        ctx: Context,
        raw: String?,
        origin: String,
        builders: MutableMap<String, AppBuilder>,
        defaults: Defaults
    ) {
        if (raw.isNullOrBlank()) return
        val doc = runCatching { RuleDocument.parse(raw) }.getOrElse {
            SkipLog.add("$origin 规则解析失败，已跳过：${it.javaClass.simpleName}")
            return
        }

        when (doc) {
            is JSONObject -> applyObject(doc, origin, builders, defaults)
            is JSONArray -> applyHashArray(ctx, doc, origin, builders, defaults)
        }
    }

    private fun applyObject(
        root: JSONObject,
        origin: String,
        builders: MutableMap<String, AppBuilder>,
        defaults: Defaults
    ) {
        root.optJSONObject("defaults")?.let { defaults.merge(it) }
        val apps = root.optJSONObject("apps") ?: return
        for (pkg in apps.keys()) {
            if (pkg.isBlank() || pkg.length > 200) continue
            val obj = apps.optJSONObject(pkg) ?: continue
            val b = builders.getOrPut(pkg) { AppBuilder(pkg) }
            applyJson(obj, b)
            b.origin = origin
        }
    }

    /**
     * 李跳跳格式：[{"包名hashCode":"{\"popup_rules\":...}"}, ...]
     * 通过本机已安装包名计算同样的 Java String.hashCode 来找回包名。
     * 未安装的应用不会建立规则对象，326 条远程规则不会变成 326 份常驻内存。
     */
    private fun applyHashArray(
        ctx: Context,
        array: JSONArray,
        origin: String,
        builders: MutableMap<String, AppBuilder>,
        defaults: Defaults
    ) {
        val byHash = HashMap<Int, String>()
        runCatching {
            ctx.packageManager.getInstalledPackages(0).forEach {
                byHash.putIfAbsent(it.packageName.hashCode(), it.packageName)
            }
        }

        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            for (hash in entry.keys()) {
                val app = RuleDocument.objectValue(entry.opt(hash)) ?: continue
                val explicit = app.optString("packageName").trim()
                val pkg = explicit.ifBlank { byHash[hash.toIntOrNull()] } ?: continue
                val b = builders.getOrPut(pkg) { AppBuilder(pkg) }
                applyJson(app, b)
                b.origin = origin
            }
        }
        // defaults 也可能由数组来源提供，但李跳跳数组没有 defaults，这里无需处理
    }

    private fun applyJson(obj: JSONObject, b: AppBuilder) {
        obj.optString("name").takeIf { it.isNotBlank() }?.let { b.name = it.take(60) }
        val source = obj.optJSONObject("splash") ?: obj
        source.optJSONArray("keywords")?.let { b.keywords = toStringList(it) }
        source.optJSONArray("keywords_append")?.let { b.append.addAll(toStringList(it)) }
        source.optString("action").takeIf { it.isNotBlank() }?.let { b.action = it }
        if (source.has("delay")) b.delay = source.optLong("delay", 200L)
        if (source.has("duration")) b.duration = source.optLong("duration", 4200L)
        if (source.has("interval")) b.interval = source.optLong("interval", 130L)

        val popups = obj.optJSONArray("popups") ?: obj.optJSONArray("popup_rules")
        if (popups != null) {
            for (i in 0 until minOf(popups.length(), 40)) {
                popups.optJSONObject(i)?.let { b.popups.add(it) }
            }
        }
    }

    private fun build(b: AppBuilder, d: Defaults): AppRule {
        val base = b.keywords ?: d.keywords
        // 含中文的关键词排前面：文本检索在 WebView 上慢，
        // 预算用完后只做 id 检索，中文词才是最常命中的那种。
        val keywords = (base + b.append).map { it.trim() }
            .filter { it.isNotEmpty() }.distinct().take(80)
            .sortedBy { if (it.any { c -> c.code > 127 }) 0 else 1 }
        val splash = SplashRule(
            keywords = keywords,
            excludes = d.excludes,
            action = if (b.action.equals("back", true)) "back" else "click",
            delayMs = (b.delay ?: d.delay).coerceIn(0L, 2000L),
            durationMs = (b.duration ?: d.duration).coerceIn(800L, 10_000L),
            intervalMs = (b.interval ?: d.interval).coerceIn(100L, 500L)
        )
        val popups = b.popups.mapNotNull { parsePopup(it) }
            .distinctBy { it.whenAll.joinToString("&") }.take(40)
        return AppRule(b.pkg, b.name, splash, popups, b.origin)
    }

    private fun parsePopup(o: JSONObject): PopupRule? {
        val whens = o.optJSONArray("when")?.let { toStringList(it) }
            ?: o.optString("id").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: return null
        val rawActions = o.optJSONArray("action")?.let { toStringList(it) }
            ?: o.optString("action").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: return null

        val globalBack = rawActions.any { it.equals("GLOBAL_ACTION_BACK", true) }
        val actions = rawActions.filterNot { it.equals("GLOBAL_ACTION_BACK", true) }
        if (whens.isEmpty() || (actions.isEmpty() && !globalBack)) return null
        return PopupRule(
            whens,
            actions,
            o.optLong("cooldown", 8000L).coerceIn(1500L, 60_000L),
            globalBack
        )
    }

    private class AppBuilder(val pkg: String) {
        var name: String = ""
        var keywords: List<String>? = null
        val append = ArrayList<String>()
        var action: String = "click"
        var delay: Long? = null
        var duration: Long? = null
        var interval: Long? = null
        val popups = ArrayList<JSONObject>()
        var origin: String = ""
    }

    private class Defaults {
        var keywords: List<String> = listOf(
            "跳过", "关闭广告", "跳过广告", "Skip", "skip",
            "关闭开屏", "跳过开屏", "点击跳过"
        )
        var excludes: List<String> = listOf(
            "跳过登录", "跳过教程", "跳过新手", "跳过绑定", "跳过设置", "跳过实名",
            "跳过引导", "跳过此步", "跳过验证", "跳过介绍", "跳过说明"
        )
        var delay: Long = 200L
        var duration: Long = 4200L
        var interval: Long = 130L

        fun merge(o: JSONObject) {
            o.optJSONArray("keywords")?.let { keywords = toStringList(it) }
            o.optJSONArray("exclude")?.let { excludes = toStringList(it) }
            if (o.has("delay")) delay = o.optLong("delay", delay).coerceIn(0L, 2000L)
            if (o.has("duration")) duration = o.optLong("duration", duration).coerceIn(800L, 10_000L)
            if (o.has("interval")) interval = o.optLong("interval", interval).coerceIn(100L, 500L)
        }
    }
}

private fun toStringList(a: JSONArray): List<String> {
    val out = ArrayList<String>(minOf(a.length(), 80))
    for (i in 0 until minOf(a.length(), 80)) {
        val s = a.optString(i).trim().take(160)
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}
