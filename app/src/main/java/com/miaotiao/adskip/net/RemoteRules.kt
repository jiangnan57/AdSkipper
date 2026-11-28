package com.miaotiao.adskip.net

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.miaotiao.adskip.BuildConfig
import com.miaotiao.adskip.data.Prefs
import com.miaotiao.adskip.engine.RuleDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/** 远程规则同步；失败时永远保留上一份可用缓存。 */
object RemoteRules {

    private const val TAG = "AdSkip"
    private const val FILE_NAME = "remote_rules.json"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_RECORDS = 5000

    /** 一次同步（含换镜像重试）的总预算。4 个镜像各 8 秒会拖到半分钟，用户只会以为卡死 */
    private const val TOTAL_BUDGET_MS = 30_000L

    /**
     * 同一个文件的多路镜像，内容完全一样，只换传输通道。
     *
     * 顺序不是随便排的，它取决于这次是「自动同步」还是「用户手动点获取」：
     *
     * - 自动同步（后台每 6 小时一次）用 jsDelivr 打头：国内最快，
     *   反正晚十几个小时拿到也不影响什么。
     * - 手动同步必须拿到**刚推上去的那一版**。jsDelivr 对 `@main` 有约 12 小时缓存，
     *   排第一就会出现「我刚 push 完，点更新还说已是最新」——而且它每次都成功，
     *   后面三路永远轮不到。所以手动时把不吃 CDN 缓存的 raw / Pages 放前面。
     *
     * 代价是手动那一下可能要等两次超时（每路 6 秒）才落到 jsDelivr，
     * 但这本来就是低频操作，慢几秒换「拿到的一定是新版」值得。
     */
    private const val MIRROR_RAW =
        "https://raw.githubusercontent.com/jiangnan57/AdSkipper/main/rules_server/rules.json"
    private const val MIRROR_PAGES =
        "https://jiangnan57.github.io/AdSkipper/rules_server/rules.json"
    private const val MIRROR_CDN_FASTLY =
        "https://fastly.jsdelivr.net/gh/jiangnan57/AdSkipper@main/rules_server/rules.json"

    private fun mirrorsFor(force: Boolean): List<String> = if (force) {
        listOf(MIRROR_RAW, MIRROR_PAGES, Prefs.DEFAULT_REMOTE_URL, MIRROR_CDN_FASTLY)
    } else {
        listOf(Prefs.DEFAULT_REMOTE_URL, MIRROR_CDN_FASTLY, MIRROR_PAGES, MIRROR_RAW)
    }

    data class Result(
        val ok: Boolean,
        val message: String,
        val apps: Int = 0,
        val splashApps: Int = 0,
        val popupApps: Int = 0,
        /** 实际拿到数据的镜像域名，用于在界面上说清楚「从哪来的」 */
        val host: String = ""
    )

    /** 缓存里那份规则的元信息：版本、更新日期、条数 */
    data class Meta(val version: Int, val updatedAt: String, val records: Int)

    private data class Download(
        val body: String?,
        val etag: String?,
        val notModified: Boolean
    )

