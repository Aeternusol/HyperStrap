package com.faststrap.dev

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads flag files from https links. */
object UrlImport {
    const val MAX_BYTES = 1_000_000

    class Result(val text: String?, val error: String?)

    /**
     * Accepts only https links, and turns common "view" links into raw-file links:
     * GitHub blob pages and pastebin.com pages. Returns null when the link isn't usable.
     */
    fun normalize(raw: String): String? {
        val u = raw.trim()
        if (!u.startsWith("https://")) return null
        val uri = Uri.parse(u)
        val host = uri.host ?: return null
        val segs = uri.pathSegments
        if (host == "github.com" && segs.size >= 5 && segs[2] == "blob") {
            return "https://raw.githubusercontent.com/${segs[0]}/${segs[1]}/" + segs.drop(3).joinToString("/")
        }
        if (host == "pastebin.com" && segs.size == 1) {
            return "https://pastebin.com/raw/${segs[0]}"
        }
        return u
    }

    fun host(url: String): String = Uri.parse(url).host ?: url

    /** Blocking: call from a background thread. */
    fun download(url: String): Result {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Stargazer")
            val code = conn.responseCode
            if (code != 200) return Result(null, "The server answered HTTP $code.")
            if (conn.contentLengthLong > MAX_BYTES) return Result(null, "That file is too large.")
            val buf = ByteArray(8192)
            val bytes = ByteArrayOutputStream()
            var total = 0
            conn.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return Result(null, "That file is too large.")
                    bytes.write(buf, 0, n)
                }
            }
            return Result(String(bytes.toByteArray(), Charsets.UTF_8), null)
        } catch (e: Exception) {
            return Result(null, e.message ?: e.toString())
        } finally {
            conn?.disconnect()
        }
    }
}
