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

    /**
     * 影子模式：判定照跑，但只记录「本会拦截」，不动手。
     *
     * 为什么要它：防跳转现在的判据是**推断**（来源像不像在放广告），
     * 而对不对只能靠真机数据。可一旦让新判据直接生效，用户就成了小白鼠。
     * 影子模式让新判据先跑一周，用日志里的 `mode=shadow -> BACK` 算误拦率，
     * 再决定要不要真的打开。
     *
     * 默认关。它不是一个「功能」，是一个测量档位。
     */
    var guardShadow: Boolean
        get() = sp?.getBoolean("guard_shadow", false) ?: false
        set(value) = setBool("guard_shadow", value)

    /**
     * 处置档位：`COMPAT`（默认）/ `STRICT` / `SHADOW`。
     *
     * v1.4.0 起判定分成两档证据（HIGH / LOW），`STRICT` 只对 LOW 档
     * 「只提示、不回退」。**默认必须是 COMPAT**——LOW 里本来就有真广告，
     * 一上来默认严格，用户看到的就是「没以前跳得勤」，等于用新问题换旧问题。
     *
     * 翻默认的条件写死，不靠感觉：影子模式连续 7 天，LOW 档里
     * 「拦截后 10 秒无回访」的比例 ≥ 80% 才考虑。
     */
    var guardMode: String
        get() = sp?.getString("guard_mode", "COMPAT") ?: "COMPAT"
        set(value) = setString("guard_mode", value.trim().uppercase())

    /**
     * 拦截后是否弹一条「已拦截，可撤销」的提示。
     *
     * 默认开。它是防跳转判错时用户唯一的出口——没有它，判错的代价就是
     * 「App 打不开」，用户只能反复点、反复被弹。
     *
     * 关掉它不影响防跳转本身，只是少了撤销入口。
     */
    var guardNotice: Boolean
        get() = sp?.getBoolean("guard_notice", true) ?: true
        set(value) = setBool("guard_notice", value)

    /** true = 规则库里没有的 App 也用默认关键词尝试跳过 */
    var skipAllApps: Boolean
        get() = sp?.getBoolean("skip_all", true) ?: true
        set(value) = setBool("skip_all", value)

    // ------------------------------------------------------------------ 实验开关（v1.5）
    //
    // 三个都默认关。它们改的是「广告怎么被阻止」，不是「怎么被补救」，
    // 一旦出问题用户连点都点不动——这种代价不该让所有人承担。

    /**
     * R11 触摸屏蔽层：确认是广告后，用无障碍覆盖层吃掉屏幕上的触摸。
     *
     * 默认关。风险清单见 [com.miaotiao.adskip.service.TouchShield]：
     * 挡自己的注入点击、触发 ROM 的叠加层检测、和手势导航冲突。
     */
    var expShield: Boolean
        get() = sp?.getBoolean("exp_shield", false) ?: false
        set(value) = setBool("exp_shield", value)

    /**
     * R12 传感器前置：冷启动窗口里监听摇一摇，在落地之前把广告点掉。
     *
     * 默认关。只有「看到广告指纹 + 检测到摇动」才会动手，误摇不会触发。
     */
    var expShake: Boolean
        get() = sp?.getBoolean("exp_shake", false) ?: false
        set(value) = setBool("exp_shake", value)

    /**
     * R13 目的地入口证据：比对首帧 Activity 和 launcher Activity，只记日志。
     *
     * 默认关是因为它不影响行为，开着只是多几个 IPC。想验证信号强弱时再开。
     */
    var expEntry: Boolean
        get() = sp?.getBoolean("exp_entry", false) ?: false
        set(value) = setBool("exp_entry", value)

    /** 规则没命中时，是否允许用启发式识别一个候选并记住它 */
    /**
     * 规则没命中时自己认按钮并记住。
     *
     * 默认改成 true：界面上已经没有这个开关了，再默认关等于让新用户
     * 直接少了「规则没覆盖的 App 也能跳」这条能力。
     * 学习只发生在自识别真的点中之后，且只学控件 id / 干净短文本，
     * 不学坐标（见 LearnStore），风险可控。
     */
    var autoLearn: Boolean
        get() = sp?.getBoolean("auto_learn", true) ?: true
        set(value) = setBool("auto_learn", value)

    /**
     * 失联自愈：无障碍授权还在、服务却连不上时，重启进程让系统重新绑定。
     *
     * 默认开。只在「连续几分钟都连不上」时才动手，平时完全不影响。
     */
    var autoHeal: Boolean
        get() = sp?.getBoolean("auto_heal", true) ?: true
        set(value) = setBool("auto_heal", value)

    /** 上次自愈的时间点（elapsedRealtime，跨进程重启有效），用于冷却 */
    var lastHealAt: Long
        get() = sp?.getLong("last_heal_at", 0L) ?: 0L
        // 必须 commit 而不是 apply：写完这一行就要杀进程，
        // apply 是异步落盘，进程一死就丢了，冷却失效会变成反复重启。
        set(value) {
            sp?.edit()?.putLong("last_heal_at", value)?.commit()
        }

    /**
     * 定时重启间隔（小时）。0 表示关闭。
     *
     * 为什么需要它：进程连续跑几天之后，节点回收、点击记录表、规则缓存这些
     * 内存状态会一点点累积，表现是「跳过越来越慢、甚至干脆不跳了」。
     * 重启一次进程（约 2 秒）就全部归零，比逐项去修省事得多。
     *
     * 6 小时是折中：一天最多重启两三次，用户几乎察觉不到；
     * 又能保证「用久了变慢」不会真的拖到用户发现。
     */
    var restartHours: Int
        get() = sp?.getInt("restart_hours", 6) ?: 6
        set(value) = setInt("restart_hours", value)

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

    private fun setInt(key: String, value: Int) {
        val editor = sp?.edit() ?: return
        editor.putInt(key, value)
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
