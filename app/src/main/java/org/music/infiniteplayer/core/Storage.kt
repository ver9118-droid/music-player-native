package org.music.infiniteplayer.core

import android.content.Context
import java.io.File

/**
 * 앱이 만든 캐시·기록의 용량 계산/정리.
 *
 *  - 캐시 : 썸네일 이미지, 임시 파일 (앱 캐시 폴더)
 *  - 기록 : 오류 로그, 메인 스레드 스택 덤프
 *
 * 내려받은 음악 파일(사용자 파일)은 절대 건드리지 않는다.
 */
object Storage {

    /** 사람이 읽기 좋은 크기 문자열 (예: 12.3 MB) */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun cacheRoots(ctx: Context): List<File> = listOfNotNull(
        ctx.cacheDir,
        ctx.externalCacheDir,
    )

    private fun logFiles(ctx: Context): List<File> = buildList {
        // 앱 내부 로그 폴더 (crash.txt / main_stack.txt)
        add(File(ctx.filesDir, "logs"))
        // 파일관리자에서 볼 수 있는 외부 로그 파일
        ctx.getExternalFilesDir(null)?.let {
            add(File(it, "crash.txt"))
            add(File(it, "main_stack.txt"))
        }
    }

    /** 캐시 사용량 (바이트) */
    fun cacheSize(ctx: Context): Long = cacheRoots(ctx).sumOf { dirSize(it) }

    /** 기록 사용량 (바이트) */
    fun logSize(ctx: Context): Long = logFiles(ctx).sumOf { dirSize(it) }

    fun cacheSizeText(ctx: Context): String = sizeText(cacheSize(ctx))

    fun logSizeText(ctx: Context): String = sizeText(logSize(ctx))

    /** 캐시 비우기 (썸네일·임시 파일) */
    fun clearCaches(ctx: Context) {
        cacheRoots(ctx).forEach { root ->
            root.listFiles()?.forEach { deleteQuietly(it) }
        }
        Images.clearMemory()
        // 썸네일 폴더는 다시 만들어 둔다 (다음 곡부터 바로 캐시 가능)
        try {
            File(ctx.cacheDir, "thumbs").mkdirs()
        } catch (_: Exception) {
        }
    }

    /** 기록 삭제 (오류 로그·스택 덤프) */
    fun clearLogs(ctx: Context) {
        logFiles(ctx).forEach { deleteQuietly(it) }
        CrashLog.clear()
        try {
            Prefs.lastError = ""
            Prefs.lastErrorAt = ""
        } catch (_: Exception) {
        }
    }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSize(it) } ?: 0
    }

    /**
     * 폴더는 "안에 든 것만" 지우고 폴더 자체는 남긴다.
     * (로그 폴더를 통째로 지우면 이후 오류 기록이 저장되지 않는다)
     */
    private fun deleteQuietly(file: File) {
        try {
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteQuietly(it) }
            } else {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }
}
