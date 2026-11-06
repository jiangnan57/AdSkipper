package com.miaotiao.adskip

import android.app.Application
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.service.KeepAliveService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // 前台保活：让系统回收后台时优先保活本进程，
        // 并且能自检无障碍授权是否被「一键清理」撤销。
        KeepAliveService.start(this)
    }
}
