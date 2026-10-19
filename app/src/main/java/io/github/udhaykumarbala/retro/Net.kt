package io.github.udhaykumarbala.retro

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server answered HTTP 429 (rate limited); worth retrying later. */
class BusyException(val host: String) : IOException("HTTP 429 from $host")

/** Minimal HTTP download with manual redirects (HttpURLConnection will not follow http to https). */
object Net {
    private const val USER_AGENT = "Retro/0.1 (Android TV; +https://github.com/udhaykumarbala)"

    /**
     * Streams [url] into [target]. [onProgress] gets 0..1, or -1 when the size is unknown.
     * [cancelled] is polled between chunks.
     */
    fun download(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        var current = url
        var conn: HttpURLConnection? = null
        try {
            for (hop in 0 until 6) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    current = URL(URL(current), loc).toString()
                    c.disconnect()
                    continue
                }
                if (code == 429) {
                    c.disconnect()
                    throw BusyException(URL(current).host)
                }
                if (code !in 200..299) {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                conn = c
                break
            }
            val c = conn ?: throw IOException("too many redirects")
            val total = c.contentLengthLong
            if (total > maxBytes) throw IOException("file too large")
            var done = 0L
            var lastReported = -1
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > maxBytes) throw IOException("file too large")
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** itch.io download links (https://<author>.itch.io/<game>/file/<upload_id>) need a POST first. */
    fun isItch(url: String): Boolean = try {
        val u = URL(url)
        u.host.endsWith(".itch.io", ignoreCase = true) && u.path.contains("/file/")
    } catch (e: Exception) {
        false
    }

    /**
     * Resolves an itch.io file link the way the Download button does: an empty POST returns JSON
     * {"url": "<signed url>"}. The signed URL expires in about 60 seconds, so fetch it right away.
     */
    fun resolveItch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            val code = c.responseCode
            if (code == 429) throw BusyException(c.url.host)
            if (code !in 200..299) throw IOException("itch.io HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val signed = JSONObject(body).optString("url")
            if (signed.isEmpty()) throw IOException("itch.io gave no download link")
            return signed
        } finally {
            c.disconnect()
        }
    }
}
