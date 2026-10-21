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
 * 기기 저장소(음악 라이브러리 / 벨소리) 에 파일 저장.
 *
 *  - Android 10(API 29) 이상 : MediaStore 에 직접 삽입 (권한 불필요)
 *  - 그 이하 : 공개 폴더에 복사 후 미디어 스캐너 등록
 */
object MediaSaver {

    private const val MUSIC_DIR = "Music/무한 음악 플레이어"
    private const val RING_DIR = "Music/Ringtones"

    /** 음악 라이브러리에 저장 */
    suspend fun saveToMusic(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(context, file, displayName, mimeType, MUSIC_DIR, isRingtone = false)
        } else {
            copyLegacy(context, file, displayName, mimeType, Environment.DIRECTORY_MUSIC)
        }
    }

    /** 벨소리로 쓸 수 있게 저장 (IS_RINGTONE 플래그) */
    suspend fun saveAsRingtone(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(context, file, displayName, mimeType, RING_DIR, isRingtone = true)
        } else {
            copyLegacy(context, file, displayName, mimeType, Environment.DIRECTORY_RINGTONES)
        }
    }

    private fun insert(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        isRingtone: Boolean,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            if (isRingtone) put(MediaStore.Audio.Media.IS_RINGTONE, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    /** Android 9 이하 : 공개 폴더 복사 + 스캔 (스캔 결과를 기다린다) */
    private suspend fun copyLegacy(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        type: String,
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(type)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, displayName)
            file.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf(mimeType)
                ) { _, uri -> if (cont.isActive) cont.resume(uri) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
