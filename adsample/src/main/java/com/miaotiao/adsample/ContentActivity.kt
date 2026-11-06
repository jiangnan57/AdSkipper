package com.miaotiao.adsample

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ContentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val variant = intent.getIntExtra("variant", 0)
        val how = intent.getStringExtra("how") ?: "?"
        Log.i("AdSample", "contentReached variant=$variant how=$how")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.WHITE)
        root.setPadding(60, 60, 60, 60)

        val t = TextView(this)
        // 注意：文案不能含「跳过」二字，否则会干扰秒跳的识别测试
        t.text = if (how == "clicked") "广告已结束 ✓" else "超时未处理（广告正常播完）"
        t.setTextColor(if (how == "clicked") Color.parseColor("#1B9E4B") else Color.parseColor("#C62828"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        t.gravity = Gravity.CENTER
        root.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val s = TextView(this)
        s.text = "样本 #$variant · $how\n\n这是内容页（对照组）"
        s.setTextColor(Color.parseColor("#666666"))
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        s.gravity = Gravity.CENTER
        s.setPadding(0, 40, 0, 0)
        root.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }
}
