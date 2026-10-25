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

    /**
     * 벨소리 저장 폴더 (음악 폴더 `Music/` 바로 아래).
     *
     * 삼성 **사운드 선택기(＋ 목록)** 는 음악 폴더 계열 파일만 모아 보여주기 때문에
     * 저장소 루트의 `Ringtones` 에 넣으면 목록에 아예 안 뜬다.
     * (Music 바로 아래 + `IS_MUSIC=1` 로 "모두/음악/폴더" 목록 어디에서도 보이게 한다)
     */
    private const val RING_DIR = "Music"

    /**
     * **설정 > 소리 및 진동 > 벨소리** 목록이 읽는 폴더 = 저장소 루트 `Ringtones`.
     *
     * 두 목록이 폴더를 다르게 읽는다:
     *  - 사운드 선택기(＋ 목록) → 음악 폴더(`RING_DIR`)
     *  - 설정 벨소리 목록     → 루트 `Ringtones` (`RING_ROOT_DIR`)
     * 그래서 벨소리는 **두 곳에 같은 파일을 넣는다** (이중 저장).
     */
    private const val RING_ROOT_DIR = "Ringtones"

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
            insert(context, file, name, mime, MUSIC_DIR, isRingtone = false)
        } else {
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_MUSIC)
        }
    }

    /** 벨소리 목록에 보이게 저장 — **두 폴더에 이중 저장** 후 루트 `Ringtones` URI 반환 */
    suspend fun saveAsRingtone(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        val mime = plainMime(mimeType)
        val name = safeName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ① 사운드 선택기(＋ 목록) 가 모아 보여주는 음악 폴더
            val music = insert(context, file, name, mime, RING_DIR, isRingtone = true)
            // ② 설정 > 벨소리 목록이 읽는 저장소 루트 Ringtones (실패해도 ① 은 유지)
            val root = insert(context, file, name, mime, RING_ROOT_DIR, isRingtone = true)
            // 둘 중 하나라도 성공하면 성공으로 친다 (설정 목록용 URI 를 우선 반환)
            if (root.uri != null) root else music
        } else {
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_MUSIC)
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_RINGTONES)
        }
    }

    /**
     * 사운드 선택기에서 고른 소리를 **저장소 루트 `Ringtones`** 에 저장한다.
     * (설정 > 소리 및 진동 > 벨소리 목록에 뜨게 함)
     */
    suspend fun saveToRingtoneFolder(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        val mime = plainMime(mimeType)
        val name = safeName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(context, file, name, mime, RING_ROOT_DIR, isRingtone = true)
        } else {
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_RINGTONES)
        }
    }

    private fun insert(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        isRingtone: Boolean,
    ): SaveResult {
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            // 같은 이름 파일이 이미 있으면 → **그대로 재사용**한다.
            // (지우고 다시 만들면 MediaStore ID 가 바뀌어
            //  갤럭시 사운드 선택기가 저장해 둔 "선택한 하이라이트" 구간이 풀려
            //  벨소리가 항상 처음부터 재생된다)
            if (isRingtone) {
                findExisting(resolver, displayName, relativePath)?.let {
                    return SaveResult(it, "")
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                // 목록에 제목으로 보이게 (파일명과 별개로 표시될 수 있음)
                put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                if (isRingtone) {
                    // 벨소리 목록에 뜨도록 (설정 > 소리 및 진동 > 벨소리)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    // 사운드 선택기(＋ 목록)는 음악 파일만 모아 보여준다.
                    // IS_MUSIC 이 0 이면 목록에 안 뜬다 → 1 로 설정해 찾을 수 있게 한다.
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
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

    /**
     * 같은 폴더에 같은 이름 파일이 이미 있으면 그 **기존 URI** 를 돌려준다.
     *
     * 삭제 후 다시 만들지 않고 재사용한다 — 그래야 MediaStore ID 가 유지되어
     * 사운드 선택기가 파일에 붙여 둔 **하이라이트 구간 정보가 사라지지 않는다.**
     * (반복 적용 시 사본이 생기지도 않는다)
     */
    private fun findExisting(
        resolver: android.content.ContentResolver,
        displayName: String,
        relativePath: String,
    ): Uri? {
        return try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND (" +
                    "${MediaStore.Audio.Media.RELATIVE_PATH}=? OR " +
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)",
                arrayOf(displayName, "$relativePath/", "$relativePath/%"),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            // 조회 실패는 저장을 막지 않는다 (그때만 새로 만든다)
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

