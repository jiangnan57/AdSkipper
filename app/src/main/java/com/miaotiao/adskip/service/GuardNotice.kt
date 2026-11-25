package com.miaotiao.adskip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.Prefs

/**
 * 「已拦截一次广告跳转」的提示，带一个**撤销**按钮。
 *
 * ## 为什么要做这个出口
 *
 * 防跳转本质是**事后补救**：它看到「来源像广告 → 落地很快」就按返回，
 * 但看不到用户到底点了什么。判错的代价是用户正在打开的 App 被弹走，
 * 而他能做的只有再点一次——如果又判错，就再弹一次。
 *
 * 有了撤销，判错的代价从「打不开」变成「点一下按钮」，而且撤销本身
 * 就是一次**明确的负反馈**：同一个目的地在 30 分钟内不再拦（见
 * [JumpGuard.muteFor]）。这比任何名字补丁都准——它记的是用户意图。
 *
 * ## 三条硬约束
 *
 * 1. **渠道必须真的建出来**。Android 8+ 往一个不存在的渠道发通知会
 *    直接丢弃，只打一行 "No Channel found"。这个坑在保活通知上已经踩过一次
 *    （见 KeepAliveService.createChannel）。
 * 2. **IMPORTANCE_LOW**：无声、不横幅、不上锁屏。它是个工具提示，
 *    不该打断任何事。
 * 3. **15 秒后自动消失**，而且所有提示共用一个 id —— 连着拦三次只留一条，
 *    不能变成通知轰炸。
 */
object GuardNotice {

    private const val TAG = "AdSkip"

    /** 改过重要度/行为就必须换 id：Android 不允许原地修改已建好的渠道 */
    private const val CHANNEL_ID = "adskip_guard_v1"

    /** 所有拦截提示共用一个 id：新的一条直接替换旧的 */
    private const val NOTICE_ID = 0x1A2B

    /** 15 秒后自动消失。撤销是个「立刻」的动作，留太久只会堆在通知栏里 */
    private const val TIMEOUT_MS = 15_000L

    /**
     * 弹一条「已拦截…」。
     *
     * @param dest 被拦的落地页包名
     * @param origin 来源包名（可能为 null）
     * @param reason 判定原因，原样写进正文——用户反馈时能直接截图
     */
    fun show(ctx: Context, dest: String, origin: String?, reason: String) {
        // 用户关掉了提示，或者防跳转本身关着：什么都不做
        if (!Prefs.guardNotice || !Prefs.guardJump) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { createChannel(ctx, nm) }

        val destName = nameOf(ctx, dest)
        val originName = origin?.let { nameOf(ctx, it) }
        val text = if (originName != null) {
            ctx.getString(R.string.guard_notice_text, destName, originName)
        } else {
            ctx.getString(R.string.guard_notice_text_noorigin, destName)
        }

        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.guard_notice_title))
            .setContentText(text)
            // 判定原因放在展开后：标题要短，但排查时这几个字最有用
            .setStyle(Notification.BigTextStyle().bigText("$text\n$reason"))
            .addAction(buildUndoAction(ctx, dest, origin))
            .addAction(buildMuteAction(ctx))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        runCatching { nm.notify(NOTICE_ID, n) }
            .onFailure { Log.w(TAG, "guard notice failed", it) }
    }

    /** 用户撤销之后把提示撤掉，别留一条点不动的旧通知 */
    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { nm.cancel(NOTICE_ID) }
    }

    private fun buildUndoAction(ctx: Context, dest: String, origin: String?): Notification.Action {
        val intent = Intent(ctx, UndoReceiver::class.java).apply {
            action = UndoReceiver.ACTION_UNDO
            putExtra(UndoReceiver.EXTRA_DEST, dest)
            putExtra(UndoReceiver.EXTRA_ORIGIN, origin)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, ctx.getString(R.string.guard_notice_undo), pi).build()
    }

    private fun buildMuteAction(ctx: Context): Notification.Action {
        val intent = Intent(ctx, UndoReceiver::class.java).apply {
            action = UndoReceiver.ACTION_MUTE
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, ctx.getString(R.string.guard_notice_mute), pi).build()
    }

    private fun createChannel(ctx: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.guard_notice_channel),
            // LOW：无声、不抬头、不上锁屏。它是提示，不是消息
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
        nm.createNotificationChannel(ch)
    }

    /** 包名换成应用名给用户看；查不到就退回包名最后一段 */
    private fun nameOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))
}
