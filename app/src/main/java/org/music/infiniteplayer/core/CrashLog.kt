package org.music.infiniteplayer.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 예외 핸들러.
 *
 *  - 스택트레이스를 파일로 남긴다 (진단 화면에서 확인/복사 가능)
 *  - 음악 재생이 계속되도록 앱을 죽이지 않는다 (백그라운드 스레드 예외)
 */
object CrashLog {

    private const val MAX_SIZE = 60 * 1024

    @Volatile
    private var file: File? = null

    @Volatile
    private var externalFile: File? = null

    fun install(context: Context) {
        try {
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            file = File(dir, "crash.txt")
        } catch (_: Exception) {
        }
        try {
            // 사용자가 파일관리자로 볼 수 있는 위치에도 저장
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                ext.mkdirs()
                externalFile = File(ext, "crash.txt")
            }
        } catch (_: Exception) {
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = android.os.Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val text = buildString {
                append("시간: ").append(java.util.Date()).append('\n')
                append("스레드: ").append(thread.name).append('\n')
                append("버전: ").append(BuildInfo.VERSION).append('\n')
                append('\n')
                try {
                    val sw = StringWriter()
                    PrintWriter(sw).use { error.printStackTrace(it) }
                    append(sw.toString())
                } catch (e: Exception) {
                    append("(스택트레이스 기록 실패: ").append(e.message).append(")")
                }
            }
            save(text)
            if (thread === mainThread) {
                // 메인 스레드 예외는 기본 동작 유지 (시스템 크래시 창)
                // → 로그가 저장되므로 다음 실행에서 진단 화면으로 확인 가능
                previous?.uncaughtException(thread, error)
            }
            // 백그라운드 스레드 예외는 앱을 죽이지 않는다 (재생 유지)
        }
    }

    private fun save(text: String) {
        val trimmed = if (text.length > MAX_SIZE) text.takeLast(MAX_SIZE) else text
        try {
            file?.writeText(trimmed)
        } catch (_: Exception) {
        }
        try {
            externalFile?.writeText(trimmed)
        } catch (_: Exception) {
        }
    }

    /** 텍스트를 로그 파일에 남긴다 (기존 내용 뒤에 덧붙임) */
    fun saveText(text: String) {
        try {
            val prev = try {
                file?.takeIf { it.exists() }?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            save(prev + "\n\n" + text)
        } catch (_: Exception) {
        }
    }

    /** 예외를 로그 파일에 남긴다 */
    fun save(error: Throwable) {        try {
            val sw = StringWriter()
            PrintWriter(sw).use { error.printStackTrace(it) }
            save(
                buildString {
                    append("시간: ").append(java.util.Date()).append('\n')
                    append('\n').append(sw.toString())
                }
            )
        } catch (_: Exception) {
        }
    }

    /** 최근 크래시 로그 (없으면 빈 문자열) */
    fun read(): String {
        return try {
            file?.takeIf { it.exists() && it.length() > 0 }?.readText().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        try {
            file?.delete()
        } catch (_: Exception) {
        }
        try {
            externalFile?.delete()
        } catch (_: Exception) {
        }
    }

    /** 앱 버전 정보 */
    object BuildInfo {
        const val VERSION = "2.1.3"
    }
}

