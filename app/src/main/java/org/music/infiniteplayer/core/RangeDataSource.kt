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

    /**
     * 재생 전에 주소가 살아있는지 1바이트만 받아 확인한다.
     *
     * 유튜브는 영상에 따라 "주소는 주지만 실제 요청은 403" 인 경우가 있다.
     * (모음/플레이리스트 영상, 일부 봇 차단 대상)
     * 이런 주소를 플레이어에 넘기면 "버퍼링 → 403 오류" 로 멈추므로,
     * 여기서 미리 걸러서 다음 클라이언트/다음 곡으로 넘어가게 한다.
     *
     * @return true = 재생 가능(또는 판단 불가), false = 확실히 막힘(4xx)
     */
    fun probeOk(url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.UA_YT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Range", "bytes=0-0")
            }
            val code = conn.responseCode
            lastHttpCode = code
            // 403/404/410 처럼 "확실히 막힌" 경우만 실패로 본다.
            // 시간 초과·연결 오류는 일시적일 수 있으므로 재생을 시도한다.
            code !in 400..499
        } catch (_: Exception) {
            true
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
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
            // 요청 끝을 "항상" 닫힌 범위로 만든다.
            // 유튜브는 Range 헤더가 없거나 "bytes=0-" 같은 열린 범위면 403 을 준다.
            // 길이를 모르는 경우에도 넉넉한 닫힌 범위로 요청하고,
            // 실제 전체 길이는 응답의 Content-Range 에서 알아낸다.
            val chunk = 8L * 1024 * 1024
            val end = when {
                total > 0 && length != LENGTH_UNSET -> minOf(start + length - 1, total - 1)
                total > 0 -> total - 1
                length != LENGTH_UNSET -> start + length - 1
                else -> start + chunk - 1
            }
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
    private var reopenCount = 0
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
        reopenCount = 0
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
            // 끊겼으면 같은 위치에서 이어받기를 1회 시도한다
            if (reopenCount < 3 && reopenAt(absolutePosition)) {
                return read(buffer, offset, length)
            }
            // 곡 끝부분(마지막 512KB)에서 연결이 끊기면 정상 종료로 본다.
            // (유튜브는 마지막 바이트를 보낸 뒤 연결을 바로 닫는다)
            val left = if (totalLength > 0) totalLength - absolutePosition else Long.MAX_VALUE
            if (left <= 512 * 1024) {
                bytesRemaining = 0
                currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
                return C.RESULT_END_OF_INPUT
            }
            throw e
        } catch (e: RuntimeException) {
            // 연결 상태가 깨진 경우 (IllegalStateException 등)
            if (reopenCount < 3 && reopenAt(absolutePosition)) {
                return read(buffer, offset, length)
            }
            val left = if (totalLength > 0) totalLength - absolutePosition else Long.MAX_VALUE
            if (left <= 512 * 1024) {
                bytesRemaining = 0
                currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
                return C.RESULT_END_OF_INPUT
            }
            throw IOException("스트림 읽기 실패 (${e.javaClass.simpleName})", e)
        }
        if (n == -1) {
            // 받은 범위가 끝났는데 파일이 남아 있으면 이어서 받는다
            val more = totalLength > 0 && absolutePosition < totalLength
            if (more && reopenCount < 3 && reopenAt(absolutePosition)) {
                return read(buffer, offset, length)
            }
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

    /**
     * 같은 위치에서 새 연결을 열어 이어받는다.
     * (요청 범위가 끝났는데 파일이 남아 있거나, 연결이 끊긴 경우)
     */
    private fun reopenAt(position: Long): Boolean {
        val spec = currentSpec ?: return false
        val url = spec.uri?.toString() ?: return false
        return try {
            val (conn, total) = StreamRange.open(url, position, StreamRange.LENGTH_UNSET)
            val range = conn.getHeaderField("Content-Range")
            val realTotal = range?.substringAfterLast("/")?.toLongOrNull()
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
            connection = conn
            totalLength = when {
                realTotal != null && realTotal > 0 -> realTotal
                total > 0 -> total
                else -> StreamRange.LENGTH_UNSET
            }
            absolutePosition = position
            bytesRemaining = if (totalLength > 0) {
                (totalLength - position).coerceAtLeast(0)
            } else {
                StreamRange.LENGTH_UNSET
            }
            reopenCount++
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        // 정리 과정에서 예외가 나도 절대 밖으로 던지지 않는다.
        // (ExoPlayer 스레드에서 IllegalStateException 이 올라오면
        //  "Source error" 로 재생이 통째로 실패한다)
        try {
            currentSpec?.let { transferListener?.onTransferEnd(this, it, true) }
        } catch (_: Throwable) {
        }
        try {
            connection?.disconnect()
        } catch (_: Throwable) {
        }
        connection = null
        bytesRemaining = 0
        currentSpec = null
    }

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    /**
     * ExoPlayer 에서 쓰는 DataSource.Factory.
     *
     * http/https 는 이 클래스가 처리하고(유튜브 403 회피용 닫힌 Range),
     * file:// · content:// (기기에 저장한 곡) 는 [androidx.media3.datasource.DefaultDataSource]
     * 가 처리하도록 넘긴다.
     */
    class Factory(private val context: android.content.Context) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            androidx.media3.datasource.DefaultDataSource(context, RangeDataSource())
    }
}

