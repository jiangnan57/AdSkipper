package com.miaotiao.adskip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.miaotiao.adskip.App
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.ui.MainActivity
import com.miaotiao.adskip.util.AccessUtil
import com.miaotiao.adskip.util.SkipLog

/**
 * 保活 + 自检服务。
 *
 * 为什么需要它：HyperOS / MIUI 的「一键清理后台」本质是 force-stop，
 * 会把 enabled_accessibility_services 直接清空、accessibility_enabled 置 0，
 * 无障碍服务被系统彻底注销——不是进程被杀，是**授权被撤销**。
 * 实测：force-stop 之后 dumpsys accessibility 里 Bound services 和
 * Enabled services 都是空的，settings 里 enabled_accessibility_services = null。
 * 这时候用户看到的是「App 还在、图标还在，但什么都不跳了」，毫无线索。
 *
 * 这个服务做三件事：
 * 1. 前台服务常驻，让系统回收后台时优先保活本进程（用户手动清理仍会失效）
 * 2. 定时自检无障碍是否还活着，一旦发现被撤权就弹通知提醒用户重开
 * 3. 开机自启，重新拉起保活服务
 *
 * 注意：被 force-stop 后本进程也没了，前台服务无法对抗「一键清理」——
 * 这是系统设计，任何应用都做不到。所以真正兜底的是第 2 条：
 * 用户下次打开 App 时能立刻看到「无障碍未开启」。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkIntervalMs = 60_000L

    /** 上一次自检的时间点，用来发现「进程被系统冻结过」 */
    private var lastTick = 0L

    /** 「系统里授权还在、服务却没连上」连续出现了几轮（每轮 60 秒） */
    private var unconnectedTicks = 0

    private val checker = object : Runnable {
        override fun run() {
            runCatching { checkFrozen() }
            runCatching { checkAndNotify() }
            runCatching { checkLongRun() }
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    /**
     * 定时重启：连续跑够时长就重启进程，把内存里累积的状态全部清掉。
     *
     * 用户报的现象是「用久了跳过变慢、甚至干脆不跳」。这类慢性退化没有单一
     * 断点，而是进程内一堆状态在累积：节点引用、点击记录表、规则解析结果、
     * 日志队列……逐项去查太贵，重启一次（约 2 秒）全部归零，性价比最高。
     *
     * 和 [trySelfHeal] 的分工：
     *   · 定时重启是**计划性**的，不管有没有病都做，治「慢性累积」；
     *   · 症状自愈是**应急**的，只在确认失联时才做，治「急性失效」。
     * 两者共用 lastHealAt 冷却，避免同一时间各杀一次。
     *
     * 用 elapsedRealtime 而不是 uptime：它含深度睡眠，手机放一夜也算运行时长。
     * 这正是想要的——休眠期间进程同样在老化，不该因为没亮屏就不算数。
     */
    private fun checkLongRun() {
        val hours = Prefs.restartHours
        if (hours <= 0) return
        val start = App.processStartAt
        if (start <= 0L) return
        val ran = android.os.SystemClock.elapsedRealtime() - start
        if (ran < hours * 60L * 60_000L) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = Prefs.lastHealAt
        // 兜底冷却：正常情况重启后 processStartAt 归零、不会连续触发，
        // 但万一「杀不掉 / 系统立刻拉起」就靠它拦住重启风暴。
        if (last > 0L && now - last < HEAL_COOLDOWN_MS) return
        Prefs.lastHealAt = now
        SkipLog.add("已连续运行 ${ran / 3_600_000} 小时，按计划重启清空内存状态")
        Log.w(TAG, "scheduled restart, ran ${ran / 60_000} min")
        // 顺手清磁盘缓存。规则和记录都在 filesDir，不受影响。
        runCatching {
            cacheDir.listFiles()?.forEach { f -> runCatching { f.deleteRecursively() } }
        }
        handler.postDelayed(
            { android.os.Process.killProcess(android.os.Process.myPid()) },
            500L
        )
    }

    /**
     * 冻结检测。
     *
     * 这个 Runnable 每 60 秒跑一次。如果两次之间隔了两三分钟，说明进程在中间
     * 被系统挂起过（Freezer），那段时间里无障碍事件全部处理不了、
     * 也不会跳过任何广告——但界面上的「无障碍已开启」依然是绿的，
     * 用户完全看不出发生了什么。这里把它写进日志，至少能留下线索。
     */
    private fun checkFrozen() {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = if (lastTick > 0L) now - lastTick else 0L
        lastTick = now
        if (gap > checkIntervalMs * 3) {
            SkipLog.add("后台被系统冻结 ${gap / 1000}s（建议开启通知权限 + 电池白名单）")
            Log.w(TAG, "process was frozen for ${gap}ms")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        // Android 14+ 会校验 foregroundServiceType 与声明的权限是否匹配，
        // 部分 ROM 在通知被用户关掉时也会让 startForeground 抛 SecurityException。
        // 在 onCreate 里裸抛 = 服务反复崩溃重启，把整个进程一起拖下水，
        // 反而连无障碍主功能都没了。保活失败只影响后台优先级，降级记日志即可。
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
            .onFailure {
                Log.w(TAG, "startForeground failed: ${it.message}")
                SkipLog.add("保活通知启动失败（不影响跳过，但后台更容易被冻结）")
            }
        handler.postDelayed(checker, checkIntervalMs)
        Log.i(TAG, "keep alive started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：进程被回收后系统会重新拉起
        if (intent == null) {
            runCatching { startForeground(NOTIF_ID, buildNotification()) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checker)
        super.onDestroy()
    }

    /**
     * 自检：服务实例为空且系统授权里也没有 → 说明被系统撤权了，通知用户。
     */
    private fun checkAndNotify() {
        val connected = SkipAccessibilityService.instance != null
        val enabled = AccessUtil.isServiceEnabled(this)
        if (connected) {
            unconnectedTicks = 0
            // 恢复正常，把提醒撤掉
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIF_WARN_ID)
            }
            return
        }
        if (enabled) {
            // 系统设置里授权还在、进程里却没有服务实例——这正是
            // 「无障碍明明开着，一个广告都不跳」的形态（进程被冻结、
            // 系统停止派发、或授权后没重新绑定）。只看 isServiceEnabled
            // 会把它当成健康状态，首页也一直显示绿字，用户毫无头绪。
            // 连续两轮（约 2 分钟）才记一次，避开服务正在重启的短暂窗口。
            if (++unconnectedTicks == 2) {
                SkipLog.add("无障碍已授权但服务未连接：关掉开关再打开一次，并检查省电策略")
                Log.w(TAG, "accessibility enabled but service not bound")
            }
            trySelfHeal()
            return
        }
        unconnectedTicks = 0
        // 被撤权了
        Prefs.markServiceLost()
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_WARN_ID, buildWarningNotification())
        }
        Log.w(TAG, "accessibility service lost (force-stop?), user notified")
    }

    /**
     * 失联自愈：重启进程，让系统重新绑定无障碍服务。
     *
     * 为什么不做「定时重启」：
     *   · 冻结期间自检的 Handler 本身也被挂起，定时器不跑——最需要它的场景它偏偏不工作；
     *   · 不管有没有病都重启，重启那一两秒正在播的广告就漏了，纯亏。
     *
     * 改成症状触发：只有「授权还在、服务却连续 3 轮（约 3 分钟）连不上」才动手。
     * 这个状态就是日志里「无障碍明明开着，一个广告都不跳」的形态，平时不会出现。
     *
     * 冷却 15 分钟：重启过一次还是连不上，说明不是进程状态的问题（多半是
     * 系统把无障碍彻底禁用了），再杀进程只会变成重启循环，此时改为提示用户。
     */
    private fun trySelfHeal() {
        if (!Prefs.autoHeal) return
        if (unconnectedTicks < HEAL_AFTER_TICKS) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = Prefs.lastHealAt
        if (last > 0L && now - last < HEAL_COOLDOWN_MS) {
            // 只提示一次，别每轮刷屏
            if (unconnectedTicks == HEAL_AFTER_TICKS + 2) {
                SkipLog.add("自动恢复无效，请手动关掉无障碍开关再打开")
            }
            return
        }
        SkipLog.add("无障碍失联约 ${unconnectedTicks} 分钟，自动重启进程恢复")
        requestHeal(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // 重要度不能用 IMPORTANCE_MIN。
        //
        // 实测 HyperOS / Android 16：通知权限被拒 + 通知重要度最低的组合下，
        // 系统会把本进程的 UID 状态判成 CEM（cached empty），然后在后台
        // 十几秒内冻结它——进程还在、无障碍也显示「已绑定」，但窗口事件
        // 处理不了、定时任务也不跑，表现就是「无障碍明明开着，什么广告都不跳」，
        // 而一打开自检悬浮球（overlay 窗口让进程变可见）就立刻恢复。
        // 用 LOW 保证通知真的显示出来，前台服务才会被计入进程优先级。
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
            nm.createNotificationChannel(ch)
        }

        // 「无障碍被撤权」的告警必须用单独的渠道，而且必须建出来。
        //
        // 之前这里漏了创建，只用了一个从未注册过的 channel id 去 notify()：
        // Android 8+ 遇到不存在的渠道会**直接丢弃这条通知**并只打一行
        // 「No Channel found」的 log。结果是「被一键清理后台」这个最需要提醒
        // 用户的场景，提醒永远收不到——用户只能看到“App 还在但什么都不跳”。
        if (nm.getNotificationChannel(CHANNEL_WARN_ID) == null) {
            val warn = NotificationChannel(
                CHANNEL_WARN_ID,
                getString(R.string.keep_alive_channel_warn),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            warn.setShowBadge(true)
            nm.createNotificationChannel(warn)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keep_alive_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private fun buildWarningNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_WARN_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.service_lost_title))
            .setContentText(getString(R.string.service_lost_text))
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(getString(R.string.service_lost_text))
            )
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val TAG = "AdSkip"
        /** 连续几轮「授权在、服务未连」才自愈（每轮 60 秒） */
        private const val HEAL_AFTER_TICKS = 3
        /** 自愈冷却：15 分钟内最多重启一次，避免重启循环 */
        private const val HEAL_COOLDOWN_MS = 15 * 60_000L
        /** 改过重要度必须换 channel id，Android 不允许原地修改已建好的渠道 */
        private const val CHANNEL_ID = "adskip_keep_alive_v2"
        private const val CHANNEL_WARN_ID = "adskip_warn"
        private const val NOTIF_ID = 1001
        private const val NOTIF_WARN_ID = 1002

        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start keep alive failed: ${it.message}") }
        }

        /**
         * 由无障碍服务主动请求自愈。
         *
         * 服务比自检更早发现问题：它一旦连续 60 秒拿不到活动窗口，就知道自己
         * 哑了（见 SkipAccessibilityService.noteRootFail），不必等自检每分钟
         * 一次的轮询、也不用连续 3 轮才动手。
         *
         * 冷却在这里统一判断：两条触发路径（自检 / 服务上报）共享同一个
         * lastHealAt，避免两边各杀一次。
         */
        fun requestHeal(ctx: Context) {
            if (!Prefs.autoHeal) return
            val now = android.os.SystemClock.elapsedRealtime()
            val last = Prefs.lastHealAt
            if (last > 0L && now - last < HEAL_COOLDOWN_MS) {
                Log.w(TAG, "heal requested but cooling down")
                return
            }
            Prefs.lastHealAt = now // 同步写盘，必须在杀进程之前落地
            Log.w(TAG, "heal requested by accessibility service")
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 500L)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) }
        }
    }
}
