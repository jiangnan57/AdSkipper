package com.miaotiao.adskip.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.data.TaughtRulesStore
import com.miaotiao.adskip.engine.RuleDocument
import com.miaotiao.adskip.net.RemoteRules
import com.miaotiao.adskip.service.SkipAccessibilityService
import com.miaotiao.adskip.util.Feedback
import org.json.JSONObject

class RulesActivity : AppCompatActivity() {

    private lateinit var etRules: EditText
    private lateinit var tvHint: TextView
    private lateinit var tvTaught: TextView
    private lateinit var tvLib: TextView
    private lateinit var tvLibMsg: TextView
    private lateinit var btnLibSync: MaterialButton
    private lateinit var btnTaughtClear: MaterialButton
    private lateinit var btnTaughtSend: MaterialButton

    /** 手动同步进行中：按钮已经置灰，这里再兜一层，防止连点发出两串请求 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        etRules = findViewById(R.id.etRules)
        tvHint = findViewById(R.id.tvHint)
        tvTaught = findViewById(R.id.tvTaught)
        tvLib = findViewById(R.id.tvLib)
        tvLibMsg = findViewById(R.id.tvLibMsg)
        btnLibSync = findViewById(R.id.btnLibSync)
        btnTaughtClear = findViewById(R.id.btnTaughtClear)
        btnTaughtSend = findViewById(R.id.btnTaughtSend)

        etRules.setText(Prefs.customRules)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnValidate).setOnClickListener {
            tvHint.text = validate(etRules.text.toString())
        }
        findViewById<MaterialButton>(R.id.btnTemplate).setOnClickListener {
            etRules.setText(TEMPLATE.trim())
            tvHint.text = "模板已填入，改成你自己的包名和关键词后保存"
        }

        btnLibSync.setOnClickListener { manualSync() }
        // 换库地址是极低频操作，藏成长按；但状态行里写了「长按换地址」，不用猜
        findViewById<android.view.View>(R.id.cardLib).setOnLongClickListener {
            showSourceDialog()
            true
        }

        tvTaught.setOnClickListener { showTaught() }
        btnTaughtClear.setOnClickListener { confirmClearTaught() }
        btnTaughtSend.setOnClickListener { sendAll() }
        refreshTaught()
        refreshLib()
    }

    // ------------------------------------------------------------------ 规则库

    private fun refreshLib() {
        // 读的是 4MB 的 rules.json：解一次 JSON 在低端机上要一两百毫秒，
        // 放在 onCreate 里做就是「点进规则管理先白屏一下」。
        tvLib.text = "读取中…"
        Thread {
            val meta = RemoteRules.cachedMeta(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyLib(meta)
            }
        }.start()
    }

    private fun applyLib(meta: RemoteRules.Meta?) {
        // 只留「版本 / 条数 / 更新日期」三样。加一行「上次拉取时间」会挤到第三行，
        // 第三行一换行，卡片高度就跳，看一眼像界面坏了。
        tvLib.text = when {
            meta == null ->
                "还没下载过，现在用的是内置规则\n长按这里可以换库地址"
            else -> buildString {
                if (meta.version > 0) append("v${meta.version} · ")
                append("${meta.records} 条规则")
                append("\n")
                if (meta.updatedAt.isNotBlank()) append("${meta.updatedAt} 更新 · ")
                append("长按换地址")
            }
        }
    }

    /**
     * 手动获取最新规则。
     *
     * force = true：不走 ETag，也不让 CDN 回旧副本。
     * 用户点这个按钮的前提就是「我刚改了库，现在要看到」，
     * 拿一句「已是最新」把他挡回去，比同步失败还让人恼火。
     */
    private fun primaryHost(): String =
        runCatching { java.net.URL(Prefs.DEFAULT_REMOTE_URL).host }.getOrDefault("")

