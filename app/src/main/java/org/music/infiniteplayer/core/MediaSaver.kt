package org.music.infiniteplayer.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.media.RingtoneManager
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
     * 벨소리 파일이 놓이는 폴더.
     *
     * 설정 > 소리 및 진동 > 벨소리 목록은 **Ringtones 폴더에 physically 있는 파일** 만 보여 준다
     * (`RingtoneManager.isCustomRingtone` 는 Ringtones / Notifications / Alarms 폴더 파일만 인정한다).
     * Music 폴더에 `IS_RINGTONE=1` 로 넣으면 사운드 선택기 ＋ 목록에는 보이지만
     * 설정 벨소리 목록에는 보이지 않는다 — 사운드 선택기에서 완료하면 파일이 Ringtones 폴더로
     * 복사된 뒤에야 설정 목록에 나타난다.
     *
     * 그래서 이 앱은 파일을 **Ringtones 폴더에 직접** 넣는다. 플래그는 둘 다 켠다:
     * `IS_RINGTONE=1` (설정 벨소리 목록) + `IS_MUSIC=1` (음악 라이브러리 · 사운드 선택기 ＋ 목록).
     */
    private const val RING_DIR = "Ringtones"

    /**
     * 이전 버전이 벨소리를 넣어 둔 폴더 (Music).
     *
     * 이미 있는 파일은 **새로 만들지 않는다** — 그 행을 재사용해 Ringtones 로 옮긴다
     * (MediaStore ID 가 유지되고, 목록에 같은 곡이 두 개로 보이지 않는다).
     */
    private const val RING_LEGACY_DIR = "Music"

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

    /**
     * 벨소리로 저장 — **Ringtones 폴더에 한 개** (`IS_RINGTONE=1` + `IS_MUSIC=1`).
     *
     * 순서:
     *  ① 앱이 만든 같은 이름 행이 이미 있으면 → `keepSingleCopy` 로 **한 개만** 남긴다
     *     (새 파일을 만들지 않고 기존 MediaStore 행을 재사용 → ID 유지 → 목록에 두 개로 안 보임)
     *  ② 그 행에 **하이라이트로 잘린 음원을 덮어쓴다** (파일을 새로 만들지 않는다)
     *     + 폴더/플래그를 Ringtones · `IS_RINGTONE=1` · `IS_MUSIC=1` 로 정리한다
     *     (설정 벨소리 목록은 Ringtones 폴더 파일만 보여 준다)
     *  ③ 아무것도 없으면 새로 삽입
     *
     * 사용자가 직접 넣어 둔 파일은 옮기거나 지우지 않는다 (앱이 만든 이름만 처리).
     */
    suspend fun saveAsRingtone(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        val mime = plainMime(mimeType)
        val name = safeName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            if (isAppCreated(name)) {
                // 앱이 만든 파일 → 이미 있으면 재사용/정리 (사용자 파일과 구분)
                val kept = keepSingleCopy(context, name)
                if (kept != null) {
                    if (overwrite(context, resolver, kept, file, mime, name)) {
                        return@withContext SaveResult(kept, "")
                    }
                    // 쓰기 실패 → 그 행은 이전 내용(곡 전체) 을 그대로 유지한다.
                    // 실패한 행을 돌려주면 파일명만 바뀐 채 옛 파일이 남는다 → 새로 삽입한다.
                    return@withContext insert(context, file, name, mime, RING_DIR, isRingtone = true)
                }
            } else {
                // 사용자 파일: 그 자리 그대로 사용한다 (옮기거나 지우지 않는다)
                val userRow = findExisting(resolver, name, RING_DIR)
                    ?: findExisting(resolver, name, RING_LEGACY_DIR)
                if (userRow != null) return@withContext SaveResult(userRow, "")
            }

            insert(context, file, name, mime, RING_DIR, isRingtone = true)
        } else {
            copyLegacy(context, file, name, mime, Environment.DIRECTORY_RINGTONES)
        }
    }

    /**
     * 같은 이름 사본이 두 곳에 있으면 **한 개만** 남긴다 (이전 버전이 두 곳에 저장해 둔 파일 정리).
     *
     *  - **현재 벨소리로 걸려 있는 쪽** 의 행을 유지한다 — 그 행이 목록에서 보이는 쪽이다.
     *  - 유지하는 행은 Ringtones 폴더로 **옮긴다** (파일을 복사하지 않는다 → ID 유지).
     *    설정 > 소리 및 진동 > 벨소리 목록은 Ringtones 폴더 파일만 보여 주기 때문이다.
     *  - 다른 사본은 **앱이 만든 행일 때만** 지운다. 사용자가 직접 넣어 둔 파일은 삭제하지 않는다.
     *
     * 같은 이름 파일이 하나도 없으면 null (그때는 새로 삽입하면 된다).
     */
    fun keepSingleCopy(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val name = safeName(displayName)
        val ringtone = findExisting(resolver, name, RING_DIR)
        val legacy = findExisting(resolver, name, RING_LEGACY_DIR)
        if (ringtone == null && legacy == null) return null

        val current = try {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        } catch (_: Exception) {
            null
        }

        // 현재 벨소리로 걸려 있는 쪽의 ID 를 유지한다 (그 행이 목록에서 보이는 쪽이다)
        val keep = (if (current != null && current == legacy) legacy else (ringtone ?: legacy)) ?: return null
        val drop = if (keep == legacy) ringtone else legacy

        if (drop != null && isAppCreated(name) && ownedByApp(resolver, drop, context.packageName)) {
            try {
                resolver.delete(drop, null, null)
            } catch (_: Exception) {
                // 앱이 소유하지 않은 행은 지울 수 없다 → 그대로 두고 유지하는 행만 처리한다
            }
        }

        // 앱이 만든 파일만 폴더를 옮긴다 (사용자가 넣은 파일은 그 자리 그대로 둔다)
        return if (isAppCreated(name)) moveToRingtoneFolder(resolver, keep) ?: keep else keep
    }

    /**
     * MediaStore **행에 파일 내용을 덮어쓴다** (행 ID 는 그대로 유지된다).
     *
     * 앱이 만든 행만 쓸 수 있다. 쓸 수 없으면 false — 그때는 기존 파일 그대로 사용한다.
     * 덮어쓰기 전에 행을 **Ringtones 폴더 + `IS_RINGTONE=1` + `IS_MUSIC=1`** 로 정리한다
     * (재사용한 행이 예전 Music 폴더에 있거나 플래그가 꺼져 있으면 설정 벨소리 목록에 보이지 않는다).
     * 덮어쓴 뒤에는 크기·길이 메타데이터가 다시 계산되도록 스캔한다.
     */
    private fun overwrite(
        context: Context,
        resolver: android.content.ContentResolver,
        uri: Uri,
        file: File,
        mimeType: String,
        displayName: String,
    ): Boolean {
        // 설정 벨소리 목록에 보이게: Ringtones 폴더 + 플래그 (ID 는 유지된다)
        val moved = moveToRingtoneFolder(resolver, uri) ?: uri

        // 행 이름/타입을 지금 파일에 맞춘다 (페이드 설정을 켜고 끄면 .ogg ↔ .m4a 로 바뀔 수 있다)
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            }
            resolver.update(moved, values, null, null)
        } catch (_: Exception) {
            // 이름 정리 실패는 저장을 막지 않는다
        }

        val wrote = try {
            resolver.openOutputStream(moved)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (!wrote) return false

        try {
            val path = resolver.query(moved, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                val index = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (index >= 0 && c.moveToFirst()) c.getString(index) else null
            }
            if (path != null) {
                // 스캐너는 폴더 이름으로 플래그를 다시 계산한다 (Ringtones → is_music 가 0 이 될 수 있다)
                // → 스캔이 끝난 뒤 플래그를 다시 켠다 (설정 벨소리 목록 + 사운드 선택기 ＋ 목록 양쪽 유지)
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, scanned ->
                    if (scanned == null) return@scanFile
                    try {
                        val flags = ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                            put(MediaStore.Audio.Media.IS_MUSIC, 1)
                            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        }
                        resolver.update(moved, flags, null, null)
                    } catch (_: Exception) {
                        // 플래그 재적용 실패는 저장을 막지 않는다
                    }
                }
            }
        } catch (_: Exception) {
            // 스캔 실패는 저장을 막지 않는다
        }
        return true
    }

    /** MediaStore 행을 만든 앱인지 (다른 앱/사용자 파일을 지우지 않기 위해 확인한다) */
    private fun ownedByApp(resolver: android.content.ContentResolver, uri: Uri, pkg: String): Boolean {
        return try {
            // OWNER_PACKAGE_ID (API 29) — 컬럼 이름으로 조회한다 (상수 이름이 버전마다 다르다)
            val column = "owner_package_id"
            resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                c.getColumnIndex(column) >= 0 && c.moveToFirst() && c.getString(0) == pkg
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** 앱이 만든 벨소리 파일명인지 (`곡명 (벨소리).ext`) — 사용자 파일은 건드리지 않는다 */
    private fun isAppCreated(displayName: String): Boolean = displayName.contains("(벨소리)")

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
            // (지우고 다시 만들면 MediaStore ID 가 바뀌어 목록에 같은 곡이 두 개로 보인다)
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
     * 목록에서 같은 곡이 두 개로 보이지 않는다. (반복 적용 시 사본이 생기지도 않는다)
     *
     * 확장자 없이(곡명 + " (벨소리)") 도 비교한다 — 페이드 설정을 켜고 끄면 파일이
     * .ogg ↔ .m4a 로 바뀔 수 있어, 확장자만 다른 같은 곡이 목록에 두 개로 뜨지 않게 한다.
     */
    private fun findExisting(
        resolver: android.content.ContentResolver,
        displayName: String,
        relativePath: String,
    ): Uri? {
        val base = displayName.substringBeforeLast('.')
        return try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "(${MediaStore.Audio.Media.DISPLAY_NAME}=? OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?) AND (" +
                    "${MediaStore.Audio.Media.RELATIVE_PATH}=? OR " +
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)",
                // RELATIVE_PATH 는 끝 슬래시 없이 저장되는 경우가 있다 (Ringtones, Music)
                arrayOf(displayName, "$base.%", relativePath, "$relativePath/%"),
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

    /**
     * MediaStore **행만** Ringtones 폴더로 옮긴다 (파일을 복사하지 않는다).
     *
     * 행 ID 가 유지되므로 목록에서 같은 곡이 두 개로 보이지 않는다.
     * 설정 > 소리 및 진동 > 벨소리 목록은 Ringtones 폴더 파일만 보여 준다.
     * 플래그: `IS_RINGTONE=1` (설정 벨소리 목록) + `IS_MUSIC=1` (음악 라이브러리 · 사운드 선택기 ＋ 목록).
     */
    private fun moveToRingtoneFolder(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RING_DIR)
                put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            if (resolver.update(uri, values, null, null) > 0) uri else null
        } catch (_: Exception) {
            // 앱이 소유하지 않은 행은 옮길 수 없다 → 원본 URI 를 그대로 쓴다
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

