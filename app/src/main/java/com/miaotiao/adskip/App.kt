package com.miaotiao.adskip

import android.app.Application
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.service.KeepAliveService
import com.miaotiao.adskip.util.SkipLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // 越早越好：出事那一瞬间的记录必须能落盘，进程被杀也不丢。
        // 之前只在内存里，用户报问题时 logcat 缓冲早滚掉了，无从取证。
        SkipLog.install(this)
        // 前台保活：让系统回收后台时优先保活本进程，
        // 并且能自检无障碍授权是否被「一键清理」撤销。
        KeepAliveService.start(this)
    }
}
