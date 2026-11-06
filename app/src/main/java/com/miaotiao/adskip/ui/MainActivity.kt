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
import com.miaotiao.adskip.R
import com.miaotiao.adskip.data.LearnStore
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.net.RemoteRules
import com.miaotiao.adskip.service.InspectorService
import com.miaotiao.adskip.service.JumpGuard
import com.miaotiao.adskip.service.KeepAliveService
import com.miaotiao.adskip.service.SkipAccessibilityService
import com.miaotiao.adskip.util.AccessUtil
import com.miaotiao.adskip.util.SkipLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_NOTIFY = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var tvLog: TextView
    private lateinit var btnGrant: MaterialButton

    private lateinit var swMaster: MaterialSwitch
    private lateinit var swSplash: MaterialSwitch
    private lateinit var swPopup: MaterialSwitch
    private lateinit var swGuard: MaterialSwitch
    private lateinit var swAllApps: MaterialSwitch
    private lateinit var swLearn: MaterialSwitch

    private lateinit var etRemote: EditText
    private lateinit var tvSyncStatus: TextView

    private val logListener: () -> Unit = { renderLog() }

    /** 防止 onResume 反复触发自动同步 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSubStatus = findViewById(R.id.tvSubStatus)
        dotStatus = findViewById(R.id.dotStatus)
        tvLog = findViewById(R.id.tvLog)

        swMaster = findViewById(R.id.swMaster)
        swSplash = findViewById(R.id.swSplash)
        swPopup = findViewById(R.id.swPopup)
        swGuard = findViewById(R.id.swGuard)
        swAllApps = findViewById(R.id.swAllApps)
        swLearn = findViewById(R.id.swLearn)

        etRemote = findViewById(R.id.etRemote)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        etRemote.setText(Prefs.remoteUrl)

        bindSwitches()

        findViewById<MaterialButton>(R.id.btnGrant).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        btnGrant = findViewById(R.id.btnGrant)
        findViewById<MaterialButton>(R.id.btnInspector).setOnClickListener { toggleInspector() }
        findViewById<MaterialButton>(R.id.btnRules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener { syncRemote(force = true) }
        findViewById<MaterialButton>(R.id.btnClearRemote).setOnClickListener {
            RemoteRules.clear(this)
            SkipAccessibilityService.instance?.reloadRules()
            renderSyncStatus()
            toast("已清除远程规则缓存")
        }
        findViewById<TextView>(R.id.tvClearLog).setOnClickListener { SkipLog.clear() }
        // 状态副标题本身就是「保活问题」的修复入口：点一下就去对应的系统开关
        tvSubStatus.setOnClickListener { fixKeepAlive() }

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
        runCatching {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFY
            )
        }
    }

    private fun fixKeepAlive() {
        if (!notifyGranted()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        REQ_NOTIFY
                    )
                }
            }
            return
        }
        if (!AccessUtil.canDrawOverlay(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            toast("开启「显示在其他应用上层」后返回，识别才稳定")
            return
        }
        if (!batteryWhitelisted()) {
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
            }
            return
        }
        // 两项都正常：顺手提醒小米自家的两个开关，这个 App 读不到也改不了
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
                etRemote.setText(url)
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
        swAllApps.isChecked = Prefs.skipAllApps
        swLearn.isChecked = Prefs.autoLearn

        swMaster.setOnCheckedChangeListener { _, v ->
            Prefs.masterEnabled = v
            if (!v) JumpGuard.reset()
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
        swAllApps.setOnCheckedChangeListener { _, v -> Prefs.skipAllApps = v }
        swLearn.setOnCheckedChangeListener { _, v -> Prefs.autoLearn = v }
        swLearn.setOnLongClickListener {
            showLearnedDialog()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        SkipLog.observe(logListener)
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderSyncStatus()
        autoSyncIfDue()
        // 用户很可能是刚从系统设置里授予悬浮窗权限回来的：
        // 微窗只在 onServiceConnected 挂一次，这里立刻让它补挂，不用等重启服务
        SkipAccessibilityService.instance?.retryGhostWindow()
    }

    override fun onStop() {
        SkipLog.remove(logListener)
        super.onStop()
    }

    private fun refreshStatus() {
        val on = AccessUtil.isServiceEnabled(this)
        // 服务活着就把「丢失」标记清掉；被撤销时保留标记，给出明确原因
        if (on) Prefs.clearServiceLost()

        val lost = !on && Prefs.serviceLost

        // 浏览器「无障碍开着但什么都不跳」的真正原因往往不是无障碍本身，
        // 而是进程被系统冻结（见 KeepAliveService 里通知重要度的注释）。
        // 这两个开关没搞定，无障碍开着也是白开，所以优先级排在「已开启」前面。
        val notifyOk = notifyGranted()
        val overlayOk = AccessUtil.canDrawOverlay(this)
        val batteryOk = batteryWhitelisted()
        val keepAliveOk = notifyOk && overlayOk && batteryOk

        tvStatus.text = when {
            on -> getString(R.string.status_on)
            lost -> getString(R.string.service_lost_hint)
            else -> getString(R.string.status_off)
        }
        tvSubStatus.text = when {
            on && !notifyOk -> "⚠ 通知权限未开 → 后台会被系统冻结，广告跳不过（点这里修）"
            on && !overlayOk -> "⚠ 悬浮窗权限未开 → 后台识别会失效（点这里修）"
            on && !batteryOk -> "⚠ 未加入电池白名单 → 切走一分钟就可能失效（点这里修）"
            on -> "今日已跳过 ${Prefs.todaySkipCount()} 次"
            lost -> "点右侧按钮重新开启无障碍即可恢复"
            else -> "需要在系统设置里手动授权"
        }

        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        val healthy = on && keepAliveOk
        dot.setColor(ContextCompat.getColor(this, if (healthy) R.color.ok else R.color.warn))
        dotStatus.background = dot

        // 按钮在服务正常时隐藏，异常时高亮引导
        btnGrant.visibility = if (on) View.GONE else View.VISIBLE
        if (!on && lost) {
            btnGrant.setBackgroundColor(ContextCompat.getColor(this, R.color.warn))
        }
    }

    private fun renderLog() {
        if (!::tvLog.isInitialized) return
        runOnUiThread {
            val list = SkipLog.snapshot()
            tvLog.text = if (list.isEmpty()) "暂无记录" else list.joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ 远程规则

    private fun renderSyncStatus() {
        val stats = RemoteRules.cachedStats(this)
        val cached = stats?.records ?: 0
        val info = Prefs.lastSyncInfo
        val time = if (Prefs.lastSyncAt > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(Prefs.lastSyncAt))
        } else ""

        tvSyncStatus.text = when {
            Prefs.remoteUrl.isBlank() && cached == 0 -> "未配置远程地址，当前只用内置规则"
            cached == 0 -> if (info.isBlank()) "填地址后点「立即同步」" else info
            else -> "远程库 $cached 条（开屏 ${stats?.splashApps ?: 0} / 弹窗 ${stats?.popupApps ?: 0}）" +
                "${if (time.isNotBlank()) " · $time" else ""}\n" +
                "仅对手机里已安装且能匹配包名的应用生效" +
                if (info.isNotBlank() && !info.startsWith("同步成功")) "\n$info" else ""
        }
    }

    private fun syncRemote(force: Boolean) {
        if (syncing) return
        val url = etRemote.text.toString().trim().ifBlank { Prefs.remoteUrl }
        if (url.isBlank()) {
            toast("先填一个规则地址")
            return
        }
        Prefs.remoteUrl = url
        etRemote.setText(url)
        syncing = true
        tvSyncStatus.text = "同步中…"

        Thread {
            val r = RemoteRules.sync(applicationContext, url, force = force)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                syncing = false
                renderSyncStatus()
                if (r.ok) {
                    SkipAccessibilityService.instance?.reloadRules()
                    toast(r.message)
                } else {
                    toast(r.message)
                }
            }
        }.start()
    }

    private fun autoSyncIfDue() {
        if (syncing || !Prefs.autoSync || Prefs.remoteUrl.isBlank()) return
        val due = System.currentTimeMillis() - Prefs.lastSyncAt > 6 * 3600_000L
        if (due) {
            etRemote.setText(Prefs.remoteUrl)
            syncRemote(force = false)
        }
    }

    // ------------------------------------------------------------------ 自检

    private fun toggleInspector() {
        if (InspectorService.running) {
            stopService(Intent(this, InspectorService::class.java))
            toast("已关闭")
            return
        }
        if (!AccessUtil.isServiceEnabled(this)) {
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

    /** 长按「自动学习」开关：查看 / 清空已经学到的规则 */
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
