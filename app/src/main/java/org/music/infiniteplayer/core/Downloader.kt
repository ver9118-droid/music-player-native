package org.music.infiniteplayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 스트림 직접 재생이 실패했을 때의 대체 경로.
 *  - 오디오를 로컬 파일로 받아서 플레이한다.
 *  - InnerTube 요청과 같은 HTTP 스택( HttpURLConnection )을 쓰기 때문에
 *    스트리밍 실패(예: IP 바인딩 403) 상황에서도 성공할 확률이 높다.
 */
object Downloader {

    suspend fun download(
        url: String,
        dest: File,
        onProgress: ((percent: Int) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.UA_YT)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (onProgress != null && total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            dest.length() > 1024
        } catch (_: Exception) {
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
