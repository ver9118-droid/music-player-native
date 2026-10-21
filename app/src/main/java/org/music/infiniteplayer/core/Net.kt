package org.music.infiniteplayer.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 아주 가벼운 HTTP 클라이언트.
 *
 * okhttp/retrofit 을 넣지 않는 이유
 *  - 앱 시작 속도(콜드 스타트)에 영향을 주지 않기 위해서
 *  - APK 크기를 최소화하기 위해서
 *  - 필요한 기능(GET/POST + gzip) 은 40줄로 충분하기 때문
 */
object Net {

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val UA_YT =
        "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"

    private const val TIMEOUT = 15_000

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, "GET", headers)
        try {
            return String(readAll(conn), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = open(url, "GET", headers)
        try {
            return readAll(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, "POST", headers)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return String(readAll(conn), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Connection", "close")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readAll(conn: HttpURLConnection): ByteArray {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = try {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (_: Exception) {
                ""
            }
            throw IOException("HTTP $code ${err.take(180)}")
        }
        val raw: InputStream = BufferedInputStream(conn.inputStream)
        val encoding = (conn.contentEncoding ?: "").lowercase()
        val stream = if (encoding.contains("gzip")) GZIPInputStream(raw) else raw
        return stream.use { it.readBytes() }
    }
}
