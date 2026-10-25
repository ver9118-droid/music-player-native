package org.music.infiniteplayer.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 기기 저장소(음악 라이브러리) 에 파일 저장.
 *
 *  - Android 10(API 29) 이상 : MediaStore 에 직접 삽입 (권한 불필요)
 *  - 그 이하 : 공개 폴더에 복사 후 미디어 스캐너 등록
 */
object MediaSaver {

    private const val MUSIC_DIR = "Music/무한 음악 플레이어"

    /**
     * MIME 타입 정리.
     * 유튜브는 `audio/mp4; codecs="mp4a.40.2"` 처럼 코덱 정보가 붙어오는데,
     * MediaStore 는 `audio/mp4` 형태만 받아들인다. (그대로 넣으면 저장이 실패한다)
     */
    fun plainMime(mimeType: String?): String {
        val base = (mimeType ?: "").substringBefore(";").trim()
        return if (base.isEmpty() || !base.contains('/')) "audio/mp4" else base
    }

    /** 파일명 정리 (MediaStore 가 거절하는 문자 제거 + 길이 제한) */
    fun safeName(name: String, fallback: String = "audio"): String {
        var s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > 90) s = s.substring(0, 90).trim()
        if (s.isEmpty()) s = fallback
        return s
    }

    /** MIME 타입에 맞는 확장자 */
    fun extensionFor(mimeType: String?): String {
        val m = plainMime(mimeType)
        return when {
            m.contains("mp4") || m.contains("m4a") || m.contains("aac") -> "m4a"
            m.contains("webm") -> "webm"
            m.contains("ogg") -> "ogg"
            m.contains("mpeg") -> "mp3"
            m.contains("mp4a") -> "m4a"
            else -> "m4a"
        }
    }

    /** 저장 결과 (오류 사유 포함) */
    data class SaveResult(val uri: Uri? = null, val error: String = "")

    /** 음악 라이브러리에 저장 */
    suspend fun saveToMusic(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        val mime = plainMime(mimeType)
        val name = safeName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(context, file, name, mime, MUSIC_DIR)
        } else {
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_MUSIC)
        }
    }

    private fun insert(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
    ): SaveResult {
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                // 목록에 제목으로 보이게 (파일명과 별개로 표시될 수 있음)
                put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return SaveResult(null, "MediaStore 삽입 실패 ($displayName)")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return SaveResult(null, "파일 쓰기 실패")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SaveResult(uri, "")
        } catch (e: Exception) {
            try {
                uri?.let { resolver.delete(it, null, null) }
            } catch (_: Exception) {
            }
            return SaveResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Android 9 이하 : 공개 폴더 복사 + 스캔 (스캔 결과를 기다린다) */
    private suspend fun copyLegacy(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        type: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(type)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, displayName)
            file.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            val uri = suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf(mimeType)
                ) { _, scanned -> if (cont.isActive) cont.resume(scanned) }
            }
            SaveResult(uri, "")
        } catch (e: Exception) {
            SaveResult(null, e.message ?: e.javaClass.simpleName)
        }
    }
}

