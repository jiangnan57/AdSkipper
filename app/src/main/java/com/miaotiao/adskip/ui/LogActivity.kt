package com.miaotiao.adskip.ui

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miaotiao.adskip.R
import com.miaotiao.adskip.util.LogExport
import com.miaotiao.adskip.util.SkipLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 运行记录。
 *
 * 以前这 10 行等宽字就摆在首页，问题是双重的：
 * 一是吵——真正的状态（有没有在跑）和一堆 11sp 日志挤在同一屏，视觉重量一样；
 * 二是没用——用户其实只想确认「刚才那次是不是被它拦下来的」，
 * 而不是读 "skipped pkg=com.tencent.mm how=rule"。
 *
 * 所以这里按「天」分组、按「应用」命名，只说三件事：谁、做了什么、几点。
 * 颜色分三类（跳过 / 拦截 / 没找到），长按或右上角导出完整记录。
 */
class LogActivity : AppCompatActivity() {

    private companion object {
        const val KIND_SKIP = 0
        const val KIND_GUARD = 1
        const val KIND_MISS = 2
        const val KIND_SYSTEM = 3

        const val SHAPE_FIRST = 0
        const val SHAPE_MID = 1
        const val SHAPE_LAST = 2
        const val SHAPE_SINGLE = 3
    }

    private sealed class Row {
        class Day(val label: String) : Row()
        class Item(
            val time: String,
            val title: String,
            val desc: String,
            val kind: Int,
            val shape: Int
        ) : Row()
    }

    private lateinit var list: ListView
    private lateinit var tvEmpty: TextView

