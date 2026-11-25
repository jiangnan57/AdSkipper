package com.miaotiao.adskip.util

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.miaotiao.adskip.BuildConfig

/**
 * 把「运行记录」通过系统分享发出去。
 *
 * 首页和记录页都要这个动作，逻辑只能有一份——之前写在 MainActivity 里，
 * 现在多了一个页面，复制一份就会出现「导出内容不一致」这种最难查的问题。
 *
 * 走 ACTION_SEND 而不是写文件到外部存储：不用申请存储权限，
 * 用户想发到哪就发到哪（微信、邮件、备忘录都行）。
 */
object LogExport {

    fun share(activity: Activity) {
        val lines = SkipLog.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(activity, "还没有记录", Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildString {
            append("秒跳 v").append(BuildConfig.VERSION_NAME).append(" 记录（最新在上，共 ")
            append(lines.size).append(" 条）\n\n")
            lines.forEach { append(it).append('\n') }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "秒跳运行记录")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, "导出记录")) }
            .onFailure {
                Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
    }
}
