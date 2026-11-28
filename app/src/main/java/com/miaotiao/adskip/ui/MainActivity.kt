package com.miaotiao.adskip.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.miaotiao.adskip.BuildConfig
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.LearnStore
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.net.RemoteRules
import com.miaotiao.adskip.service.InspectorService
import com.miaotiao.adskip.service.JumpGuard
import com.miaotiao.adskip.service.KeepAliveService
import com.miaotiao.adskip.service.SkipAccessibilityService
import com.miaotiao.adskip.util.AccessState
import com.miaotiao.adskip.util.AccessUtil
import com.miaotiao.adskip.util.LogExport
import com.miaotiao.adskip.util.SkipLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页。
 *
 * 这一屏的排序原则：**先回答「现在有没有在保护我」，再回答「今天省了几次」，
 * 最后才是开关和入口。**
 *
 * v1.7.0 重做之前是反的：状态卡用 15sp 灰字、开关四行、规则按钮、10 行等宽日志，
 * 视觉重量几乎一样，眼睛没有落点；而且「通知权限没开」这种关键问题被塞进
 * 一行 12sp 小字里，还长到要换行。
 *
 * 现在：状态卡置顶（大数字 + 一句话 + 三个权限点），日志搬到 LogActivity。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_NOTIFY = 1001

        /** 状态卡的三种配色：正常 / 需要处理 / 未开启 */
        private const val STYLE_OK = 0
        private const val STYLE_WARN = 1
        private const val STYLE_OFF = 2
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var tvTodayNum: TextView
    private lateinit var dotStatus: View
    private lateinit var heroCard: View
    private lateinit var todayBox: View
    private lateinit var btnFix: MaterialButton

    private lateinit var dotNotify: View
    private lateinit var dotOverlay: View
    private lateinit var dotBattery: View

    private lateinit var swMaster: MaterialSwitch
    private lateinit var swSplash: MaterialSwitch
    private lateinit var swPopup: MaterialSwitch
    private lateinit var swGuard: MaterialSwitch

    private lateinit var rowSplash: View
    private lateinit var rowPopup: View
    private lateinit var rowGuard: View

    private lateinit var tvLogSub: TextView

    private val logListener: () -> Unit = { renderLogSub() }

    /** 防止 onResume 反复触发自动同步 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSubStatus = findViewById(R.id.tvSubStatus)
        tvTodayNum = findViewById(R.id.tvTodayNum)
        dotStatus = findViewById(R.id.dotStatus)
        heroCard = findViewById(R.id.heroCard)
        todayBox = findViewById(R.id.todayBox)
        btnFix = findViewById(R.id.btnFix)
        tvLogSub = findViewById(R.id.tvLogSub)

        dotNotify = findViewById(R.id.dotNotify)
        dotOverlay = findViewById(R.id.dotOverlay)
        dotBattery = findViewById(R.id.dotBattery)

        swMaster = findViewById(R.id.swMaster)
        swSplash = findViewById(R.id.swSplash)
        swPopup = findViewById(R.id.swPopup)
        swGuard = findViewById(R.id.swGuard)

        rowSplash = findViewById(R.id.rowSplash)
        rowPopup = findViewById(R.id.rowPopup)
        rowGuard = findViewById(R.id.rowGuard)

        findViewById<TextView>(R.id.tvVersion).text = "v" + BuildConfig.VERSION_NAME

        // 远程规则地址仍然生效（预置/深链导入），但不再占界面：
        // 普通用户没有「规则源」这个概念，一个输入框 + 两个按钮只会让他犹豫。

        bindSwitches()

        btnFix.setOnClickListener {
            // 没真连上（含假开）就只能去系统设置，页内修不了
            if (AccessUtil.state(this) != AccessState.CONNECTED) openAccessibilitySettings()
            else fixFirstIssue()
        }

        // 自检/探测器不再放在界面上：对普通用户它只是一排看不懂的按钮。
        // 需要排查时，长按标题 1.5 秒即可进来，能力一点没少。
        findViewById<TextView>(R.id.tvAppName).setOnLongClickListener {
            toggleInspector()
            true
        }

        findViewById<View>(R.id.rowRules).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, RulesActivity::class.java)) }
            // 长按看「自动学习」攒下的记录，原来是挂在自动学习开关上的。
            setOnLongClickListener {
                showLearnedDialog()
                true
            }
        }

        findViewById<View>(R.id.rowLog).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
            // 长按直接导出：用户报「莫名其妙被弹回去」时，能一步把记录发出来。
            setOnLongClickListener {
                LogExport.share(this@MainActivity)
                true
            }
        }

        // 副标题本身就是「保活问题」的入口：点一下按当前缺什么去补什么
        tvSubStatus.setOnClickListener { onSubStatusClick() }

        // 三个权限点各自可点，缺哪个点哪个，不用在一次弹窗里连跳三步
        findViewById<View>(R.id.pillNotify).setOnClickListener { fixNotify() }
        findViewById<View>(R.id.pillOverlay).setOnClickListener { fixOverlay() }
        findViewById<View>(R.id.pillBattery).setOnClickListener { fixBattery() }

        ensureNotifyPermission()
        handleDeepLink(intent)
    }

    // ------------------------------------------------------------------ 保活 / 被系统冻结

    /**
     * 通知权限。
     *
     * 这不是「要不要弹通知」的小事：前台服务靠通知证明自己活着。
     * Android 13+ 把 POST_NOTIFICATIONS 变成运行时权限，用户拒绝后
     * 通知不显示，HyperOS 就会把本进程当成普通后台应用（UID 状态 CEM）
     * 直接冻结——无障碍服务还显示「已开启」，但一个事件都处理不了。
     */
    private fun notifyGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 电池优化白名单。没进白名单，后台一样会被 Doze / 冻结掐掉 */
    private fun batteryWhitelisted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(true)
    }

    private fun ensureNotifyPermission() {
        if (notifyGranted()) return
        requestNotify()
    }

    private fun requestNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    /** 按「最要紧的那一项」给出修复动作，顺序与状态卡文案保持一致 */
    private fun fixFirstIssue() {
        when {
            !notifyGranted() -> {
                requestNotify()
            }

            !AccessUtil.canDrawOverlay(this) -> {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                toast("开启「显示在其他应用上层」后返回，识别才稳定")
            }

            !batteryWhitelisted() -> skipBatteryOptimization()

            else -> showHyperOsTips()
        }
    }

    private fun fixNotify() {
        if (notifyGranted()) {
            toast("通知权限已开启")
            return
        }
        requestNotify()
    }

    private fun fixOverlay() {
        if (AccessUtil.canDrawOverlay(this)) {
            toast("悬浮窗权限已开启")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        toast("开启「显示在其他应用上层」后返回，识别才稳定")
    }

    private fun fixBattery() {
        if (batteryWhitelisted()) {
            toast("已在电池白名单里")
            return
        }
        skipBatteryOptimization()
    }

    private fun skipBatteryOptimization() {
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            toast("请手动找到「秒跳」，选择「不限制」")
        }
    }

    /**
     * 两项权限都正常还出问题，多半是小米自家的省电策略 / 自启动。
     * 这个 App 读不到也改不了，只能把路径写清楚让用户自己去关。
     */
    private fun showHyperOsTips() {
        AlertDialog.Builder(this)
            .setTitle("再检查两个系统开关")
            .setMessage(
                "路径：设置 → 应用 → 应用管理 → 秒跳\n\n" +
                    "1) 省电策略 → 选「无限制」\n" +
                    "2) 自启动 → 打开\n\n" +
                    "小米/红米默认只允许「仅在使用中」运行，切走后一分钟就会冻结，" +
                    "表现就是无障碍开着却不跳广告。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun onSubStatusClick() {
        // 假开也算「没连上」：这时候补权限没用，必须回去重开一次服务
        if (AccessUtil.state(this) != AccessState.CONNECTED) {
            openAccessibilitySettings()
            return
        }
        val healthy = notifyGranted() && AccessUtil.canDrawOverlay(this) && batteryWhitelisted()
        if (healthy) showHyperOsTips() else fixFirstIssue()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            refreshStatus()
            if (!notifyGranted()) toast("通知权限被拒，后台可能被系统冻结")
            else KeepAliveService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    /** 深链只提供便捷导入，必须经用户确认，避免网页静默替换点击规则。 */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "miaotiao" || data.host != "sync") return
        val url = data.getQueryParameter("url")?.trim().orEmpty()
        if (url.isEmpty()) {
            toast("缺少 url 参数")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("导入远程规则？")
            .setMessage("规则会控制自动点击，请只导入你信任的来源：\n\n$url")
            .setPositiveButton("信任并同步") { _, _ ->
                Prefs.remoteUrl = url
                syncRemote(force = true)
            }
            .setNegativeButton("取消", null)
            .show()
        intent.data = null
    }

    private fun bindSwitches() {
        swMaster.isChecked = Prefs.masterEnabled
        swSplash.isChecked = Prefs.skipSplash
        swPopup.isChecked = Prefs.closePopups
        swGuard.isChecked = Prefs.guardJump
        applyMasterDim(Prefs.masterEnabled)

        swMaster.setOnCheckedChangeListener { _, v ->
            Prefs.masterEnabled = v
            applyMasterDim(v)
            if (!v) JumpGuard.reset()
            refreshStatus()
        }
        swSplash.setOnCheckedChangeListener { _, v -> Prefs.skipSplash = v }
        swPopup.setOnCheckedChangeListener { _, v -> Prefs.closePopups = v }
        swGuard.setOnCheckedChangeListener { _, v ->
            Prefs.guardJump = v
            if (!v) JumpGuard.reset()
        }
        swGuard.setOnLongClickListener {
            showBlocklistDialog()
            true
        }
    }

    /**
     * 总开关关掉时，下面三项一起变淡并失能。
     *
     * 光把开关挪到「关」不够——三个子开关还亮着，用户会以为它们仍在工作。
     * 视觉上一起沉下去，「暂停」才是真的暂停。
     */
    private fun applyMasterDim(master: Boolean) {
        val alpha = if (master) 1f else 0.42f
        listOf(rowSplash, rowPopup, rowGuard).forEach {
            it.alpha = alpha
            it.isEnabled = master
        }
        listOf(swSplash, swPopup, swGuard).forEach { it.isEnabled = master }
    }

    override fun onStart() {
        super.onStart()
        SkipLog.observe(logListener)
        renderLogSub()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        autoSyncIfDue()
        // 用户很可能是刚从系统设置里授予悬浮窗权限回来的：
        // 微窗只在 onServiceConnected 挂一次，这里立刻让它补挂，不用等重启服务
        SkipAccessibilityService.instance?.retryGhostWindow()
    }

    override fun onStop() {
        SkipLog.remove(logListener)
        super.onStop()
    }

    // ------------------------------------------------------------------ 状态渲染

    private fun refreshStatus() {
        // 「授权还在」和「真的连上了」不是一回事（见 AccessUtil.state 的注释）。
        // 小米上最常见的故障就是 Settings 里名字还在、进程早被杀掉；
        // 以前这种情况首页照样显示绿色的「服务运行中」，用户截图发过来，
        // 开发者和用户谁看都以为没问题。
        val access = AccessUtil.state(this)
        val connected = access == AccessState.CONNECTED
        // 只有真连上才清「丢失」标记：中间态是假开，标记要留着
        if (connected) Prefs.clearServiceLost()

        val lost = access == AccessState.OFF && Prefs.serviceLost
        val master = Prefs.masterEnabled

        // 浏览器「无障碍开着但什么都不跳」的真正原因往往不是无障碍本身，
        // 而是进程被系统冻结（见 KeepAliveService 里通知重要度的注释）。
        val notifyOk = notifyGranted()
        val overlayOk = AccessUtil.canDrawOverlay(this)
        val batteryOk = batteryWhitelisted()

        // 文案优先级：总开关 > 无障碍 > 三项后台权限 > 正常。
        // 一次只让用户处理一件事，别把四个问题一起糊在脸上。
        // when 有 else 分支，两者必然被赋值，不需要默认值
        val state: String
        val sub: String
        var style = STYLE_WARN
        var fixText = getString(R.string.hero_fix)
        var needFix = true

        when {
            !master -> {
                state = "总开关已关闭"
                sub = "所有功能都已暂停，广告不会被跳过"
                style = STYLE_OFF
                needFix = false
            }

            access == AccessState.AUTHORIZED_NOT_CONNECTED -> {
                // 假开：系统面板上还是「已开启」，但服务进程已经没了。
                // 这一句必须说人话，否则用户会去系统里看一眼「明明是开的」
                // 然后认定是我们坏了。
                state = getString(R.string.hero_not_connected)
                sub = getString(R.string.hero_not_connected_sub)
                style = STYLE_WARN
                fixText = getString(R.string.hero_reopen)
            }

            access == AccessState.OFF && lost -> {
                state = "无障碍被系统关闭"
                sub = "常见原因是清理了后台"
                style = STYLE_WARN
                fixText = getString(R.string.hero_reopen)
            }

            access == AccessState.OFF -> {
                state = "无障碍没有开启"
                sub = "需要在系统设置里授权一次"
                style = STYLE_OFF
                fixText = "去开启"
            }

            !notifyOk -> {
                state = getString(R.string.hero_need_step)
                sub = "通知权限被关，后台会被冻结"
            }

            !overlayOk -> {
                state = getString(R.string.hero_need_step)
                sub = "悬浮窗权限没开，后台识别会失效"
            }

            !batteryOk -> {
                state = getString(R.string.hero_need_step)
                sub = "没进电池白名单，切走一分钟就可能失效"
            }

            else -> {
                state = getString(R.string.status_on)
                sub = getString(R.string.hero_ok_sub)
                style = STYLE_OK
                needFix = false
            }
        }

        tvStatus.text = state
        tvSubStatus.text = sub
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (style) {
                    STYLE_OK -> R.color.ok_text
                    STYLE_WARN -> R.color.warn_text
                    else -> R.color.text_secondary
                }
            )
        )
        heroCard.setBackgroundResource(
            when (style) {
                STYLE_OK -> R.drawable.bg_hero_ok
                STYLE_WARN -> R.drawable.bg_hero_warn
                else -> R.drawable.bg_hero_off
            }
        )
        setDot(dotStatus, if (style == STYLE_OK) R.color.ok else if (style == STYLE_WARN) R.color.warn else R.color.text_tertiary)

        // 数字和修复按钮二选一：坏了就先修，别让「今天跳了 12 次」分散注意力
        tvTodayNum.text = Prefs.todaySkipCount().toString()
        todayBox.visibility = if (needFix) View.GONE else View.VISIBLE
        btnFix.visibility = if (needFix) View.VISIBLE else View.GONE
        btnFix.text = fixText
        btnFix.setBackgroundTintList(
            ContextCompat.getColorStateList(
                this,
                if (!connected) R.color.brand else R.color.warn
            )
        )

        bindPill(dotNotify, notifyOk)
        bindPill(dotOverlay, overlayOk)
        bindPill(dotBattery, batteryOk)

        renderLogSub()
    }

    private fun bindPill(dot: View, ok: Boolean) {
        setDot(dot, if (ok) R.color.ok else R.color.warn)
    }

    /** fail-safe 于「今天跳过几次」这种每天的副标题，不需要精确到每一条 */
    private fun renderLogSub() {
        if (!::tvLogSub.isInitialized) return
        val today = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())
        val n = SkipLog.snapshot().count {
            it.length > 5 && it.startsWith(today) &&
                !it.contains("规则已加载") && !it.contains("无障碍服务已连接")
        }
        tvLogSub.text = if (n > 0) "今天 $n 条 · 点开看明细" else "看它今天做了什么"
    }

    private fun setDot(view: View, colorRes: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(ContextCompat.getColor(this, colorRes))
        view.background = d
    }

    // ------------------------------------------------------------------ 远程规则

    private fun syncRemote(force: Boolean) {
        if (syncing) return
        val url = Prefs.remoteUrl
        // 界面上没有输入框了，地址只能来自预置值或深链导入。
        // 没有就静默跳过，不打扰用户。
        if (url.isBlank()) return
        syncing = true

        Thread {
            val r = RemoteRules.sync(applicationContext, url, force = force)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                syncing = false
                if (r.ok) {
                    SkipAccessibilityService.instance?.reloadRules()
                } else {
                    toast(r.message)
                }
            }
        }.start()
    }

    private fun autoSyncIfDue() {
        if (syncing || !Prefs.autoSync || Prefs.remoteUrl.isBlank()) return
        val due = System.currentTimeMillis() - Prefs.lastSyncAt > 6 * 3600_000L
        if (due) syncRemote(force = false)
    }

    // ------------------------------------------------------------------ 自检

    private fun toggleInspector() {
        if (InspectorService.running) {
            stopService(Intent(this, InspectorService::class.java))
            toast("已关闭")
            return
        }
        if (AccessUtil.state(this) != AccessState.CONNECTED) {
            toast("请先开启无障碍服务")
            return
        }
        if (!AccessUtil.canDrawOverlay(this)) {
            toast("需要「显示在其他应用上层」权限")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        startService(Intent(this, InspectorService::class.java))
        toast("蓝色小球已显示：切到目标 App，点它就能自检")
    }

    /** 长按「规则管理」：查看 / 清空已经学到的规则 */
    private fun showLearnedDialog() {
        val count = LearnStore.learnedAppCount()
        AlertDialog.Builder(this)
            .setTitle("已学习 $count 个应用")
            .setMessage("自动学习到的规则优先级低于你手写的规则。如果发现某个 App 被点错了，可以清空重来。")
            .setPositiveButton("清空") { _, _ ->
                LearnStore.clear()
                SkipAccessibilityService.instance?.reloadRules()
                toast("已清空学习记录")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 长按「防跳转」开关：编辑额外的拦截包名 */
    private fun showBlocklistDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val input = EditText(this).apply {
            setText(Prefs.guardBlocklist)
            setHint("每行一个包名，例如 com.tencent.mtt")
            setPadding(pad, pad, pad, pad)
            minLines = 4
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("额外拦截的包名")
            .setMessage("默认已包含各大应用商店和浏览器。这里填的包名会在跳转时一并拦下。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                Prefs.guardBlocklist = input.text.toString()
                JumpGuard.reset()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
