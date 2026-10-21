package org.music.infiniteplayer.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
        var conn: java.net.HttpURLConnection? = null
        try {
            // 유튜브 서버는 Range 없는 요청/열린 범위를 403 으로 막는다.
            // 반드시 닫힌 범위로 요청한다.
            val (connection, total) = StreamRange.open(url, 0, StreamRange.LENGTH_UNSET)
            conn = connection
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
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

