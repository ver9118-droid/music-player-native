package org.music.infiniteplayer.core

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 저장된 파일(뮤직다운 / 벨소리다운) 목록.
 *
 * MediaStore 를 읽어 **이 앱이 만든 파일** 위주로 보여 주고,
 * 벨소리 폴더(Ringtones) 는 사용자가 직접 넣은 파일까지 함께 보여 준다
 * (그중 하나를 골라 기본 벨소리로 바로 적용할 수 있게).
 *
 *  - 삭제는 이 앱이 만든 행(owner_package_id)만 허용한다 — 사용자 파일은 건드리지 않는다
 *  - 파일 이동/이름 변경은 하지 않는다 (MediaStore ID 유지)
 */
object LibraryIndex {

    /** 저장된 파일 한 개. */
    data class SavedItem(
        val uri: Uri,
        val name: String,
        val title: String,
        val artist: String,
        val ringtone: Boolean,
        val size: Long,
        val durationMs: Long,
        val addedAt: Long,
        /** 상대 경로 (예: "Ringtones", "Music/무한 음악 플레이어") */
        val folder: String,
        /** 이 앱이 만든 행인지 (삭제 허용 여부) */
        val appOwned: Boolean,
    )

    /** 한 번에 읽는 최대 행 수 */
    private const val MAX_ROWS = 400

    /**
     * 목록을 읽는다. **IO 스레드에서 호출**할 것.
     * 실패하면 빈 목록을 돌려준다.
     */
    fun scan(context: Context): List<SavedItem> {
        val resolver = context.contentResolver
        val pkg = context.packageName
        val api = Build.VERSION.SDK_INT

        val base = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            "is_ringtone",
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            "relative_path",
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
        )
        val columns = if (api >= Build.VERSION_CODES.Q) base + "owner_package_name" else base

        val selection: String
        val args: Array<String>
        if (api >= Build.VERSION_CODES.Q) {
            // 이 앱이 만든 행 + 앱 폴더(벨소리 폴더 포함)의 파일
            selection = "owner_package_name=? OR relative_path LIKE ? OR relative_path LIKE ?"
            args = arrayOf(pkg, "${MediaSaver.MUSIC_DIR}%", "${MediaSaver.RING_DIR}%")
        } else {
            // 예전 버전 : 소유자 컬럼이 없어 경로/파일명으로 판별한다
            selection = "_data LIKE ? OR _data LIKE ? OR display_name LIKE ?"
            args = arrayOf(
                "%/${MediaSaver.MUSIC_DIR}/%",
                "%/${MediaSaver.RING_DIR}/%",
                "%(벨소리)%",
            )
        }

        val out = ArrayList<SavedItem>()
        // 다운로드 때 기록해 둔 제목/아티스트 (태그 없는 m4a 보완)
        val savedMeta = SavedMeta.load(context)
        try {
            val cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns, selection, args, "date_added DESC",
            )
            cursor?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val ringIdx = c.getColumnIndex("is_ringtone")
                val sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val addedIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val folderIdx = c.getColumnIndex("relative_path")
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val ownerIdx = if (api >= Build.VERSION_CODES.Q) c.getColumnIndex("owner_package_name") else -1
                if (idIdx < 0) return emptyList()

                while (c.moveToNext() && out.size < MAX_ROWS) {
                    val id = c.getLong(idIdx)
                    val name = c.str(nameIdx)
                    val ringtone = c.num(ringIdx) == 1L
                    val rawTitle = c.str(titleIdx)
                    val meta = savedMeta[name]

                    // 제목 : 다운로드 때 기록한 값 → MediaStore 태그 → 파일명
                    var displayTitle = (meta?.first ?: "").ifBlank {
                        if (rawTitle.isBlank() || rawTitle.equals("<unknown>", ignoreCase = true)) {
                            name.substringBeforeLast('.')
                        } else {
                            rawTitle
                        }
                    }
                    if (ringtone) displayTitle = displayTitle.removeSuffix(" (벨소리)")

                    // 아티스트 : 다운로드 때 기록한 값 → 태그 → 파일명("아티스트 - 곡명")
                    // (유튜브에서 받은 m4a 는 태그가 없어 MediaStore 가 "<unknown>" 을 준다)
                    val taggedArtist = c.str(artistIdx)
                        .let { if (it.equals("<unknown>", ignoreCase = true)) "" else it }
                    val artist = (meta?.second ?: "").ifBlank { taggedArtist }
                        .ifBlank { artistFromName(name) }

                    val size = c.num(sizeIdx)
                    val added = c.num(addedIdx)
                    val folder = c.str(folderIdx).trimEnd('/')
                    val data = c.str(dataIdx)
                    val duration = c.num(durIdx)

                    val inMusicDir = folder == MediaSaver.MUSIC_DIR ||
                            data.contains("/${MediaSaver.MUSIC_DIR}/")
                    val inRingDir = folder == MediaSaver.RING_DIR ||
                            data.contains("/${MediaSaver.RING_DIR}/")
                    val owned = if (ownerIdx >= 0) {
                        val owner = c.str(ownerIdx)
                        // 소유자 값이 비어 있는 기기가 있어 폴더/파일명으로도 확인한다
                        owner == pkg || (owner.isBlank() &&
                                (inMusicDir || (inRingDir && MediaSaver.isAppCreated(name))))
                    } else {
                        inMusicDir || (inRingDir && MediaSaver.isAppCreated(name))
                    }

                    out.add(
                        SavedItem(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                            ),
                            name = name,
                            title = displayTitle,
                            artist = artist,
                            ringtone = ringtone,
                            size = size,
                            durationMs = duration,
                            addedAt = added,
                            folder = folder,
                            appOwned = owned,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // 권한/커서 오류 → 빈 목록 (앱은 계속 동작)
        }
        return out
    }

    /** 이 앱이 만든 파일만 삭제한다. 성공하면 true. */
    fun delete(context: Context, item: SavedItem): Boolean {
        if (!item.appOwned) return false
        return try {
            context.contentResolver.delete(item.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun Cursor.str(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""

    private fun Cursor.num(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    /**
     * 파일명에서 아티스트를 추측한다.
     * 뮤직다운 파일은 `아티스트 - 곡명.ext` 형식으로 저장되므로 앞부분을 쓴다.
     */
    private fun artistFromName(name: String): String {
        val base = name.substringBeforeLast('.').removeSuffix(" (벨소리)")
        val dash = base.indexOf(" - ")
        return if (dash > 0) base.substring(0, dash).trim() else ""
    }
}
