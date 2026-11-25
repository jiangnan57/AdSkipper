package com.miaotiao.adskip.engine

/**
 * 广告 SDK 指纹（v1.4.0 的 R5）。
 *
 * ## 它解决什么
 *
 * 判定链现在的证据全在**时序**上：来源冷启动、没进正片、落地很快。
 * 这三条凑齐的可能是真摇一摇，也可能是用户手快。指纹是第一条
 * 「屏幕上确实有广告 SDK」的正面证据。
 *
 * ## 为什么 v1.4.0 只记日志
 *
 * 指纹表是拍脑袋写的。让它在真机上跑一周，看 `adfp=true` 的拦截里
 * 有多少被撤销，再决定要不要让它参与确认。现在就让它决定拦不拦，
 * 等于把一个没校准过的信号直接塞进判定链——这个项目已经在同类错误上
 * 栽过四次。
 *
 * ## 匹配规则（踩过的坑）
 *
 * 报告原文给的是 `"ad_"` 这种裸子串。它会命中 `download_`、`loading_`、
 * `badge_`——几乎每个 App 都有。所以：
 *
 *   · SDK 类名用**包名前缀**（`com.bytedance.sdk`），命中率低但不会误伤；
 *   · 控件 id 用**分段边界**（`/tt_splash`、`:id/ksad`），不裸匹配。
 *
 * 这个文件不许 import android.*，要能在 JVM 单测里跑。
 */
object AdFingerprint {

    /** 广告 SDK 的类名/包名前缀。只要出现就基本上可以确定是广告容器 */
    private val SDK_PREFIX = listOf(
        "com.bytedance.sdk",   // 穿山甲 / Pangle
        "com.qq.e",            // 优量汇（广点通）
        "com.kwad",            // 快手联盟
        "com.baidu.mobads",    // 百度联盟
        "com.sigmob"           // Sigmob
    )

    /**
     * 已知广告控件的 id 前缀。
     *
     * 前面必须是 `/`、`:`、`.` 或字符串开头 —— viewIdResourceName 的形态是
     * `com.foo:id/tt_splash_skip` 或 `com.foo/.ui.Splash: id/ksad_skip`。
     * 加了这个边界，`download_btn` 里的 `load_` 不会被误判。
     */
    private val ID_RE = Regex(
        "(?i)(^|[/:.])(tt_splash|ksad|gdt_|pangle_|csj_|bd_ad|mimo_)"
    )

    /**
     * 这段文本是不是广告 SDK 的痕迹。
     *
     * @param s viewIdResourceName 或 className；null/空直接 false
     */
    fun hit(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        for (p in SDK_PREFIX) {
            if (s.startsWith(p) || s.contains(".$p.") || s.contains(":$p")) return true
        }
        return ID_RE.containsMatchIn(s)
    }

    /**
     * 一批节点里有没有广告痕迹。调用方一次遍历顺手调，零额外 IPC。
     */
    fun hitAny(vararg values: String?): Boolean {
        for (v in values) if (hit(v)) return true
        return false
    }
}
