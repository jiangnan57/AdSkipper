package com.miaotiao.adskip.util

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.miaotiao.adskip.BuildConfig

/**
 * 「导出诊断」——把现场环境 + 运行记录拼成一段文本，交给系统分享。
 *
 * 首页和记录页都要这个动作，逻辑只能有一份——之前写在 MainActivity 里，
 * 现在多了两个入口，复制一份就会出现「导出内容不一致」这种最难查的问题。
 *
 * 走 ACTION_SEND 而不是写文件到外部存储：不用申请存储权限，
 * 用户想发到哪就发到哪（微信、邮件、备忘录都行）。
 *
 * 也不再「没有记录就不让导出」：**一条记录都没有本身就是结论**
 * （服务从没连上过），恰恰是最需要看到的那一种现场。
 */
object LogExport {

    /** 分享主题，方便对方在微信/邮件里一眼认出这是什么 */
    fun subject(): String = "秒跳诊断 v" + BuildConfig.VERSION_NAME

    fun build(activity: Activity): String {
        val head = Diagnostics.build(activity)
        val lines = SkipLog.snapshot()
        return buildString {
            append(head)
            append('\n')
            append("--- 运行记录（最新在上，共 ").append(lines.size).append(" 条）---\n")
            if (lines.isEmpty()) {
                append("（一条记录都没有）\n")
            } else {
                lines.forEach { append(it).append('\n') }
            }
            append('\n')
            append("把以上内容整段发给开发者即可，不含任何账号信息。")
        }
    }

    /**
     * 反馈用：头部照旧，记录只带最近 [limit] 条。
     *
     * 完整记录可能有上万字，微信聊天里会被折叠、问卷的多行填空也不适合贴那么长，
     * 而排查「昨天那次为什么没跳」根本用不到第 180 条。
     */
    fun compact(activity: Activity, limit: Int): String =
        composeCompact(Diagnostics.build(activity), SkipLog.snapshot(), limit)

    /**
     * 拼装逻辑做成纯函数，好让单测钉住「截断了有没有说清总数」。
     * 一旦这里悄悄少写一句，拿到手的诊断就分不清是「只有 30 条」还是「被截了」。
     */
    internal fun composeCompact(head: String, lines: List<String>, limit: Int): String =
        buildString {
            append(head).append('\n')
            val shown = lines.take(limit.coerceAtLeast(0))
            append("--- 运行记录（最新在最上，共 ").append(lines.size).append(" 条")
            if (shown.size < lines.size) {
                append("，这里只带最近 ").append(shown.size).append(" 条")
            }
            append("）---\n")
            if (shown.isEmpty()) {
                append("（一条记录都没有）\n")
            } else {
                shown.forEach { append(it).append('\n') }
            }
        }

    fun share(activity: Activity) {
        val text = runCatching { build(activity) }.getOrElse {
            Toast.makeText(activity, "生成诊断失败", Toast.LENGTH_SHORT).show()
            return
        }
        // 同步写一条 logcat：用户点了导出但没发出去（或者发的渠道我们收不到）时，
        // 只要人还在电脑旁，`adb logcat -s AdSkip` 就能拿到同一份现场
        runCatching { Log.i("AdSkip", text.lineSequence().take(20).joinToString("\n")) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject())
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, "导出诊断")) }
            .onFailure {
                Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
    }
}
