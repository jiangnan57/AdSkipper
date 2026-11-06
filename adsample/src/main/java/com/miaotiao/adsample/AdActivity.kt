package com.miaotiao.adsample

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 渲染人造开屏广告。形态由 intent extra "variant" 决定。
 * 被跳过（点击跳过控件）或 15 秒超时后进入 ContentActivity。
 */
class AdActivity : AppCompatActivity() {

    private val tag = "AdSample"
    private var variantId = 1
    private var skipped = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var skipLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        variantId = intent.getIntExtra("variant", 1)
        val v = Variants.byId(variantId)

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val root = FrameLayout(this)

        // 广告创意层：真实开屏广告几乎都是「整屏可点击」，
        // 所以这里也做成全屏可点击，用来检验误点防护是否生效。
        val ad = FrameLayout(this)
        ad.isClickable = true
        ad.setBackgroundColor(Color.parseColor("#2B3A67"))
        ad.setOnClickListener {
            Log.i(tag, "adClicked variant=$variantId  <- 点到广告了")
            // 真实广告会跳应用商店；这里只记录，不真的跳
        }
        root.addView(ad, FrameLayout.LayoutParams(w, h))

        val adText = TextView(this)
        // 注意：广告创意文案里绝不能出现「跳过 / 关闭 / skip」这类词，
        // 否则会污染识别测试（秒跳会把广告文案本身当按钮点掉）。
        adText.text = "AD CREATIVE #$variantId"
        adText.setTextColor(Color.parseColor("#9FB3E0"))
        adText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        adText.gravity = Gravity.CENTER
        adText.isClickable = false
        ad.addView(
            adText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        when (variantId) {
            1 -> addCornerSkip(root, w, h, "跳过 5s", topMarginDp = 28, rightMarginDp = 16) {
                "跳过 ${it}s"
            }
            2 -> addCornerSkip(root, w, h, "跳过广告", topMarginDp = 28, rightMarginDp = 16)
            3 -> addBottomRightSkip(root, w, h)
            4 -> addCircleCountdown(root, w, h)
            5 -> addCloseIcon(root, w, h)
            6 -> addBottomSkip(root, w, h)
            7 -> addWebViewAd(root, w, h)
            8 -> addSkipDirectInAdContainer(ad, w, h)
            9 -> addCornerSkip(root, w, h, "Skip", topMarginDp = 28, rightMarginDp = 16)
            10 -> addCornerSkip(root, w, h, "关闭广告", topMarginDp = 28, rightMarginDp = 16)
            11 -> addSecondsOnly(root, w, h)
            12 -> addSkipAtHeight(root, w, h, "跳过", 0.78f)
            13 -> addSkipInWrapper(root, w, h)
            14 -> addCornerSkip(root, w, h, "跳 过", topMarginDp = 28, rightMarginDp = 16)
            15 -> addSkipTouchOnlyInAdContainer(ad, w, h)
            99 -> setupContentControl(root, w, h)
            950 -> setupPopupOverlay(root, w, h)
            901 -> setupNovelPage(root, w, h)
            902 -> setupCommentSheet(root, w, h)
            903 -> setupChatPage(root, w, h)
            904 -> setupChatMessagePage(root, w, h)
        }

        setContentView(root)
        Log.i(tag, "render variant=$variantId name=${v.name}")

        // 负向样本不是广告，不该有 15 秒后自动进内容页的行为
        if (!v.expectSkip && variantId != 99) return

        // 15 秒硬超时，避免测试挂死
        handler.postDelayed({
            if (!skipped) {
                Log.i(tag, "timeout variant=$variantId  <- 未被跳过")
                gotoContent("timeout")
            }
        }, 15_000L)
    }

    // ---------- 各形态 ----------

    /** 右上角文字按钮；带倒计时则每秒递减 */
    private fun addCornerSkip(
        root: FrameLayout, w: Int, h: Int, initial: String,
        topMarginDp: Int, rightMarginDp: Int,
        fmt: ((Int) -> String)? = null
    ) {
        skipLabel = styledSkip(initial)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = if (topMarginDp > 200) topMarginDp else dp(topMarginDp)
        lp.rightMargin = dp(rightMarginDp)
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)

