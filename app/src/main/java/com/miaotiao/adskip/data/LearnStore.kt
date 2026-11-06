package com.miaotiao.adskip.data

import com.miaotiao.adskip.engine.Matcher
import com.miaotiao.adskip.engine.SafeWords
import com.miaotiao.adskip.engine.SplashScanner
import com.miaotiao.adskip.util.SkipLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动学习：规则没命中、由启发式猜中的按钮，记下来变成一条正式规则。
 *
 * 优先级上它低于用户手写规则（合并顺序：内置 → 远程 → 自动学习 → 自定义），
 * 所以学坏了随时可以在「规则管理」里清掉，或者直接关掉「自动学习」开关。
 *
 * 只学两种东西：
 *   1. 控件 id（最稳，跨版本还能用）
 *   2. 文字里带「跳过」的干净短文本
 *
 * 坐标学习已经彻底删掉。原因是它会造成「每次启动都点同一个位置」：
 * 曾经在某次误判时把「番茄的点赞」「DeepSeek 的新对话」学成了坐标规则，
 * 之后每次打开 App 都在 650ms 时无条件点那个点，看起来就像 App 自己发疯。
 * 坐标是「这一刻这台机器上的一个位置」，它的语义在别的页面完全不成立。
 */
object LearnStore {

    private const val MAX_APPS = 300

    fun learnedAppCount(): Int =
        runCatching { JSONObject(Prefs.learnedRules).optJSONObject("apps")?.length() ?: 0 }
            .getOrDefault(0)

    /**
     * 清理历史脏数据：丢掉所有「纯坐标」和「通用界面词」的学习结果。
     * 每次加载规则时跑一遍，老版本学坏的东西会自动消失，用户不用手动清空。
     */
    fun sanitize(): Int {
        val raw = Prefs.learnedRules
        if (raw.isBlank()) return 0
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        val apps = root.optJSONObject("apps") ?: return 0

        var removed = 0
        val pkgs = apps.keys().asSequence().toList()
        for (pkg in pkgs) {
            val app = apps.optJSONObject(pkg) ?: continue
            val splash = app.optJSONObject("splash") ?: continue
            val arr = splash.optJSONArray("keywords_append") ?: continue

            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isEmpty()) continue
                if (Matcher.isCoord(s) || SafeWords.isUnsafe(s)) {
                    removed++
                    continue
                }
                keep.put(s)
            }
            splash.put("keywords_append", keep)
        }

        if (removed > 0) {
            Prefs.learnedRules = root.toString()
            SkipLog.add("已清理 $removed 条历史学习（坐标 / 通用词）")
        }
        return removed
    }

    fun learn(pkg: String, appName: String, cand: SplashScanner.Candidate): String? {
        val keyword = keywordFor(cand) ?: return null

        val root = runCatching {
            JSONObject(Prefs.learnedRules.ifBlank { "{\"apps\":{}}" })
        }.getOrElse { JSONObject() }

        val apps = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
        if (apps.length() >= MAX_APPS && !apps.has(pkg)) return null

        val app = apps.optJSONObject(pkg) ?: JSONObject().also { apps.put(pkg, it) }
        if (appName.isNotBlank()) app.put("name", appName)

        val splash = app.optJSONObject("splash") ?: JSONObject().also { app.put("splash", it) }
        val arr = splash.optJSONArray("keywords_append") ?: JSONArray().also {
            splash.put("keywords_append", it)
        }

        for (i in 0 until arr.length()) {
            if (arr.optString(i) == keyword) return null
        }
        arr.put(keyword)

        Prefs.learnedRules = root.toString()
        SkipLog.add("自动学习 $pkg → $keyword")
        return keyword
    }

    fun clear() {
        Prefs.learnedRules = ""
    }

    private fun keywordFor(cand: SplashScanner.Candidate): String? {
        val node = cand.node

        // 1. 控件 id：跳过 "android:id/" 这种残缺值
        node.viewIdResourceName?.takeIf { it.contains('/') && it.length < 96 }?.let { return it }

        // 2. 文字：只学「短、干净、不是倒计时、不是通用界面词」的
        //    带标点的长句多半是广告文案或频道标签（比如「双11,未选中」），学了就是灾难
        val text = node.text?.toString()?.trim().orEmpty()
        val clean = text.isNotEmpty() &&
            text.length <= 6 &&
            text.none { it.isDigit() || it in "，,。.、|/\\:：;；!！ " } &&
            SafeWords.isLearnable(text)
        if (clean) {
            Matcher.compileOne(text)?.let { return it.raw }
        }

        // 3. 坐标兜底已移除。
        //    坐标规则会在每次冷启动 650ms 时无条件点那个位置，一旦学错
        //    （点到了内容页的「分类 / 点赞」），就成了「每次打开都被点一下」。
        //    宁可这个 App 学不到，也不能让它反复误点。
        return null
    }
}
