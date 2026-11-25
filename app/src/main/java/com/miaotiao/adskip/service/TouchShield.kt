package com.miaotiao.adskip.service

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 触摸屏蔽层（v1.5 的 R11，实验，默认关）。
 *
 * ## 它想解决什么
 *
 * 现在所有的「防」都是事后补救：落地页已经起来了，再按返回弹回去。
 * 点击型广告（点屏幕任意位置跳转）其实可以**不让它发生**——在广告上盖
 * 一层透明、可触摸的 View，把用户那一下吃掉。
 *
 * 摇一摇无效（没有触摸），那一半交给 [ShakeDetector]。
 *
 * ## 三个必须守住的边界
 *
 * 1. **最多 3 秒**。屏蔽层的存在感必须低于「用户会注意到」的阈值。
 * 2. **必须有一个看得见的解除入口**。判错的时候用户得能自己出来——
 *    不能让他等 3 秒。
 * 3. **不能挡自己的点击**。坐标点击（`dispatchGesture`）会被覆盖层吃掉，
 *    所以注入前要先撤掉（见 [releaseForTap]）。
 *
 * ## 为什么用 TYPE_ACCESSIBILITY_OVERLAY
 *
 * 无障碍服务专用的窗口类型，不需要「悬浮窗」权限。用
 * `TYPE_APPLICATION_OVERLAY` 会要求用户再授权一次，而且部分 ROM
 * 在权限弹窗期间会禁用触摸。
 *
 * ## 为什么默认关
 *
 * 它会**吃掉触摸**。判错一次用户的感受就是「屏幕点不动了」。
 * 这个代价不应该让所有人承担，只能给愿意开实验开关的人。
 */
object TouchShield {

    private const val TAG = "AdSkip"

    /** 最长存在时间 */
    const val MAX_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var wm: WindowManager? = null
    private var until = 0L
    private var lastDurationMs = MAX_MS

    private val hide = Runnable { release() }

    /**
     * 盖上屏蔽层。
     *
     * @return 是否真的盖上了（`addView` 可能被系统拒绝，只打一行日志）
     */
    fun cover(service: SkipAccessibilityService, durationMs: Long = MAX_MS): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cover(service, durationMs) }
            return true
        }
        if (root != null) return true
        val w = service.getSystemService(WindowManager::class.java) ?: return false
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE：不抢焦点，物理返回键照常可用。
            // 刻意**不加** NOT_TOUCHABLE —— 那一项是「不接收触摸」，加了就等于没盖。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val container = FrameLayout(service)

        // 吃触摸的那一层。背景几乎全透明（留 1/255 是为了某些 ROM 上
        // 完全透明的窗口不接收触摸）。
        val blocker = View(service)
        blocker.setBackgroundColor(Color.argb(1, 0, 0, 0))
        blocker.setOnClickListener { /* 就是用来吃掉这一下的 */ }
        container.addView(
            blocker,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 解除入口：小、半透明、在右下角，不挡主要内容
        val release = TextView(service).apply {
            text = "解除屏蔽"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { release() }
        }
        val rp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        rp.gravity = Gravity.BOTTOM or Gravity.END
        rp.setMargins(0, 0, 48, 160)
        container.addView(release, rp)

        return try {
            w.addView(container, lp)
            root = container
            wm = w
            lastDurationMs = durationMs
            until = System.currentTimeMillis() + durationMs
            main.removeCallbacks(hide)
            main.postDelayed(hide, durationMs)
            Log.i(TAG, "shield cover ${durationMs}ms")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "shield cover failed", t)
            root = null
            wm = null
            false
        }
    }

    /** 撤掉。幂等。 */
    fun release() {
        main.removeCallbacks(hide)
        val v = root ?: return
        val w = wm
        root = null
        wm = null
        until = 0L
        runCatching { w?.removeViewImmediate(v) }
        Log.i(TAG, "shield release")
    }

    fun isActive(): Boolean = root != null

    /** 还剩多久（毫秒，0 = 没盖着） */
    fun remainingMs(): Long {
        if (root == null) return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * 临时撤掉再盖上，给一次坐标点击让路。
     *
     * `dispatchGesture` 注入的触摸会打在最上层窗口上——也就是我们自己的
     * 屏蔽层。不撤的话，跳过按钮永远点不到，而且日志会显示「点了但没反应」。
     */
    fun releaseForTap(service: SkipAccessibilityService, resumeAfterMs: Long = 60L) {
        if (!isActive()) return
        val left = remainingMs()
        release()
        if (left <= resumeAfterMs) return
        main.postDelayed({ cover(service, left - resumeAfterMs) }, resumeAfterMs)
    }

    /** 服务销毁时调用 */
    fun destroy() {
        release()
        main.removeCallbacksAndMessages(null)
    }
}
