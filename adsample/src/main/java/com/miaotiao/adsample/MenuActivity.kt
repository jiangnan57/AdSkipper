package com.miaotiao.adsample

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#111318"))
        val pad = dp(16)
        root.setPadding(pad, dp(40), pad, pad)

        val title = TextView(this)
        title.text = "广告形态样本 · 点一项开始"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setPadding(0, 0, 0, dp(6))
        root.addView(title)

        val hint = TextView(this)
        hint.text = "启动后会自动倒计时 15 秒，若被跳过则直接进入内容页"
        hint.setTextColor(Color.parseColor("#8A90A0"))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        hint.setPadding(0, 0, 0, dp(16))
        root.addView(hint)

        Variants.ALL.forEach { v ->
            val item = TextView(this)
            item.text = if (v.id == 99) "${v.name}  #99" else "#${v.id}  ${v.name}"
            item.setTextColor(if (v.id == 99) Color.parseColor("#FFB300") else Color.parseColor("#E6E8EE"))
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            item.setPadding(dp(14), dp(14), dp(14), dp(14))
            item.setBackgroundColor(Color.parseColor("#1C2029"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            item.layoutParams = lp
            item.setOnClickListener {
                startActivity(Intent(this, AdActivity::class.java).putExtra("variant", v.id))
            }
            root.addView(item)
        }

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#111318"))
        sv.addView(root)
        setContentView(sv)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
