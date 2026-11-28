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
import com.miaotiao.adskip.service.SkipAccessibilityService
import org.json.JSONObject

class RulesActivity : AppCompatActivity() {

    private lateinit var etRules: EditText
    private lateinit var tvHint: TextView
    private lateinit var tvTaught: TextView
    private lateinit var btnTaughtClear: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        etRules = findViewById(R.id.etRules)
        tvHint = findViewById(R.id.tvHint)
        tvTaught = findViewById(R.id.tvTaught)
        btnTaughtClear = findViewById(R.id.btnTaughtClear)

        etRules.setText(Prefs.customRules)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnValidate).setOnClickListener {
            tvHint.text = validate(etRules.text.toString())
        }
        findViewById<MaterialButton>(R.id.btnTemplate).setOnClickListener {
            etRules.setText(TEMPLATE.trim())
            tvHint.text = "模板已填入，改成你自己的包名和关键词后保存"
        }

        tvTaught.setOnClickListener { showTaught() }
        btnTaughtClear.setOnClickListener { confirmClearTaught() }
        refreshTaught()
    }

    // ------------------------------------------------------------------ 学来的规则

    private fun refreshTaught() {
        val n = TaughtRulesStore.count(Prefs.taughtRules)
        if (n == 0) {
            tvTaught.text = "还没学过弹窗规则。\n弹窗关不掉时：长按「检」悬浮球 → 点一下那个关闭按钮"
            btnTaughtClear.visibility = android.view.View.GONE
        } else {
            tvTaught.text = "已学会 $n 条弹窗规则 · 点这里查看或删除"
            btnTaughtClear.visibility = android.view.View.VISIBLE
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
            .setTitle("已学会的弹窗规则（点一条可删除）")
            .setItems(items) { _, i -> confirmDelete(rows[i]) }
            .setPositiveButton("关闭", null)
            .show()
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
        } catch (e: Exception) {
            "JSON 格式错误：${e.message}"
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
