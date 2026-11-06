package com.miaotiao.adskip.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.engine.RuleDocument
import com.miaotiao.adskip.service.SkipAccessibilityService
import org.json.JSONObject

class RulesActivity : AppCompatActivity() {

    private lateinit var etRules: EditText
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        etRules = findViewById(R.id.etRules)
        tvHint = findViewById(R.id.tvHint)

        etRules.setText(Prefs.customRules)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnValidate).setOnClickListener {
            tvHint.text = validate(etRules.text.toString())
        }
        findViewById<MaterialButton>(R.id.btnTemplate).setOnClickListener {
            etRules.setText(TEMPLATE.trim())
            tvHint.text = "模板已填入，改成你自己的包名和关键词后保存"
        }
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
