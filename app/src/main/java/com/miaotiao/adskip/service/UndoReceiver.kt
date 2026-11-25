package com.miaotiao.adskip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.util.SkipLog

/**
 * 拦截提示上的两个按钮。
 *
 * 只做两件很轻的事，不启动服务、不读窗口：
 *
 *   · **撤销**：把被拦的 App 打开一次 + 该目的地 30 分钟内不再拦。
 *   · **不再提示**：关掉拦截提示本身（防跳转照旧）。
 *
 * ## 为什么撤销只打开首页
 *
 * 无障碍看不到 Intent，我们从来不知道用户原本要去落地页的哪一页。
 * `getLaunchIntentForPackage` 只能拉起启动页。这比「什么都不做」有用，
 * 但文案里不能承诺「回到原来那一页」——那是做不到的事。
 *
 * ## 为什么可以直接 startActivity
 *
 * Android 10+ 限制后台启动 Activity，但**用户点击通知按钮**触发的
 * PendingIntent 属于系统允许的豁免场景。这也意味着：如果哪天改成
 * 自动弹出（不经用户点击），这里会静默失败。
 */
class UndoReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UNDO = "com.miaotiao.adskip.UNDO_INTERCEPT"
        const val ACTION_MUTE = "com.miaotiao.adskip.MUTE_GUARD_NOTICE"
        const val EXTRA_DEST = "dest"
        const val EXTRA_ORIGIN = "origin"

        /**
         * 撤销之后，这个目的地多久不再拦：30 分钟。
         *
         * 比自动熔断（5 分钟）长得多，因为撤销是**用户明确表态**，
         * 不是「被拦了几次」的推断。30 分钟足够他干完手里的事。
         */
        const val UNDO_MUTE_MS = 30 * 60_000L

        private const val TAG = "AdSkip"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ACTION_UNDO -> {
                val dest = intent.getStringExtra(EXTRA_DEST) ?: return
                val origin = intent.getStringExtra(EXTRA_ORIGIN)
                JumpGuard.muteFor(origin, dest, UNDO_MUTE_MS)
                GuardNotice.cancel(ctx)
                val ok = launch(ctx, dest)
                Log.i(TAG, "guard undo dest=$dest origin=$origin launched=$ok")
                SkipLog.add(
                    "用户撤销了拦截：${dest.substringAfterLast('.')}" +
                        if (ok) "（已打开，${UNDO_MUTE_MS / 60_000} 分钟内不再拦）"
                        else "（没能打开，${UNDO_MUTE_MS / 60_000} 分钟内不再拦）"
                )
            }

            ACTION_MUTE -> {
                Prefs.guardNotice = false
                GuardNotice.cancel(ctx)
                SkipLog.add("已关闭拦截提示（防跳转照旧）")
            }
        }
    }

    private fun launch(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }
}
