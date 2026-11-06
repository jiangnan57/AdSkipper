package com.miaotiao.adskip.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量配置。所有读写都做了空值兜底，避免服务比 Application 更早拉起时崩溃。
 */
object Prefs {

    private const val FILE = "adskip_prefs"
    const val DEFAULT_REMOTE_URL =
        "https://testingcf.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/BasicRules.json"
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        // 开发联调地址绝不能带给朋友；升级后自动迁回公共规则源
        val url = sp?.getString("remote_url", DEFAULT_REMOTE_URL).orEmpty()
        if (url.contains("127.0.0.1") || url.contains("localhost") || url.contains("10.0.2.2")) {
            sp?.edit()
                ?.putString("remote_url", DEFAULT_REMOTE_URL)
                ?.putString("remote_etag", "")
                ?.putString("remote_etag_url", "")
                ?.apply()
        }
    }

    var masterEnabled: Boolean
        get() = sp?.getBoolean("master", true) ?: true
        set(value) = setBool("master", value)

    var skipSplash: Boolean
        get() = sp?.getBoolean("skip_splash", true) ?: true
        set(value) = setBool("skip_splash", value)

    var closePopups: Boolean
        get() = sp?.getBoolean("close_popups", true) ?: true
        set(value) = setBool("close_popups", value)

    var guardJump: Boolean
        get() = sp?.getBoolean("guard_jump", true) ?: true
        set(value) = setBool("guard_jump", value)

    /** true = 规则库里没有的 App 也用默认关键词尝试跳过 */
    var skipAllApps: Boolean
        get() = sp?.getBoolean("skip_all", true) ?: true
        set(value) = setBool("skip_all", value)

    /** 规则没命中时，是否允许用启发式识别一个候选并记住它 */
    var autoLearn: Boolean
        get() = sp?.getBoolean("auto_learn", false) ?: false
        set(value) = setBool("auto_learn", value)

    // ------------------------------------------------------------------ 规则来源

    /** 远程规则地址，为空表示不使用 */
    var remoteUrl: String
        get() = sp?.getString("remote_url", DEFAULT_REMOTE_URL) ?: DEFAULT_REMOTE_URL
        set(value) = setString("remote_url", value.trim())

    /** 是否允许缓存里的远程规则参与匹配 */
    var remoteEnabled: Boolean
        get() = sp?.getBoolean("remote_enabled", true) ?: true
        set(value) = setBool("remote_enabled", value)

    var autoSync: Boolean
        get() = sp?.getBoolean("auto_sync", true) ?: true
        set(value) = setBool("auto_sync", value)

    var lastSyncAt: Long
        get() = sp?.getLong("last_sync_at", 0L) ?: 0L
        set(value) {
            val e = sp?.edit() ?: return
            e.putLong("last_sync_at", value)
            e.apply()
        }

    var lastSyncInfo: String
        get() = sp?.getString("last_sync_info", "") ?: ""
        set(value) = setString("last_sync_info", value)

    /** 上次同步拿到的 ETag，用于协商缓存 */
    var remoteEtag: String
        get() = sp?.getString("remote_etag", "") ?: ""
        set(value) = setString("remote_etag", value)

    /** ETag 只对生成它的那个 URL 有效，换源后必须重新下载 */
    var remoteEtagUrl: String
        get() = sp?.getString("remote_etag_url", "") ?: ""
        set(value) = setString("remote_etag_url", value)

    // ------------------------------------------------------------------ 规则内容

    var customRules: String
        get() = sp?.getString("custom_rules", "") ?: ""
        set(value) = setString("custom_rules", value)

    /** 自动学习出来的规则，优先级低于用户手写规则 */
    var learnedRules: String
        get() = sp?.getString("learned_rules", "") ?: ""
        set(value) = setString("learned_rules", value)

    /** 额外需要拦截的包名，换行 / 逗号 / 空格分隔 */
    var guardBlocklist: String
        get() = sp?.getString("guard_blocklist", "") ?: ""
        set(value) = setString("guard_blocklist", value)

    // ------------------------------------------------------------------ 统计

    private fun setBool(key: String, value: Boolean) {
        val editor = sp?.edit() ?: return
        editor.putBoolean(key, value)
        editor.apply()
    }

    private fun setString(key: String, value: String) {
        val editor = sp?.edit() ?: return
        editor.putString(key, value)
        editor.apply()
    }

    private fun todayKey(): String =
        "count_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    /**
     * 计数是 read-modify-write，必须加锁。
     * 目前的调用点都在扫描线程上，但「自检面板」和「悬浮球」也会走同一批代码，
     * 一旦哪天有人从主线程调一次，丢计数会变成「今天跳过 0 次」这种假象。
     */
    @Synchronized
    fun bumpSkip(n: Int = 1) {
        val s = sp ?: return
        val key = todayKey()
        s.edit().putInt(key, s.getInt(key, 0) + n).apply()
    }

    fun todaySkipCount(): Int = sp?.getInt(todayKey(), 0) ?: 0

    /**
     * 记录「无障碍授权被系统撤销」这件事。
     * 用户点了「一键清理后台」之后授权就没了，这个标记用来在首页给出醒目提示，
     * 否则用户只会觉得「App 还在但什么都不跳了」。
     */
    fun markServiceLost() {
        val s = sp ?: return
        s.edit().putBoolean("service_lost", true).apply()
    }

    fun clearServiceLost() {
        val s = sp ?: return
        s.edit().putBoolean("service_lost", false).apply()
    }

    var serviceLost: Boolean
        get() = sp?.getBoolean("service_lost", false) ?: false
        set(value) {
            val s = sp ?: return
            s.edit().putBoolean("service_lost", value).apply()
        }
}