    private val refresh: () -> Unit = { rebuild() }
    private val labelCache = HashMap<String, String>()
    private var dots: Array<GradientDrawable>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        list = findViewById(R.id.listLog)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnExport).setOnClickListener { LogExport.share(this) }
        list.setOnItemLongClickListener { _, _, _, _ ->
            LogExport.share(this)
            true
        }

        dots = Array(4) { buildDot(it) }
        rebuild()
    }

    override fun onStart() {
        super.onStart()
        SkipLog.observe(refresh)
    }

    override fun onStop() {
        SkipLog.remove(refresh)
        super.onStop()
    }

    // ------------------------------------------------------------------ 组装

    private fun rebuild() {
        val rows = buildRows(SkipLog.snapshot())
        list.adapter = Adapter(rows)
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 把落盘的纯文本记录拆成「天 + 条」。
     *
     * 格式是 `MM-dd HH:mm:ss  内容`（见 SkipLog.add）。这里不追求把每条都
     * 解析得很细——解析失败的就当系统提醒原样显示，比丢掉强。
     */
    private fun buildRows(lines: List<String>): List<Row> {
        val parsed = lines.mapNotNull { parseLine(it) }
        if (parsed.isEmpty()) return emptyList()

        val out = ArrayList<Row>(parsed.size + 8)
        var currentDay: String? = null
        var buffer = ArrayList<Parsed>()

        fun flush() {
            val day = currentDay ?: return
            if (buffer.isEmpty()) return
            out.add(Row.Day(dayLabel(day, buffer.size)))
            buffer.forEachIndexed { i, p ->
                val shape = when {
                    buffer.size == 1 -> SHAPE_SINGLE
                    i == 0 -> SHAPE_FIRST
                    i == buffer.size - 1 -> SHAPE_LAST
                    else -> SHAPE_MID
                }
                out.add(Row.Item(p.time, p.title, p.desc, p.kind, shape))
            }
            buffer = ArrayList()
        }

        parsed.forEach { p ->
            if (p.day != currentDay) {
                flush()
                currentDay = p.day
            }
            buffer.add(p)
        }
        flush()
        return out
    }

    private class Parsed(
        val day: String,
        val time: String,
        val title: String,
        val desc: String,
        val kind: Int
    )

    private fun parseLine(line: String): Parsed? {
        if (line.length < 16) return null
        val day = line.substring(0, 5)
        val time = line.substring(6, 11)
        val msg = line.substring(16).trim()
        if (msg.isEmpty()) return null

        // 每次服务重连都会刷这两条，留在列表里会把真正有用的记录顶走。
        // 数据本身没丢，导出时仍然是完整的。
        if (msg.contains("规则已加载") || msg.contains("无障碍服务已连接")) return null

        var kind = KIND_SYSTEM
        var title = "系统提醒"
        var desc = msg

        when {
            msg.startsWith("跳过 ") -> {
                val body = msg.removePrefix("跳过 ")
                val name = body.substringBefore(" · ").trim()
                val how = body.substringAfter(" · ", "")
                kind = KIND_SKIP
                title = name.ifBlank { "未知应用" }
                // label 里本来就带「跳过 5s」这种倒计时文案，再拼一次「跳过」
                // 就成了「开屏广告已跳过 · 跳过 5s」。只有真正说明了「怎么跳过的」
                // （自绘、规则名）才值得跟在后面。
                desc = when {
                    how.isBlank() -> getString(R.string.log_skip)
                    how.contains("自绘") -> "开屏广告已跳过 · 自绘按钮"
                    how.startsWith("跳过") -> getString(R.string.log_skip)
                    else -> {
                        val way = how.substringBefore("（").trim()
                        if (way.isBlank() || way.startsWith("跳过")) getString(R.string.log_skip)
                        else "开屏广告已跳过 · $way"
                    }
                }
            }

            msg.startsWith("关闭弹窗") -> {
                val name = msg.substringAfter("·", "").trim()
                kind = KIND_SKIP
                title = name.ifBlank { "未知应用" }
                desc = getString(R.string.log_popup)
            }

            msg.startsWith("拦截跳转 ") -> {
                val pkg = msg.removePrefix("拦截跳转 ").substringBefore("：").trim()
                kind = KIND_GUARD
                title = appLabel(pkg)
                desc = getString(R.string.log_guard)
            }

            msg.startsWith("已从 ") -> {
                // 「已从 xxx.com 退出（该页会吞返回键）」——包名可能是网页域名
                val who = msg.removePrefix("已从 ").substringBefore(" ").trim()
                kind = KIND_GUARD
                title = appLabel(who)
                desc = "该页吞返回键，已退出"
            }

            msg.startsWith("看到广告页但没认出跳过按钮") -> {
                val name = msg.substringAfter("：", "").trim()
                kind = KIND_MISS
                title = name.ifBlank { "未知应用" }
                desc = getString(R.string.log_miss)
            }
        }

        return Parsed(day, time, title, desc, kind)
    }

    private fun dayLabel(day: String, count: Int): String {
        val fmt = SimpleDateFormat("MM-dd", Locale.getDefault())
        val today = fmt.format(Date())
        val yesterday = fmt.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.time
        )
        val head = when (day) {
            today -> getString(R.string.log_today)
            yesterday -> getString(R.string.log_yesterday)
            else -> runCatching {
                fmt.parse(day)?.let { SimpleDateFormat("M月d日", Locale.getDefault()).format(it) }
            }.getOrNull() ?: day
        }
        return "$head · $count 条"
    }

    /** 包名 → 应用名。查不到就退回包名最后一段，至少比一串 com.xxx.yyy 好读 */
    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "未知应用"
        labelCache[pkg]?.let { return it }
        val name = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA))
                .toString()
        }.getOrNull()
        val v = name?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
        labelCache[pkg] = v
        return v
    }

    private fun buildDot(kind: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val c = when (kind) {
            KIND_SKIP -> R.color.ok
            KIND_GUARD -> R.color.warn
            else -> R.color.chevron
        }
        setColor(ContextCompat.getColor(this@LogActivity, c))
    }

    // ------------------------------------------------------------------ 列表

    private inner class Adapter(private val rows: List<Row>) : BaseAdapter() {

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Day) 0 else 1

        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Day -> {
                    val v = convertView
                        ?: layoutInflater.inflate(R.layout.row_log_day, parent, false)
                    (v as TextView).text = row.label
                    v
                }

                is Row.Item -> {
                    val v = convertView
                        ?: layoutInflater.inflate(R.layout.row_log_item, parent, false)
                    bindItem(v, row)
                    v
                }
            }
        }
    }

    private fun bindItem(v: View, row: Row.Item) {
        val piece = v.findViewById<View>(R.id.cardPiece)
        piece.setBackgroundResource(
            when (row.shape) {
                SHAPE_FIRST -> R.drawable.bg_log_first
                SHAPE_MID -> R.drawable.bg_log_mid
                SHAPE_LAST -> R.drawable.bg_log_last
                else -> R.drawable.bg_log_single
            }
        )
        // 组首不画分隔线，否则组首顶上会多出一条悬空的线
        v.findViewById<View>(R.id.sepLog).visibility =
            if (row.shape == SHAPE_FIRST || row.shape == SHAPE_SINGLE) View.GONE else View.VISIBLE

        v.findViewById<View>(R.id.dotLog).background = dots?.get(row.kind)
        v.findViewById<TextView>(R.id.tvLogTitle).text = row.title
        v.findViewById<TextView>(R.id.tvLogDesc).text = row.desc
        v.findViewById<TextView>(R.id.tvLogTime).text = row.time
    }
}
