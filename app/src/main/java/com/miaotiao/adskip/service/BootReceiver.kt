package com.miaotiao.adskip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：重启后系统不会自动恢复无障碍授权，
 * 但保活服务可以自己起来，一旦发现无障碍还开着就一切照常。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        Log.i("AdSkip", "boot completed, starting keep alive")
        KeepAliveService.start(context.applicationContext)
    }
}
