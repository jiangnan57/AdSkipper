package com.miaotiao.adskip

import android.app.Application
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.service.KeepAliveService
import com.miaotiao.adskip.util.SkipLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 必须最先记：定时重启的判据是「本进程已经运行了多久」，
        // 越晚记，累计时长就越短，重启窗口会被无限往后推。
        processStartAt = android.os.SystemClock.elapsedRealtime()
        Prefs.init(this)
        // 越早越好：出事那一瞬间的记录必须能落盘，进程被杀也不丢。
        // 之前只在内存里，用户报问题时 logcat 缓冲早滚掉了，无从取证。
        SkipLog.install(this)
        // 前台保活：让系统回收后台时优先保活本进程，
        // 并且能自检无障碍授权是否被「一键清理」撤销。
        KeepAliveService.start(this)
    }

    companion object {
        /**
         * 本进程启动的时刻（elapsedRealtime）。
         *
         * 用 elapsedRealtime 而不是 System.currentTimeMillis：前者含深度睡眠，
         * 后者会被用户改时间影响。手机放着过一夜，进程其实也「老」了一夜，
         * 该重启就重启，不该因为休眠而不算数。
         */
        @Volatile
        var processStartAt = 0L
            private set
    }
}