    private fun manualSync() {
        if (syncing) return
        val url = Prefs.remoteUrl
        if (url.isBlank()) {
            showSourceDialog()
            return
        }
        syncing = true
        btnLibSync.isEnabled = false
        btnLibSync.text = "获取中…"
        tvHint.text = ""
        tvLibMsg.visibility = android.view.View.VISIBLE
        tvLibMsg.text = "正在拉取…"

        Thread {
            val ctx = applicationContext
            // 版本号也在这里算：cachedMeta 要解 4MB JSON，放主线程和同步本身一样卡
            val before = RemoteRules.cachedMeta(ctx)?.version ?: 0
            val r = RemoteRules.sync(ctx, url, force = true)
            val afterMeta = if (r.ok) RemoteRules.cachedMeta(ctx) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                syncing = false
                btnLibSync.isEnabled = true
                btnLibSync.text = "获取最新规则"
                // 失败时不动卡片：同步失败不代表本地那份没了，重画成「还没下载过」只会更误导
                if (r.ok) applyLib(afterMeta) else refreshLib()
                if (r.ok) {
                    // 不 reload 的话，新规则要等下一次服务重启才生效
                    SkipAccessibilityService.instance?.reloadRules()
                    val after = afterMeta?.version ?: 0
                    val upgrade = if (before > 0 && after > before) "（v$before → v$after）" else ""
                    // 不是默认那条线就说出来：用户排查「为什么版本不对」时，
                    // 第一件事就是确认这次到底从哪儿拉的
                    val alt = if (r.host.isNotBlank() && r.host != primaryHost()) {
                        "\n线路 ${r.host}"
                    } else ""
                    tvLibMsg.text = r.message + upgrade + alt
                    tvHint.text = ""
                    Toast.makeText(this, "规则库已更新", Toast.LENGTH_SHORT).show()
                } else {
                    tvLibMsg.text = r.message
                    tvHint.text = ""
                }
            }
        }.start()
    }

    private fun showSourceDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(Prefs.remoteUrl)
            setHint("https://…/rules.json")
            setPadding(pad, pad, pad, pad)
            minLines = 3
            gravity = android.view.Gravity.TOP
            textSize = 12f
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("规则库地址")
            .setMessage(
                "默认是自己的规则库，一般不用改。\n\n" +
                    "只接受 https 地址：规则就是「在谁的界面上点哪里」，" +
                    "明文传输等于把点击权交给同一个 WiFi 下的任何人。\n\n" +
                    "改完会自动拉一次。"
            )
            .setView(input)
            .setPositiveButton("保存并获取", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton("取消", null)
            .create()

        // 默认的按钮监听会先关弹窗再执行，地址写错时人就被弹出来了，
        // 还得重新长按一次。所以自己接管：校验不过就留在原地，把错误标在输入框上。
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty() && !v.startsWith("https://", ignoreCase = true)) {
                        input.error = "必须以 https:// 开头"
                        return@setOnClickListener
                    }
                    input.error = null
                    dlg.dismiss()
                    Prefs.remoteUrl = v
                    Prefs.remoteEtag = ""
                    Prefs.remoteEtagUrl = ""
                    if (v.isBlank()) {
                        tvLibMsg.visibility = android.view.View.VISIBLE
                        tvLibMsg.text = "已清空地址，现在只用内置规则和你自己的规则"
                        refreshLib()
                    } else {
                        manualSync()
                    }
                }
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    dlg.dismiss()
                    Prefs.remoteUrl = Prefs.DEFAULT_REMOTE_URL
                    Prefs.remoteEtag = ""
                    Prefs.remoteEtagUrl = ""
                    manualSync()
                }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ 学来的规则

    private fun refreshTaught() {
        val n = TaughtRulesStore.count(Prefs.taughtRules)
        if (n == 0) {
            tvTaught.text = "还没学过弹窗规则。\n弹窗关不掉时：长按「检」悬浮球 → 点一下那个关闭按钮"
            btnTaughtClear.visibility = android.view.View.GONE
            btnTaughtSend.visibility = android.view.View.GONE
        } else {
            tvTaught.text = "已学会 $n 条弹窗规则 · 查看 / 复制 / 删除"
            btnTaughtClear.visibility = android.view.View.VISIBLE
            // 没人送回来，学到的规则就永远只在这一台手机上存在
            btnTaughtSend.visibility = android.view.View.VISIBLE
        }
    }

    private fun showTaught() {
        val rows = TaughtRulesStore.rows(Prefs.taughtRules)
        if (rows.isEmpty()) {
            tvHint.text = "还没有学过的规则"
            return
        }
        val items = rows.map {
            val app = it.name.ifBlank { it.pkg }
            "$app\n出现「${it.whenText}」→ 点「${it.actionText}」"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("已学会的弹窗规则")
            .setItems(items) { _, i -> rowActions(rows[i]) }
            .setNeutralButton("复制全部") { _, _ ->
                copy(TaughtRulesStore.exportAll(Prefs.taughtRules), rows.size)
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * 点一条要能「复制」——学出来的东西发给我，才是把它补进公共库的唯一路径。
     * 删除放在第二层，避免手一抖把刚学会的规则点没了。
     */
    private fun rowActions(target: TaughtRulesStore.Row) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("「${target.whenText}」→ 点「${target.actionText}」")
            .setMessage(TaughtRulesStore.exportLine(target))
            .setPositiveButton("复制") { _, _ -> copy(TaughtRulesStore.exportLine(target), 1) }
            .setNeutralButton("删除") { _, _ -> confirmDelete(target) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「提交给开发者」：全选复制，再问一句要不要去问卷。 */
    private fun sendAll() {
        val rows = TaughtRulesStore.rows(Prefs.taughtRules)
        if (rows.isEmpty()) {
            tvHint.text = "还没有学过的规则"
            return
        }
        copy(TaughtRulesStore.exportAll(Prefs.taughtRules), rows.size)
    }

    /**
     * 复制之后就问「要不要去问卷」。
     *
     * 只弹一次 toast 的话，用户知道东西在剪贴板里，但不知道往哪贴——
     * 问卷地址在他那儿是空的。问一句，跳不跳他自己定，App 不偷偷联网。
     */
    private fun copy(text: String, n: Int) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("秒跳规则", text))
        Feedback.showRuleSurvey(this, n)
    }

    /** 学错了必须能撤：这条规则会真的去点别人 App 上的按钮 */
    private fun confirmDelete(target: TaughtRulesStore.Row) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除这条规则？")
            .setMessage("出现「${target.whenText}」时，不再自动点「${target.actionText}」。")
            .setPositiveButton("删除") { _, _ ->
                Prefs.taughtRules = TaughtRulesStore.removeWhere(Prefs.taughtRules) {
                    it.pkg == target.pkg &&
                        it.whenText == target.whenText &&
                        it.actionText == target.actionText
                }
                SkipAccessibilityService.instance?.reloadRules()
                refreshTaught()
                tvHint.text = "已删除"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearTaught() {
        val n = TaughtRulesStore.count(Prefs.taughtRules)
        if (n == 0) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空全部 $n 条学来的规则？")
            .setMessage("手写的自定义规则不受影响。")
            .setPositiveButton("清空") { _, _ ->
                Prefs.taughtRules = ""
                SkipAccessibilityService.instance?.reloadRules()
                refreshTaught()
                tvHint.text = "已清空学来的规则"
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun save() {
        val text = etRules.text.toString()
        if (text.isNotBlank()) {
            val msg = validate(text)
            if (!msg.startsWith("格式正确")) {
                tvHint.text = msg
                return
            }
        }
        Prefs.customRules = text
        SkipAccessibilityService.instance?.reloadRules()
        tvHint.text = "已保存并生效"
        Toast.makeText(this, "规则已保存", Toast.LENGTH_SHORT).show()
    }

    private fun validate(text: String): String {
        if (text.isBlank()) return "内容为空：保存后会只使用内置规则"
        return try {
            // 必须和 RuleRepository 用同一套解析器：加载路径能容忍社区规则里
            // 的尾随逗号、也支持「李跳跳哈希数组」格式。以前这里用裸 JSONObject
            // 校验，会把实际上完全能加载的规则判成「格式错误」并拒绝保存。
            val doc = RuleDocument.parse(text)
            val stats = RuleDocument.stats(text)
            val shape = if (doc is JSONObject) "对象格式" else "数组格式"
            "格式正确（$shape），共 ${stats.records} 个应用规则" +
                "（开屏 ${stats.splashApps} / 弹窗 ${stats.popupApps}）"
        } catch (t: Throwable) {
            // 用户自己粘进来的 JSON 也可能把解析器搞崩（深层嵌套 → StackOverflowError）。
            // 这里只该显示「格式错误」，不该让进程退出。
            "JSON 格式错误：${t.message ?: t.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TEMPLATE = """
{
  "apps": {
    "com.example.app": {
      "name": "示例应用",
      "splash": {
        "keywords": ["跳过", "关闭广告"],
        "keywords_append": ["tv_skip"],
        "delay": 300,
        "duration": 4000,
        "interval": 200
      },
      "popups": [
        {
          "when": ["检测到更新"],
          "action": ["以后再", "取消"],
          "cooldown": 8000
        },
        {
          "when": ["立即更新", "-本次不再提醒"],
          "action": ["=以后再说"]
        }
      ]
    }
  }
}
"""
    }
}
