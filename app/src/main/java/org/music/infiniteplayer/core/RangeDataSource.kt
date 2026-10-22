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

    /** 길이 미지정 (C.LENGTH_UNSET 와 같은 값이지만 Long 타입) */
    const val LENGTH_UNSET = -1L

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

    /** 마지막 HTTP 응답 코드 (진단용) */
    @Volatile
    var lastHttpCode: Int = -1
        private set

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
        // 어떤 HTTP 코드였는지 남겨야 원인(403/416/…)을 알 수 있다
        throw androidx.media3.datasource.DataSourceException(
            "HTTP ${if (lastHttpCode > 0) lastHttpCode else "?"} (pos=$start, total=$total)",
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
    }

    private fun tryConnect(url: String, start: Long, length: Long, total: Long): HttpURLConnection? {
        var conn: HttpURLConnection? = null
        try {
            // 파일 크기를 아는데 시작 위치가 이미 끝을 넘었으면 요청 자체가 403 이 된다
            if (total > 0 && start >= total) return null
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.UA_YT)
                setRequestProperty("Accept", "*/*")
            }
            // 요청 끝을 반드시 파일 크기 안으로 자른다.
            // (ExoPlayer 는 청크 단위로 읽으므로 마지막 청크가 EOF 를 넘기기 쉽고,
            //  유튜브는 범위 끝이 파일 크기를 넘으면 403 을 준다 → IO_UNSPECIFIED)
            val wanted = if (length != LENGTH_UNSET) start + length - 1 else total - 1
            val end = if (total > 0) minOf(wanted, total - 1) else wanted
            if (end >= start) {
                conn.setRequestProperty("Range", "bytes=$start-$end")
            }
            val code = conn.responseCode
            lastHttpCode = code
            // 206(부분) 만 정상. 200 은 전체 응답이므로 중간 위치 요청에는 쓸 수 없다.
            if (code == 206 || (code == 200 && start == 0L)) return conn
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
    private var totalLength: Long = StreamRange.LENGTH_UNSET
    private var bytesRemaining: Long = 0
    private var absolutePosition: Long = 0
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri?.toString() ?: throw IOException("URL 없음")
        uri = dataSpec.uri
        currentSpec = dataSpec
        transferListener?.onTransferInitializing(this, dataSpec, true)

        // 이미 파일 끝을 지난 위치를 요청하면 유튜브가 403 을 준다.
        // (곡이 끝나는 순간 ExoPlayer 가 한 번 더 여는 경우가 있다)
        // 이때는 오류가 아니라 "읽을 데이터 없음"으로 처리한다.
        val known = StreamRange.contentLengthOf(url)
        if (known > 0 && dataSpec.position >= known) {
            connection = null
            totalLength = known
            absolutePosition = dataSpec.position
            bytesRemaining = 0
            responseHeaders = emptyMap()
            transferListener?.onTransferStart(this, dataSpec, true)
            return StreamRange.LENGTH_UNSET
        }

        val (conn, total) = StreamRange.open(url, dataSpec.position, dataSpec.length)
        connection = conn

        // 응답 헤더에서 실제 전체 길이를 다시 확인
        val range = conn.getHeaderField("Content-Range")
        val realTotal = range?.substringAfterLast("/")?.toLongOrNull()
        totalLength = if (realTotal != null && realTotal > 0) realTotal else total

        val available: Long = if (totalLength > 0) {
            (totalLength - dataSpec.position).coerceAtLeast(0)
        } else {
            StreamRange.LENGTH_UNSET
        }
        val remaining: Long = when {
            dataSpec.length != StreamRange.LENGTH_UNSET && available != StreamRange.LENGTH_UNSET ->
                minOf(dataSpec.length, available)
            dataSpec.length != StreamRange.LENGTH_UNSET -> dataSpec.length
            else -> available
        }

        bytesRemaining = remaining
        absolutePosition = dataSpec.position
        responseHeaders = conn.headerFields?.filterKeys { it != null } ?: emptyMap()
        transferListener?.onTransferStart(this, dataSpec, true)
        // 길이를 "모른다"고 알려준다(-1).
        // 숫자를 주면 ExoPlayer 가 그 만큼을 반드시 읽어야 하는 양으로 기대하고,
        // 유튜브가 곡 끝에서 연결을 먼저 끊으면 IO 오류로 처리해 재생이 멈춘다.
        // 길이 미지정이면 스트림이 끝나는 지점을 정상 종료로 본다.
        return StreamRange.LENGTH_UNSET
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) {
            currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
            return C.RESULT_END_OF_INPUT
        }
        val conn = connection ?: return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining > 0 && bytesRemaining < length) {
            bytesRemaining.toInt()
        } else {
            length
        }
        val n = try {
            conn.inputStream.read(buffer, offset, want)
        } catch (e: IOException) {
            // 곡 끝부분(마지막 512KB)에서 연결이 끊기면 정상 종료로 본다.
            // (유튜브는 마지막 바이트를 보낸 뒤 연결을 바로 닫는다)
            val left = if (totalLength > 0) totalLength - absolutePosition else Long.MAX_VALUE
            if (left <= 512 * 1024) {
                bytesRemaining = 0
                currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
                return C.RESULT_END_OF_INPUT
            }
            throw e
        }
        if (n == -1) {
            bytesRemaining = 0
            currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
            return C.RESULT_END_OF_INPUT
        }
        absolutePosition += n
        if (bytesRemaining > 0) bytesRemaining -= n
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

