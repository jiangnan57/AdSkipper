package com.miaotiao.adskip.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.miaotiao.adskip.R

/**
 * 「问题反馈」：复制现场 → 问一句 → 跳问卷。
 *
 * 为什么不是直接分享到微信：微信的系统分享面板只列好友和群，
 * 对方要先加开发者好友才发得出来——而「装完没用」的人恰恰不可能去加好友。
 * 所以走公开链接，谁都点得开。
 *
 * 为什么先复制再跳：问卷是多行填空，用户到那一步才想起来「记录在哪」就晚了；
 * 而且他多半一个人在手机上，没法一边看 App 一边往回翻。
 * 复制放在跳转之前，顺序不能反过来。
 *
 * 三条不需要新权限、不需要联网：剪贴板是系统服务，问卷在浏览器里打开，
 * App 自己的进程一个字节都不往外发。
 */
object Feedback {

    /**
     * 腾讯问卷地址。
     *
     * 留空时退化成「只复制、不跳转」，主按钮是「知道了」——
     * 这是问卷还没建好时的手动降级，正常发版不该走到这条分支。
     */
    const val QUESTIONNAIRE_URL = "https://wj.qq.com/s2/28866546/pssy/"

    /**
     * 「新规则入库」问卷：学到的弹窗规则通过它回流成公共规则。
     *
     * 和问题反馈分开两份：那边收的是运行日志（多半是「没生效」），
     * 这边收的是 `包名 | when | action`（学会了一条别人还没有的）。
     * 混在一起的话，两边都得先猜这份答卷是哪种。
     */
    const val RULE_SURVEY_URL = "https://wj.qq.com/s2/28867380/8kz9/"

    /** 问卷贴不下上万字，只带最近这些条 */
    private const val RECENT = 30

    private const val CLIP_LABEL = "秒跳诊断"

    fun start(activity: Activity) {
        val text = runCatching { LogExport.compact(activity, RECENT) }.getOrElse {
            toast(activity, R.string.feedback_failed)
            return
        }
        if (!copy(activity, text)) {
            toast(activity, R.string.feedback_failed)
            return
        }
        // 与「导出诊断」同一条路子：logcat 里也留一份。
        // 对方点了反馈却始终没把内容发出来时，这是唯一还能捞到的现场。
        runCatching { Log.i("AdSkip", text.lineSequence().take(20).joinToString("\n")) }

        val ready = QUESTIONNAIRE_URL.isNotBlank()
        AlertDialog.Builder(activity)
            .setTitle(R.string.feedback_title)
            .setMessage(R.string.feedback_msg)
            .setPositiveButton(if (ready) R.string.feedback_go else R.string.feedback_ok) { _, _ ->
                if (ready) open(activity, QUESTIONNAIRE_URL)
            }
            .setNegativeButton(R.string.feedback_cancel, null)
            .show()
    }

    /**
     * 规则已经在剪贴板里了，这里只负责「说清楚下一步」。
     *
     * 不替用户自动跳：问卷是在浏览器/微信里打开的，直接跳走会让他以为 App 自己
     * 把规则上传了——而实际上一个字节都没发，跳不跳全看他点不点。
     */
    fun showRuleSurvey(activity: Activity, count: Int) {
        if (RULE_SURVEY_URL.isBlank()) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.rule_survey_title)
            .setMessage(activity.getString(R.string.rule_survey_msg, count))
            .setPositiveButton(R.string.rule_survey_go) { _, _ -> open(activity, RULE_SURVEY_URL) }
            .setNegativeButton(R.string.rule_survey_ok, null)
            .show()
    }

    /**
     * Android 13 起系统自己会弹「已复制」浮层，再 toast 一次就是重影，
     * 所以只在老系统上补一句。
     */
    private fun copy(activity: Activity, text: String): Boolean {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val ok = runCatching {
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }.isSuccess
        if (ok && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(activity, R.string.feedback_copied)
        }
        return ok
    }

    private fun open(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast(activity, R.string.feedback_no_browser)
        }
    }

    private fun toast(activity: Activity, resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
