package com.miaotiao.adskip.engine

import android.graphics.Rect

/** 开屏广告规则 */
data class SplashRule(
    val keywords: List<String>,
    val excludes: List<String>,
    val action: String = "click",
    val delayMs: Long = 200L,
    val durationMs: Long = 4200L,
    val intervalMs: Long = 130L
) {
    /**
     * 预编译，避免每次扫描都重新拆分字符串。
     *
     * 必须用 SYNCHRONIZED：同一个 SplashRule 会被两个线程同时读——
     * 扫描线程（adskip-scan）和「自检面板」（主线程 / 悬浮球的读取线程）。
     * 原来的 LazyThreadSafetyMode.NONE 只保证单线程安全，第二个线程可能读到
     * 尚未发布完成的列表（JMM 下 value 字段非 volatile），表现出来是
     * 偶发 NullPointerException 或「自检面板说没找到、实际能跳过」这种怪现象。
     * 预编译只在首次访问时执行一次，加锁的代价可以忽略。
     */
    val kw: List<Matcher.Keyword> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Matcher.compile(keywords) }
    val ex: List<Matcher.Keyword> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Matcher.compile(excludes) }
    val coords: List<Rect> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        keywords.mapNotNull { if (Matcher.isCoord(it)) Matcher.coordRect(it) else null }
    }

    val isActionable: Boolean
        get() = kw.isNotEmpty() || coords.isNotEmpty() || action.equals("back", true)

    companion object {
        val EMPTY = SplashRule(emptyList(), emptyList())
    }
}

/** 应用内弹窗规则：满足 whenAll 全部条件时，点击 actions 里的某个按钮 */
data class PopupRule(
    val whenAll: List<String>,
    val actions: List<String>,
    val cooldownMs: Long = 8000L,
    /** 李跳跳规则里的 GLOBAL_ACTION_BACK */
    val globalBack: Boolean = false
) {
    // 与 SplashRule 同理：会被扫描线程和自检线程并发读，不能用 NONE。
    val whenKw: List<Matcher.Keyword> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Matcher.compile(whenAll) }
    val actionKw: List<Matcher.Keyword> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Matcher.compile(actions) }
}

data class AppRule(
    val pkg: String,
    val name: String,
    val splash: SplashRule,
    val popups: List<PopupRule>,
    /** 这条规则来自哪里：内置 / 远程 / 自动学习 / 自定义，仅用于展示 */
    val origin: String = ""
)

data class RuleSet(
    /** 规则库中显式配置过的应用 */
    val apps: Map<String, AppRule>,
    /** 兜底规则，作用于所有未配置的应用 */
    val generic: AppRule
) {
    companion object {
        val EMPTY = RuleSet(
            emptyMap(),
            AppRule(
                "*", "通用",
                SplashRule(listOf("跳过", "关闭广告"), emptyList()),
                emptyList()
            )
        )
    }
}
