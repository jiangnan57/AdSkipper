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
 * 就是一次**明确的负反馈**：同一对 (来源, 目的地) 在 30 分钟内不再拦。
 *
 * ## v1.4.0：IMPORTANCE_LOW 是个错误
 *
 * v1.3.11 用的是 `IMPORTANCE_LOW`，理由很正当——工具提示不该打扰人。
 * 但 2026-11-25 在 HyperOS 4.0.3（Android 17）上实测：
 *
 *   1. LOW 的通知被系统归进 `Aggregate_SilentSection`；
 *   2. 通知栏里它和保活通知被**合并成一个分组**，只显示标题+正文；
 *   3. `uiautomator dump` 的节点树里**根本没有「撤销」和「不再提示」**——
 *      不是被遮住，是压根没渲染；
 *   4. 也就是说这个功能上线后，用户一次都点不到。
 *
 * 所以改成 `IMPORTANCE_DEFAULT` + `setSound(null, null)` + 关振动：
 * 不进静默分组、不出横幅、不出声，但按钮会被真的画出来。渠道 id 必须
 * 跟着改成 v2 —— Android 不允许原地修改已建好渠道的重要度。
 *
 * 另外加了第二道保险：**点通知本体 = 撤销**。这样即使某个 ROM 仍然
 * 折叠按钮，用户也还有一个能用的入口（正文里写明了「点此撤销」）。
 *
 * ## 三条硬约束
 *
 * 1. **渠道必须真的建出来**。Android 8+ 往一个不存在的渠道发通知会
 *    直接丢弃，只打一行 "No Channel found"。
 * 2. **无声、不出横幅、不上锁屏**。它是个工具提示，不该打断任何事。
 * 3. **15 秒后自动消失**，而且所有提示共用一个 id —— 连着拦三次只留一条，
 *    不能变成通知轰炸。
 */
object GuardNotice {

    private const val TAG = "AdSkip"

    /** 改过重要度就必须换 id：Android 不允许原地修改已建好的渠道 */
    private const val CHANNEL_ID = "adskip_guard_v2"

    /** 拦截提示共用一个 id：新的一条直接替换旧的 */
    private const val NOTICE_ID = 0x1A2B

    /** 严格档的「已保留」提示用另一个 id，避免把撤销那条顶掉 */
    private const val SKIPPED_ID = 0x1A2C

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

        // 点通知本体也走撤销：按钮被 ROM 折叠时的唯一活路。
        val undoPi = pendingUndo(ctx, dest, origin)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.guard_notice_title))
            .setContentText(text)
            // 判定原因放在展开后：标题要短，但排查时这几个字最有用
            .setStyle(Notification.BigTextStyle().bigText("$text\n$reason"))
            .setContentIntent(undoPi)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.guard_notice_undo), undoPi).build())
            .addAction(buildMuteAction(ctx))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        runCatching { nm.notify(NOTICE_ID, n) }
            .onFailure { Log.w(TAG, "guard notice failed", it) }
    }

    /**
     * 严格档：判定该拦、但证据弱，所以**没有**回退。
     *
     * 只提示「刚才有一次没拦」，不提供撤销——撤销的语义是「我不该被拦」，
     * 而这里本来就没拦。两个按钮只会让用户以为发生了两次不同的事。
     */
    fun showSkipped(ctx: Context, dest: String, origin: String?, reason: String) {
        if (!Prefs.guardNotice || !Prefs.guardJump) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { createChannel(ctx, nm) }

        val destName = nameOf(ctx, dest)
        val originName = origin?.let { nameOf(ctx, it) }
        val text = if (originName != null) {
            ctx.getString(R.string.guard_skipped_text, destName, originName)
        } else {
            ctx.getString(R.string.guard_skipped_text_noorigin, destName)
        }

        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.guard_skipped_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n$reason"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        runCatching { nm.notify(SKIPPED_ID, n) }
            .onFailure { Log.w(TAG, "guard skipped notice failed", it) }
    }

    /** 用户撤销之后把提示撤掉，别留一条点不动的旧通知 */
    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching {
            nm.cancel(NOTICE_ID)
            nm.cancel(SKIPPED_ID)
        }
    }

    private fun pendingUndo(ctx: Context, dest: String, origin: String?): PendingIntent {
        val intent = Intent(ctx, UndoReceiver::class.java).apply {
            action = UndoReceiver.ACTION_UNDO
            putExtra(UndoReceiver.EXTRA_DEST, dest)
            putExtra(UndoReceiver.EXTRA_ORIGIN, origin)
        }
        return PendingIntent.getBroadcast(
            ctx, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
            // DEFAULT 而不是 LOW：LOW 会被 Android 17 归进「静默通知」分组，
            // 分组里的子通知不渲染 actions —— 撤销按钮等于不存在（见文件头）。
            // 用 DEFAULT + 关声音/振动，效果上仍然是「安静地出现」。
            NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.setSound(null, null)
        ch.enableVibration(false)
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
