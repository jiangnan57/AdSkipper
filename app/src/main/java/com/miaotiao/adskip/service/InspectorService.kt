package com.miaotiao.adskip.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 自检 / 节点探测器：在任意界面上显示一个悬浮球，点一下就能：
 *   - 「自检」：用真实规则跑一遍识别，告诉你命中了什么、为什么没命中
 *   - 「节点树」：把当前窗口完整 dump 出来，写规则时照着抄
 *   - 「记住」：把当前屏幕上最像跳过按钮的那个节点直接写进规则
 *
 * 这三个按钮合起来就是「让手机自己检测」的入口：
 * 不用连电脑，切到目标 App 点一下球就知道结果。
 */
class InspectorService : Service() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var panel: View? = null

    /**
     * 自检必须跑在后台线程。
     *
     * 这里踩过一个很深的坑：`analyzeForeground()` 内部要读 rootInActiveWindow、
     * 遍历几百个节点、还可能做 webView 文本检索（单次可达数秒）。这些全是同步 IPC，
     * 放在 View 的点击回调（主线程）上，轻则面板卡住一两秒没反应，
     * 重则死锁——系统在等主线程响应，而主线程正卡在读窗口上。
     *
     * 服务里所有节点树操作都走独立线程（见 SkipAccessibilityService.poller 的注释），
     * 自检面板没有理由例外。
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "adskip-inspector").apply { isDaemon = true }
    }
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** 在后台跑 block，结果回主线程交给 after */
    private fun offMain(block: () -> String, after: (String) -> Unit) {
        worker.execute {
            val text = runCatching(block).getOrElse { "读取失败：${it.message}" }
            // 用户可能在读取期间关掉了悬浮球：服务销毁后 panel 已被移除，
            // 这时再 after(text) 会往已经失效的 WindowManager 里 addView。
            ui.post { if (running) after(text) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        runCatching { worker.shutdownNow() }
        removeBall()
        removePanel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 悬浮球

    private fun addBall() {
        val size = dp(46)
        val view = TextView(this).apply {
            text = "检"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61E6FE0.toInt())
            }
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(8)
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX - (ev.rawX - downX).toInt()
                        lp.y = startY + (ev.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val moved = kotlin.math.abs(ev.rawX - downX) > 14 ||
                            kotlin.math.abs(ev.rawY - downY) > 14
                        if (!moved) capture()
                        return true
                    }
                }
                return false
            }
        })

        runCatching {
            wm.addView(view, lp)
            ball = view
        }
    }

    private fun removeBall() {
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
    }

    // ------------------------------------------------------------------ 面板

    private fun capture() {
        val svc = SkipAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            return
        }
        // 先给个空面板，让用户知道「在算了」，避免以为球没反应
        showPanel("正在读取当前界面…")
        // 默认先给自检结果，比整棵节点树有用得多
        offMain({ svc.analyzeForeground() }) { text ->
            panel?.let {
                val body = it.findViewWithTag<TextView>("body")
                body?.text = text
            } ?: showPanel(text)
        }
    }

    private fun showPanel(content: String) {
        removePanel()

        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2FFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(ctx).apply {
            text = "自检结果"
            textSize = 13f
            setTextColor(0xFF1A1A1A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnTree = panelButton("节点树")
        val btnLearn = panelButton("记住")
        val btnCopy = panelButton("复制")
        val btnClose = panelButton("关闭")

        header.addView(title)
        header.addView(btnTree)
        header.addView(btnLearn)
        header.addView(btnCopy)
        header.addView(btnClose)

        val body = TextView(ctx).apply {
            text = content
            textSize = 10f
            setTextColor(0xFF333333.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
            tag = "body"
        }

        val scroll = ScrollView(ctx).apply { addView(body) }
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        runCatching {
            wm.addView(root, lp)
            panel = root
        }

        btnTree.setOnClickListener {
            val svc = SkipAccessibilityService.instance ?: return@setOnClickListener
            title.text = "节点树 · 读取中…"
            offMain({ svc.dumpForegroundWindow() }) { t ->
                title.text = "节点树 · ${t.length} 字符"
                body.text = t
            }
        }

        btnLearn.setOnClickListener {
            val svc = SkipAccessibilityService.instance ?: return@setOnClickListener
            offMain({ svc.learnForeground() }) { msg ->
                toast(msg)
                // 单线程 executor 保证这条排在「记住」之后执行
                offMain({ svc.analyzeForeground() }) { fresh ->
                    title.text = "自检结果"
                    body.text = fresh
                }
            }
        }

        btnCopy.setOnClickListener {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("nodes", body.text))
            }
            toast("已复制")
        }

        btnClose.setOnClickListener { removePanel() }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    // ------------------------------------------------------------------ 工具

    private fun panelButton(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF1E6FE0.toInt())
        setPadding(dp(9), dp(6), dp(4), dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
