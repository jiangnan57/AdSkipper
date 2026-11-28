package com.miaotiao.adskip.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「最近记录」——内存环形日志 + **落盘**。
 *
 * 为什么要落盘（v1.3.5）：
 *
 * 之前只有内存环形（120 条），进程一被杀就全没了。而「用户莫名其妙被弹回去」
 * 这类问题的取证恰恰最难：
 *
 * · 用户反馈时通常已经过去几分钟甚至几小时，`adb logcat` 的环形缓冲早就滚掉
 *   （HyperOS 上更狠，经常 `logcat -c` 之后就是 0 行，缓冲区被清空）。
 * · 进程被系统回收 / 用户上滑清理后重启，内存日志直接归零。
 *
 * 实测就吃过这个亏：用户报「12:08 番茄小说从书页退回书架」，
 * 12:52 去取日志时缓冲最早已是 12:12:06，关键现场完全丢失，
 * 只能靠代码静态审查反推。
 *
 * 现在每条记录都会异步追加到 `filesDir/skiplog.txt`，进程重启后自动载回，
 * 下次再出问题直接看首页的「最近记录」或 `adb pull` 这个文件就够了。
 */
object SkipLog {

    /** 内存里保留多少条（首页列表用） */
    private const val MAX = 200

    /** 落盘文件里保留多少条（比内存多，方便回溯更早的现场） */
    private const val MAX_FILE_LINES = 800

    private const val FILE_NAME = "skiplog.txt"

    /** 两次落盘的最小间隔：日志可能一秒来好几条，不能每条都写盘 */
    private const val FLUSH_INTERVAL_MS = 2_000L

    private val items = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private var file: File? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "adskip-log").apply { isDaemon = true }
    }

    private var lastFlushAt = 0L

    /**
     * 回调必须回主线程。
     *
     * 这个类的调用方遍布三个线程：主线程（服务连接、界面操作）、扫描线程
     * （跳过 / 关弹窗）、规则加载线程。而监听者全是界面（首页副标题、
     * 记录页列表）。原来直接 `listeners.forEach { it() }`，于是扫描线程
     * 写一条日志就会在扫描线程上碰 TextView —— 抛
     * `CalledFromWrongThreadException`，又被外层的 runCatching 吞掉：
     * 界面不刷新，日志里还什么都没有，看起来就是「这个功能时好时坏」。
     *
     * 现在统一 post 到主线程，并用一个 pending 标记合并同一帧内的多次通知：
     * 一次跳广告可能连着写三四条，没必要重绘四遍。
     */
    private var mainHandler: Handler? = null
    private val notifyPending = AtomicBoolean(false)

    /**
     * 绑定落盘位置并把历史记录载回内存。重复调用无副作用。
     *
     * 必须在 Application 或 Service 起来时尽早调用，否则崩溃前的记录
     * 写不进文件——而要查的恰恰就是「刚出事那一瞬间」。
     */
    @Synchronized
    fun install(ctx: Context) {
        if (mainHandler == null) {
            mainHandler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        }
        if (file != null) return
        val f = File(ctx.filesDir, FILE_NAME)
        file = f
        runCatching {
            if (!f.exists() || f.length() == 0L) return@runCatching
            // 文件按「旧 → 新」写的，载入时反转成「新 → 旧」放进队首
            val lines = f.readLines(Charsets.UTF_8).takeLast(MAX)
            lines.asReversed().forEach { if (it.isNotBlank()) items.addLast(it) }
        }
    }

    /** 落盘文件，供「导出记录」或 adb pull 使用；未 install 时为 null */
    fun file(): File? = file

    @Synchronized
    fun add(msg: String) {
        items.addFirst(fmt.format(Date()) + "  " + msg)
        while (items.size > MAX) items.removeLast()
        notifyListeners()
        maybeFlush()
    }

    @Synchronized
    fun snapshot(): List<String> = items.toList()

    @Synchronized
    fun clear() {
        items.clear()
        notifyListeners()
        val f = file ?: return
        io.execute { runCatching { if (f.exists()) f.delete() } }
    }

    /**
     * 通知全部监听者。**永远在主线程执行**（见 [mainHandler] 的注释）。
     *
     * 没有主线程 Handler（JVM 单测里 install 不会被调用）时退化成同步调用，
     * 单测里那点逻辑不涉及界面，行为与以前一致。
     */
    private fun notifyListeners() {
        val handler = mainHandler
        if (handler == null) {
            listeners.toList().forEach { runCatching { it() } }
            return
        }
        // 已经排了一次就不再排：一次扫描写三四条日志不该重绘三四遍
        if (!notifyPending.compareAndSet(false, true)) return
        handler.post {
            notifyPending.set(false)
            // 取快照时重新加锁：期间可能有界面 onStop 注销了监听
            val snapshot = synchronized(this) { listeners.toList() }
            snapshot.forEach { runCatching { it() } }
        }
    }

    @Synchronized
    fun observe(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun remove(l: () -> Unit) {
        listeners.remove(l)
    }

    /**
     * 节流落盘。
     *
     * 只做「距上次写盘超过 2 秒才写」，不做批量合并：日志量本来就很小
     * （一次事故几十条），多写几次比丢现场划算得多。
     * 代价是进程被强杀时可能丢最后 2 秒——用 [add] 的同步性换不来更好的方案，
     * 每条都 fsync 反而会把主线程拖慢。
     */
    private fun maybeFlush() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_INTERVAL_MS) return
        lastFlushAt = now
        val lines = items.toList() // 新 → 旧
        io.execute { writeOut(f, lines) }
    }

    /** 强制立即写盘（App 退到后台、服务销毁前调用） */
    fun flushNow() {
        val f = file ?: return
        val lines = synchronized(this) { items.toList() }
        io.execute { writeOut(f, lines) }
    }

    private fun writeOut(f: File, newestFirst: List<String>) {
        runCatching {
            if (newestFirst.isEmpty()) return@runCatching
            val text = newestFirst.asReversed().takeLast(MAX_FILE_LINES).joinToString("\n")
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            // 直接 rename 覆盖：Android 上同一分区内 rename 是原子的
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