    fun cacheFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun cachedJson(ctx: Context): String? {
        val f = cacheFile(ctx)
        if (!f.exists() || f.length() == 0L || f.length() > MAX_BYTES) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun cachedStats(ctx: Context): RuleDocument.Stats? {
        val raw = cachedJson(ctx) ?: return null
        return runCatching { RuleDocument.stats(raw) }.getOrNull()
    }

    fun cachedApps(ctx: Context): Int = cachedStats(ctx)?.records ?: 0

    fun cachedMeta(ctx: Context): Meta? {
        val raw = cachedJson(ctx) ?: return null
        val stats = runCatching { RuleDocument.stats(raw) }.getOrNull() ?: return null
        return runCatching {
            val doc = RuleDocument.parse(raw)
            if (doc is org.json.JSONObject) {
                Meta(
                    version = doc.optInt("version", 0),
                    updatedAt = doc.optString("updatedAt", ""),
                    records = stats.records
                )
            } else {
                Meta(0, "", stats.records)
            }
        }.getOrNull()
    }

    /**
     * 远程规则是「可执行的界面指令」——关键词、点击动作、是否按返回，
     * 全都会直接作用在别人的 App 上。明文 http 传输意味着同一个 WiFi 下
     * 任何人都能把规则换成「在任何页面点这个位置」，所以只接受 https。
     * 本地调试请改用 adb push 或「规则管理」里的自定义规则。
     */
    private fun isSecure(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true)

    /** 阻塞式同步，必须放在子线程。多个地址按顺序作为镜像，首个有效来源生效。 */
    fun sync(ctx: Context, url: String, force: Boolean = false): Result {
        val candidates = candidates(url, force)
        if (candidates.isEmpty()) return Result(false, "没填规则地址")
        val invalid = candidates.firstOrNull { !isSecure(it) }
        if (invalid != null) {
            return Result(false, "规则地址必须以 https:// 开头（明文 http 可被中途篡改为任意点击指令）")
        }

        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        var lastError = "未知错误"
        for (candidate in candidates) {
            if (System.currentTimeMillis() > deadline) {
                lastError = "网络太慢，先停在这里"
                break
            }
            try {
                val download = download(candidate, force)
                val host = runCatching { URL(candidate).host }.getOrDefault("")
                if (download.notModified) {
                    val stats = cachedStats(ctx)
                    if (stats != null) {
                        val msg = describe("已是最新", ctx, stats)
                        finish(true, msg)
                        return Result(true, msg, stats.records, stats.splashApps, stats.popupApps, host)
                    }
                    throw IllegalStateException("服务端返回 304，但本地缓存不可用")
                }

                val body = download.body ?: throw IllegalArgumentException("下载内容为空")
                val stats = RuleDocument.stats(body)
                if (stats.records > MAX_RECORDS) {
                    throw IllegalArgumentException("规则超过 $MAX_RECORDS 条，已拒绝加载")
                }
                atomicWrite(ctx, body)

                Prefs.remoteEtag = download.etag.orEmpty()
                Prefs.remoteEtagUrl = if (download.etag.isNullOrBlank()) "" else candidate

                val msg = describe("已获取", ctx, RuleDocument.stats(body))
                finish(true, msg)
                Log.i(TAG, "remote rules synced: ${stats.records} records, ${body.length} chars, $host")
                return Result(true, msg, stats.records, stats.splashApps, stats.popupApps, host)
            } catch (t: Throwable) {
                // 这里必须接 Throwable，不只是 Exception：规则解析是递归下降，
                // 一份刻意构造的深层嵌套 JSON 会抛 StackOverflowError。
                // 它是 Error，漏出去就会被当成未捕获异常，直接把整个进程带走——
                // 而下一次打开还会再拉同一份文件，于是变成一开就崩。
                // 解析失败只是「这路镜像不可用」，继续试下一路才对。
                lastError = friendlyError(t)
                Log.i(TAG, "remote mirror failed: ${candidate.substringBefore('?')} -> $lastError")
            }
        }

        val kept = cachedApps(ctx)
        val msg = "同步失败：$lastError" + if (kept > 0) "（仍用上次 $kept 条）" else "（使用内置规则）"
        finish(false, msg)
        return Result(false, msg, kept)
    }

    private fun candidates(raw: String, force: Boolean): List<String> {
        val entered = raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (entered.isEmpty()) return emptyList()
        // 只有「用默认库」才展开镜像；用户自己填了地址就按他填的走
        return if (entered.size == 1 && entered[0] == Prefs.DEFAULT_REMOTE_URL) {
            mirrorsFor(force)
        } else entered
    }

    private fun download(url: String, force: Boolean): Download {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 10_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("User-Agent", "MiaoTiao/${BuildConfig.VERSION_NAME}")
                if (force) {
                    // 手动点「获取最新规则」时不能让中间层拿旧副本糊弄过去
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }
                if (!force && Prefs.remoteEtagUrl == url && Prefs.remoteEtag.isNotBlank()) {
                    setRequestProperty("If-None-Match", Prefs.remoteEtag)
                }
            }

            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED ->
                    return Download(null, conn.getHeaderField("ETag"), true)
                !in 200..299 -> throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            // instanceFollowRedirects 会一路跟到底，包括把 https 跟成 http。
            // 必须校验「最终」协议，否则 https 地址可以被降级成明文传输。
            if (!conn.url.protocol.equals("https", ignoreCase = true)) {
                throw IllegalArgumentException("地址被重定向到非加密连接，已拒绝")
            }
            if (conn.contentLengthLong > MAX_BYTES) throw IllegalArgumentException("规则文件超过 4MB")

            val raw = conn.inputStream
            val input = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else raw
            val bytes = input.use { readLimited(it) }
            return Download(String(bytes, Charsets.UTF_8), conn.getHeaderField("ETag"), false)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IllegalArgumentException("规则文件超过 4MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun atomicWrite(ctx: Context, body: String) {
        val atomic = AtomicFile(cacheFile(ctx))
        val stream = atomic.startWrite()
        try {
            stream.write(body.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    /**
     * 一句话说清结果。开屏/弹窗的明细只写进日志，不进这句话：
     * 卡片上就一行字的位置，带上「（开屏 20 / 弹窗 2）」必换行，
     * 换行之后那个孤零零的「）」比没有这条信息更难看。
     */
    private fun describe(prefix: String, ctx: Context, stats: RuleDocument.Stats): String {
        val ver = cachedMeta(ctx)?.version ?: 0
        val v = if (ver > 0) " v$ver" else ""
        return "$prefix$v · ${stats.records} 条"
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is UnknownHostException -> "域名解析失败，请检查网络"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "HTTPS 证书校验失败"
        is IllegalArgumentException -> e.message ?: "规则格式不正确"
        is IllegalStateException -> e.message ?: "网络响应异常"
        is OutOfMemoryError -> "规则文件太大，内存不足"
        is StackOverflowError -> "规则格式异常，已跳过"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }

    fun clear(ctx: Context) {
        runCatching { AtomicFile(cacheFile(ctx)).delete() }
        Prefs.remoteEtag = ""
        Prefs.remoteEtagUrl = ""
        Prefs.lastSyncInfo = "已清除远程规则"
    }

    private fun finish(ok: Boolean, msg: String) {
        if (ok) Prefs.lastSyncAt = System.currentTimeMillis()
        Prefs.lastSyncInfo = msg
    }
}
