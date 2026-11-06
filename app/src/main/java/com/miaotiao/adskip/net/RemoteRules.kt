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

    private val COMMUNITY_MIRRORS = listOf(
        Prefs.DEFAULT_REMOTE_URL,
        "https://cdn.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/BasicRules.json",
        "https://fastly.jsdelivr.net/gh/Snoopy1866/LiTiaotiao-Custom-Rules@main/BasicRules.json",
        "https://raw.githubusercontent.com/Snoopy1866/LiTiaotiao-Custom-Rules/main/BasicRules.json"
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val apps: Int = 0,
        val splashApps: Int = 0,
        val popupApps: Int = 0
    )

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
        val candidates = candidates(url)
        if (candidates.isEmpty()) return Result(false, "没填规则地址")
        val invalid = candidates.firstOrNull { !isSecure(it) }
        if (invalid != null) {
            return Result(false, "规则地址必须以 https:// 开头（明文 http 可被中途篡改为任意点击指令）")
        }

        var lastError = "未知错误"
        for (candidate in candidates) {
            try {
                val download = download(candidate, force)
                if (download.notModified) {
                    val stats = cachedStats(ctx)
                    if (stats != null) {
                        val msg = describe("已是最新", stats)
                        finish(true, msg)
                        return Result(true, msg, stats.records, stats.splashApps, stats.popupApps)
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

                val msg = describe("同步成功", stats)
                finish(true, msg)
                Log.i(TAG, "remote rules synced: ${stats.records} records, ${body.length} chars")
                return Result(true, msg, stats.records, stats.splashApps, stats.popupApps)
            } catch (e: Exception) {
                lastError = friendlyError(e)
                Log.i(TAG, "remote mirror failed: ${candidate.substringBefore('?')} -> $lastError")
            }
        }

        val kept = cachedApps(ctx)
        val msg = "同步失败：$lastError" + if (kept > 0) "（仍用上次 $kept 条）" else "（使用内置规则）"
        finish(false, msg)
        return Result(false, msg, kept)
    }

    private fun candidates(raw: String): List<String> {
        val entered = raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (entered.isEmpty()) return emptyList()
        return if (entered.size == 1 && entered[0] == Prefs.DEFAULT_REMOTE_URL) {
            COMMUNITY_MIRRORS
        } else entered
    }

    private fun download(url: String, force: Boolean): Download {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("User-Agent", "MiaoTiao/${BuildConfig.VERSION_NAME}")
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

    private fun describe(prefix: String, stats: RuleDocument.Stats): String =
        "$prefix：${stats.records} 条（开屏 ${stats.splashApps} / 弹窗 ${stats.popupApps}）"

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "域名解析失败，请检查网络"
        is SocketTimeoutException -> "连接或读取超时"
        is SSLException -> "HTTPS 证书校验失败"
        is IllegalArgumentException -> e.message ?: "规则格式不正确"
        is IllegalStateException -> e.message ?: "网络响应异常"
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
