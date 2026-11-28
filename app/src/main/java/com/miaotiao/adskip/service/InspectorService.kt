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
 * 屏幕上的一个小球，有两种模式，由启动时的 extra 决定：
 *
 * **学弹窗（用户用）** — 球是琥珀色的「学」，点一下就把当前界面压成候选，
 * 再点弹窗上那个关不掉的按钮就学会了。入口在首页「教它关弹窗」。
 *
 * **自检 / 节点探测器（排查用）** — 球是蓝色的「检」，点一下跑一遍识别，
 * 告诉你命中什么、为什么没命中，还能 dump 整棵节点树。入口是长按标题 1.5 秒。
 *
 * 两种模式共用一个球，但颜色和字不同：用户不该看到「检」这种东西，
 * 也不该在「检」上找不到他要的「学」。
 */
class InspectorService : Service() {

    companion object {
        /** 带这个 extra 启动 = 学弹窗模式 */
        const val EXTRA_TEACH = "teach"

        /** 学弹窗模式的自动收起：一直挂着的小球迟早会变成屏幕上的一个疙瘩 */
        const val AUTO_FOLD_MS = 120_000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var teachMode: Boolean = false
            private set
    }

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var panel: View? = null

    /** 学弹窗模式的自动收起：一直挂着的小球迟早会变成屏幕上的一个疙瘩 */
    private var autoFold: Runnable? = null

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
        // 球不在这里挂：Service 拿不到启动它的 Intent，模式要等 onStartCommand。
        // 先挂一个「检」再换成「学」，用户会看到球闪一下。
    }

    /**
     * 球已经在屏幕上时又被启动一次（比如先开着自检，再点首页的「教它关弹窗」）：
     * 换模式而不是再挂一个小球——两个球叠在一起，谁也点不准。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val want = intent?.getBooleanExtra(EXTRA_TEACH, false) ?: false
        if (ball == null || want != teachMode) {
            teachMode = want
            removeBall()
            addBall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        autoFold?.let { ui.removeCallbacks(it) }
        runCatching { worker.shutdownNow() }
        removeBall()
        removePanel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 悬浮球

    private fun addBall() {
        val teach = teachMode
        // 学弹窗的球大一圈：它要在别的 App 里被手指戳中，而且常常是单手
        val size = dp(if (teach) 54 else 46)
        val blue = 0xE61E6FE0.toInt()
        val amber = 0xE6D9822B.toInt()
        val base = if (teach) amber else blue
        val baseText = if (teach) "学" else "检"

        val view = TextView(this).apply {
            text = baseText
            setTextColor(Color.WHITE)
            textSize = if (teach) 17f else 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(base)
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

        // 学弹窗模式：两分钟没人碰就自己收起来。用户很可能学完就忘了它，
        // 而一个常驻在最上层的球是那种「看着就烦、又不敢点」的东西。
        if (teach) {
            val foldLater = Runnable {
                toast("「学」球已自动收起，要再用点首页的「教它关弹窗」")
                stopSelf()
            }
            autoFold = foldLater
            ui.postDelayed(foldLater, AUTO_FOLD_MS)
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var armed = false

            /**
             * 自检模式：按住半秒「上膛」，球变琥珀色、字变成「学」。
             *
             * 必须有这个视觉反馈。没有的话用户按住 0.3 秒松手，球什么都没做，
             * 他只会觉得「这个球坏了」；而长按和点击共用同一个球，
             * 不区分的话又永远学不了弹窗。
             */
            private val arm = Runnable {
                armed = true
                view.text = "学"
                (view.background as? GradientDrawable)?.setColor(amber)
            }

            /** 学弹窗模式：球本身就是「学」，长按只剩一个用途——收起来 */
            private val fold = Runnable {
                toast("已收起「学」球")
                stopSelf()
            }

            private fun reset() {
                armed = false
                ui.removeCallbacks(arm)
                ui.removeCallbacks(fold)
                view.text = baseText
                (view.background as? GradientDrawable)?.setColor(base)
            }

            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        // 用户已经在用了，自动收起的计时重新开始
                        autoFold?.let { ui.removeCallbacks(it) }
                        if (teach) ui.postDelayed(fold, 700L) else ui.postDelayed(arm, 480L)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val moved = kotlin.math.abs(ev.rawX - downX) > 14 ||
                            kotlin.math.abs(ev.rawY - downY) > 14
                        if (moved && !armed) {
                            ui.removeCallbacks(arm)
                            ui.removeCallbacks(fold)
                        }
                        lp.x = startX - (ev.rawX - downX).toInt()
                        lp.y = startY + (ev.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val moved = kotlin.math.abs(ev.rawX - downX) > 14 ||
                            kotlin.math.abs(ev.rawY - downY) > 14
                        val learn = armed && !moved
                        reset()
                        autoFold?.let { ui.postDelayed(it, AUTO_FOLD_MS) }
                        if (!moved) {
                            // 学弹窗模式下点球就是要学；自检模式点球是看结果
                            if (teach || learn) teach() else capture()
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        reset()
                        autoFold?.let { ui.postDelayed(it, AUTO_FOLD_MS) }
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
        // 换模式时旧球上的自动收起必须一起撤掉，否则它会把新球关掉
        autoFold?.let { ui.removeCallbacks(it) }
        autoFold = null
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
    }

    // ------------------------------------------------------------------ 面板

    /** 点球（学弹窗模式）：进入「学弹窗」，由无障碍服务负责抓树和盖遮罩 */
    private fun teach() {
        val svc = SkipAccessibilityService.instance
        if (svc == null) {
            toast("请先开启无障碍服务")
            return
        }
        removePanel()
        svc.beginTeach()
    }

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