        if (fmt != null) tickDown(5) { n -> skipLabel.text = fmt(n) }
    }

    /** 把跳过按钮放在屏幕指定比例高度处（检验下方区域剪枝是否误杀） */
    private fun addSkipAtHeight(root: FrameLayout, w: Int, h: Int, text: String, ratio: Float) {
        skipLabel = styledSkip(text)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = (h * ratio).toInt()
        lp.rightMargin = dp(16)
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
    }

    /** 右下角圆形按钮 */
    private fun addBottomRightSkip(root: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("跳过")
        val lp = FrameLayout.LayoutParams(dp(64), dp(64))
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.rightMargin = dp(16)
        lp.bottomMargin = dp(96)
        skipLabel.gravity = Gravity.CENTER
        skipLabel.background = GradientDrawable().apply {
            cornerRadius = dp(32).toFloat()
            setColor(Color.parseColor("#B3000000"))
        }
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
    }

    /** 右上角只有数字的圆形倒计时 */
    private fun addCircleCountdown(root: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("5")
        val lp = FrameLayout.LayoutParams(dp(42), dp(42))
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        skipLabel.gravity = Gravity.CENTER
        skipLabel.background = GradientDrawable().apply {
            cornerRadius = dp(21).toFloat()
            setColor(Color.parseColor("#4D000000"))
            setStroke(dp(1), Color.parseColor("#66FFFFFF"))
        }
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
        tickDown(5) { n -> skipLabel.text = n.toString() }
    }

    /** 关闭图标：无文字，只有 contentDescription */
    private fun addCloseIcon(root: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("✕")
        skipLabel.contentDescription = "关闭"
        val lp = FrameLayout.LayoutParams(dp(36), dp(36))
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        skipLabel.gravity = Gravity.CENTER
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
    }

    /** 底部横条 */
    private fun addBottomSkip(root: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("点击跳过广告 3")
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(120)
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
        tickDown(3) { n -> skipLabel.text = "点击跳过广告 $n" }
    }

    /** WebView 内的跳过按钮（模拟 oopz 的 H5 广告） */
    private fun addWebViewAd(root: FrameLayout, w: Int, h: Int) {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.isVerticalScrollBarEnabled = false
        wv.settings.javaScriptEnabled = false
        // 真实 H5 广告的「跳过」通常是个 <a>，点击会跳链接。
        // 这里用同样机制，才能真正验证秒跳的坐标点击有没有落到 H5 元素上。
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, url: String?
            ): Boolean {
                if (url != null && url.contains("skip")) {
                    onSkipClicked()
                }
                return true
            }
        }
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:transparent;font-family:sans-serif}
            #skip{position:absolute;top:26px;right:16px;padding:8px 16px;background:rgba(0,0,0,0.55);
            color:#fff;border-radius:16px;font-size:15px;text-decoration:none;display:block}
            #ad{position:absolute;top:40%;width:100%;text-align:center;color:#dfe6ff;font-size:26px}</style>
            </head><body>
            <a id="skip" href="myapp://skip">跳过 5</a>
            <div id="ad">H5 AD</div>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        root.addView(wv, FrameLayout.LayoutParams(w, h))
    }

    /** 跳过控件直接放在全屏可点击容器里（唯一可点的是巨型容器，考验坐标点击保护） */
    private fun addSkipDirectInAdContainer(ad: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("跳过")
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        skipLabel.setOnClickListener { onSkipClicked() }
        ad.addView(skipLabel, lp)
    }

    /** 只有「3s」这样的秒数，不含跳过字样 */
    private fun addSecondsOnly(root: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("3s")
        val lp = FrameLayout.LayoutParams(dp(44), dp(44))
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        skipLabel.gravity = Gravity.CENTER
        skipLabel.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.parseColor("#4D000000"))
        }
        skipLabel.setOnClickListener { onSkipClicked() }
        root.addView(skipLabel, lp)
        tickDown(3) { n -> skipLabel.text = "${n}s" }
    }

    /** 跳过文字本身不可点击，由外层小容器响应 */
    private fun addSkipInWrapper(root: FrameLayout, w: Int, h: Int) {
        val wrapper = FrameLayout(this)
        wrapper.isClickable = true
        wrapper.setOnClickListener { onSkipClicked() }
        skipLabel = styledSkip("跳过")
        skipLabel.isClickable = false
        wrapper.addView(
            skipLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        root.addView(wrapper, lp)
    }

    /**
     * 真实穿山甲 / 腾讯系开屏广告的形态（QQ、WakeUp 课程表都是这一类）。
     *
     * 关键特征三条，缺一不可：
     *   1. 跳过按钮**节点自身不可点击**（`isClickable = false`）——广告 SDK 在
     *      自己的 View 里重写触摸事件，无障碍拿到的节点就是不可点的；
     *   2. 树里**唯一**的可点击祖先是那个全屏广告容器；
     *   3. 容器的 onClick 就是「跳广告落地页」。
     *
     * 于是 `performAction(CLICK)` 在容器上执行时，用户看到的就是
     * 「跳过成功」但页面跳走了——正是 QQ 跳 QQ 邮箱的那个现象。
     *
     * 正确的做法只能是在跳过按钮自己的坐标上补一次点击：
     * 触摸落到按钮上，SDK 的触摸处理接住，广告容器不会收到。
     */
    private fun addSkipTouchOnlyInAdContainer(ad: FrameLayout, w: Int, h: Int) {
        skipLabel = styledSkip("跳过 5s")
        // 1) 节点不可点击：ACCESSIBILITY 的 ACTION_CLICK 对它无效
        skipLabel.isClickable = false
        // 2) 用触摸事件处理点击，模拟广告 SDK 的做法
        skipLabel.setOnTouchListener { _, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) onSkipClicked()
            true
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(28)
        lp.rightMargin = dp(16)
        // 3) 直接挂在全屏可点击广告容器里，中间没有小容器
        ad.addView(skipLabel, lp)
    }

    /** 对照组：正常内容页，带可滚列表与大量可点击项，不该被点 */
    private fun setupContentControl(root: FrameLayout, w: Int, h: Int) {
        val ad = root.getChildAt(0) as FrameLayout
        ad.setBackgroundColor(Color.parseColor("#FFFFFF"))
        ad.setOnClickListener(null)
        ad.isClickable = false

        val list = android.widget.ListView(this)
        val items = (1..30).map { "内容条目 $it" }
        list.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        list.setOnItemClickListener { _, _, _, _ ->
            Log.i(tag, "contentItemClicked  <- 点到了内容项（误点）")
        }
        root.removeViewAt(0)
        root.addView(list, FrameLayout.LayoutParams(w, h))
    }

    // ---------- 弹窗样本：内容页上浮出来的弹层 ----------

    /**
     * 模拟 App 内的运营弹层：底下是普通内容，上面盖一个半透明遮罩 +
     * 一个带「会员特惠」标题和「以后再」按钮的卡片。
     * 按钮文案刻意不用「关闭广告」：那会被通用开屏关键词抢先命中，
     * 测出来的就不是弹窗规则这一条链路了。
     *
     * 这个形态走的是「弹窗规则」那条路（when 条件命中 → 点 action 按钮），
     * 和开屏广告的识别是两条不同的代码路径，需要单独验证。
     */
    private fun setupPopupOverlay(root: FrameLayout, w: Int, h: Int) {
        val bg = root.getChildAt(0) as FrameLayout
        bg.setOnClickListener(null)
        bg.isClickable = false
        bg.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val content = TextView(this).apply {
            text = "内容条目 1\n内容条目 2\n内容条目 3"
            setTextColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }
        root.removeViewAt(0)
        root.addView(content, FrameLayout.LayoutParams(w, h))

        // 遮罩
        val mask = View(this).apply { setBackgroundColor(0x66000000.toInt()) }
        root.addView(mask, FrameLayout.LayoutParams(w, h))

        // 弹层卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
        }
        card.addView(TextView(this).apply {
            text = "会员特惠"
            setTextColor(Color.parseColor("#111111"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        })
        card.addView(TextView(this).apply {
            text = "开通会员解锁全部内容"
            setTextColor(Color.parseColor("#888888"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(16))
        })
        val btn = TextView(this).apply {
            text = "以后再"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#FF6A00"))
            }
            setOnClickListener {
                Log.i(this@AdActivity.tag, "popupClosed variant=$variantId")
                this@AdActivity.skipped = true
                this@AdActivity.startActivity(
                    Intent(this@AdActivity, ContentActivity::class.java)
                        .putExtra("variant", variantId)
                        .putExtra("how", "弹窗规则")
                )
                this@AdActivity.finish()
            }
        }
        card.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(32)
            marginEnd = dp(32)
        })
    }

    // ---------- 负向样本：长得像广告、但绝对不许点的页面 ----------

    /**
     * 番茄小说式首页。
     * 右上角有个「分类」图标——位置、大小、无文字，跟开屏广告的 × 几乎一样；
     * 底部有导航栏，中间是书封列表，右侧还有点赞。
     * 旧版会在这里把「分类」或「点赞」点掉。
     */
    private fun setupNovelPage(root: FrameLayout, w: Int, h: Int) {
        val bg = root.getChildAt(0) as FrameLayout
        bg.setOnClickListener(null)
        bg.isClickable = false
        bg.setBackgroundColor(Color.parseColor("#191919"))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("推荐", "小说", "听书", "看剧").forEach { top.addView(chip(it)) }
        root.addView(
            top,
            FrameLayout.LayoutParams(w, dp(44)).apply {
                gravity = Gravity.TOP
                topMargin = dp(88)
            }
        )

        root.addView(
            iconButton("▦", "分类") { wrongClicked("分类") },
            FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(88)
                rightMargin = dp(12)
            }
        )

        val list = android.widget.ListView(this)
        list.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            (1..40).map { "《小说书名 $it》" }
        )
        list.setOnItemClickListener { _, _, _, _ -> wrongClicked("书封") }
        root.addView(
            list,
            FrameLayout.LayoutParams(w, h - dp(300)).apply {
                gravity = Gravity.TOP
                topMargin = dp(140)
            }
        )

        root.addView(
            iconButton("♡", "点赞") { wrongClicked("点赞") },
            FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                rightMargin = dp(8)
            }
        )

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("书城", "短剧", "书架", "我的").forEach { t ->
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.parseColor("#9A9A9A"))
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { wrongClicked(t) }
            }
            tabs.addView(tv, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(
            tabs,
            FrameLayout.LayoutParams(w, dp(56)).apply { gravity = Gravity.BOTTOM }
        )
    }

    /**
     * 抖音式评论区半屏弹层：视频打底 + 白色评论面板，
     * 面板右上角有个「✕ / 关闭」，位置在屏幕中部。
     */
    private fun setupCommentSheet(root: FrameLayout, w: Int, h: Int) {
        val bg = root.getChildAt(0) as FrameLayout
        bg.setBackgroundColor(Color.parseColor("#000000"))
        bg.setOnClickListener { wrongClicked("视频区") }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }

        val header = FrameLayout(this)
        header.addView(
            TextView(this).apply {
                text = "6 条评论"
                setTextColor(Color.parseColor("#222222"))
                textSize = 15f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(48))
        )
        header.addView(
            iconButton("✕", "关闭") { wrongClicked("评论关闭×") },
            FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(10)
            }
        )
        sheet.addView(header)

        val list = android.widget.ListView(this)
        list.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            (1..30).map { "用户$it：哈哈哈哈" }
        )
        list.setOnItemClickListener { _, _, _, _ -> wrongClicked("评论条目") }
        sheet.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val input = TextView(this).apply {
            text = "有爱评论，说点儿好听的"
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { wrongClicked("评论输入框") }
        }
        sheet.addView(input)

        root.addView(
            sheet,
            FrameLayout.LayoutParams(w, (h * 0.58f).toInt()).apply { gravity = Gravity.BOTTOM }
        )
    }

    /** DeepSeek 式欢迎页：整屏空白 + 右上角「新对话」图标 */
    private fun setupChatPage(root: FrameLayout, w: Int, h: Int) {
        val bg = root.getChildAt(0) as FrameLayout
        bg.setOnClickListener(null)
        bg.isClickable = false
        bg.setBackgroundColor(Color.parseColor("#0F0F14"))

        bg.addView(
            TextView(this).apply {
                text = "嗨！今天想聊些什么？"
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 17f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        root.addView(
            iconButton("⊕", "新对话") { wrongClicked("新对话") },
            FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(56)
                rightMargin = dp(14)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "发消息或按住说话"
                setTextColor(Color.parseColor("#8A8A8A"))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                setBackgroundColor(Color.parseColor("#1E1E24"))
                isClickable = true
                setOnClickListener { wrongClicked("输入框") }
            },
            FrameLayout.LayoutParams(w - dp(40), dp(72)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(40)
            }
        )
    }

    /**
     * 负向样本 904：QQ「QQ邮箱提醒」那种聊天页。
     *
     * 真实事故：一条 GitHub Actions 通知的正文是
     * `[jiangnan57/AdSkipper] Run failed: Build APK`，规则里的关键字 `Skip`
     * 直接子串匹配上了 `AdSkip` 里的 `Skip`，秒跳于是把这条**聊天消息卡片**
     * 当成跳过按钮点了下去——用户打开 QQ 就"莫名其妙进了 QQ 邮箱，还退不出来"。
     *
     * 这条样本要求：长文案里即便含 skip / Skip 字样，也绝不许点。
     */
    private fun setupChatMessagePage(root: FrameLayout, w: Int, h: Int) {
        val bg = root.getChildAt(0) as FrameLayout
        bg.setOnClickListener(null)
        bg.isClickable = false
        bg.setBackgroundColor(Color.parseColor("#121216"))

        root.addView(
            TextView(this).apply {
                text = "QQ 邮箱提醒"
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(w, dp(48)).apply {
                gravity = Gravity.TOP
                topMargin = dp(30)
            }
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E24"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { wrongClicked("邮件消息卡片") }
        }
        card.addView(
            TextView(this).apply {
                text = "jiangnan57: [jiangnan57/AdSkipper] Run failed: Build APK · all jobs failed"
                setTextColor(Color.parseColor("#BBBBBB"))
                textSize = 13f
            }
        )
        card.addView(
            TextView(this).apply {
                text = "邮件详情  ›"
                setTextColor(Color.parseColor("#7A7A80"))
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            }
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(w - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                topMargin = dp(110)
            }
        )
    }

    private fun chip(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#B0B0B0"))
        textSize = 14f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
        setOnClickListener { wrongClicked(text) }
    }

    private fun iconButton(glyph: String, desc: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            contentDescription = desc
            setTextColor(Color.parseColor("#DDDDDD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun wrongClicked(what: String) {
        Log.i(tag, "wrongClicked variant=$variantId what=$what  <- 点到了内容页控件")
    }

    // ---------- 公共 ----------

    private fun styledSkip(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setPadding(dp(10), dp(7), dp(10), dp(7))
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#66000000"))
        }
        tv.isClickable = true
        return tv
    }

    private fun tickDown(from: Int, onTick: (Int) -> Unit) {
        var n = from
        val r = object : Runnable {
            override fun run() {
                if (skipped || isFinishing) return
                if (n <= 0) return
                onTick(n)
                n--
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(r)
    }

    private fun onSkipClicked() {
        if (skipped) return
        skipped = true
        Log.i(tag, "skipClicked variant=$variantId")
        gotoContent("clicked")
    }

    private fun gotoContent(how: String) {
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, ContentActivity::class.java).putExtra("variant", variantId).putExtra("how", how))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpToPx(): Int = resources.displayMetrics.density.toInt().coerceAtLeast(1)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
