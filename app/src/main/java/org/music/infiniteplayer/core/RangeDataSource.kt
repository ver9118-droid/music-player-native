package org.music.infiniteplayer.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 유튜브(googlevideo) 스트림 전용 HTTP 유틸.
 *
 * 서버 규칙 (실측):
 *   - Range 헤더가 없으면              → 403
 *   - Range 끝이 열려있으면("bytes=0-") → 403
 *   - Range 끝이 파일 크기를 넘으면      → 403
 *   - Range 가 파일 안에 닫혀있으면      → 206 정상
 *
 * 즉 반드시 "bytes=시작-끝" 형태로 닫힌 범위를 보내야 한다.
 * 파일 크기는 URL 의 clen= 파라미터에 들어있고, 없으면 1 바이트 요청으로 알아낸다.
 */
object StreamRange {

    /** URL 의 clen= 파라미터에서 전체 길이 (없으면 -1) */
    fun contentLengthOf(url: String): Long {
        val idx = url.indexOf("clen=")
        if (idx < 0) return -1L
        var end = idx + 5
        while (end < url.length && url[end].isDigit()) end++
        return url.substring(idx + 5, end).toLongOrNull() ?: -1L
    }

    /** 1 바이트 요청으로 전체 길이를 알아낸다 */
    fun probeLength(url: String): Long {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.UA_YT)
                setRequestProperty("Range", "bytes=0-0")
            }
            val code = conn.responseCode
            if (code != 206 && code != 200) return -1L
            val range = conn.getHeaderField("Content-Range")
            val total = range?.substringAfterLast("/")?.toLongOrNull()
            if (total != null && total > 0) return total
            val len = conn.contentLengthLong
            return if (len > 0) len else -1L
        } catch (_: Exception) {
            return -1L
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 닫힌 Range 로 연결을 연다. 실패하면 길이를 다시 알아내어 1회 재시도한다. */
    fun open(url: String, start: Long, length: Long): Pair<HttpURLConnection, Long> {
        var total = contentLengthOf(url)
        if (total <= 0) total = probeLength(url)

        var conn = tryConnect(url, start, length, total)
        if (conn != null) return conn to total

        // 길이 정보가 틀렸을 수 있으므로 다시 알아내고 재시도
        val real = probeLength(url)
        if (real > 0) {
            total = real
            conn = tryConnect(url, start, length, total)
            if (conn != null) return conn to total
        }
        throw IOException("스트림을 열 수 없습니다")
    }

    private fun tryConnect(url: String, start: Long, length: Long, total: Long): HttpURLConnection? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.UA_YT)
                setRequestProperty("Accept", "*/*")
            }
            val end = if (length != DataSpec.LENGTH_UNSET) start + length - 1 else total - 1
            if (end >= start) {
                conn.setRequestProperty("Range", "bytes=$start-$end")
            }
            val code = conn.responseCode
            if (code == 200 || code == 206) return conn
            conn.disconnect()
            return null
        } catch (_: Exception) {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
            return null
        }
    }
}

/**
 * ExoPlayer 용 DataSource.
 *
 * ExoPlayer 기본 DataSource 는 초기 요청에 "bytes=0-" (열린 범위)를 보내서
 * 유튜브 서버에 403 을 받는다.  그래서 직접 구현해서
 * 항상 닫힌 범위로 요청하도록 한다.
 */
class RangeDataSource : DataSource {

    private var connection: HttpURLConnection? = null
    private var transferListener: TransferListener? = null
    private var uri: Uri? = null
    private var currentSpec: DataSpec? = null
    private var totalLength: Long = DataSpec.LENGTH_UNSET
    private var bytesRemaining: Long = 0
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri?.toString() ?: throw IOException("URL 없음")
        uri = dataSpec.uri
        currentSpec = dataSpec
        transferListener?.onTransferInitializing(this, dataSpec, true)

        val (conn, total) = StreamRange.open(url, dataSpec.position, dataSpec.length)
        connection = conn

        // 응답 헤더에서 실제 전체 길이를 다시 확인
        val range = conn.getHeaderField("Content-Range")
        val realTotal = range?.substringAfterLast("/")?.toLongOrNull()
        if (realTotal != null && realTotal > 0) totalLength = realTotal else totalLength = total

        bytesRemaining = if (dataSpec.length != DataSpec.LENGTH_UNSET) {
            dataSpec.length
        } else {
            val contentLen = conn.contentLengthLong
            if (contentLen > 0) contentLen else (totalLength - dataSpec.position).coerceAtLeast(0)
        }
        responseHeaders = conn.headerFields?.filterKeys { it != null } ?: emptyMap()
        transferListener?.onTransferStart(this, dataSpec, true)
        return totalLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val conn = connection ?: throw IOException("닫힌 데이터 소스")
        val n = conn.inputStream.read(buffer, offset, length)
        if (n == -1) {
            bytesRemaining = 0
            currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= n
        currentSpec?.let { transferListener?.onBytesTransferred(this, it, true, n) }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }
        connection = null
        bytesRemaining = 0
    }

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    /** ExoPlayer 에서 쓰는 DataSource.Factory */
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = RangeDataSource()
    }
}
