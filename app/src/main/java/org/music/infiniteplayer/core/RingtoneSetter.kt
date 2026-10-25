package org.music.infiniteplayer.core

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

/** 벨소리(통화벨) 설정 */
object RingtoneSetter {

    /**
     * 시스템 설정에 쓸 수 있는 권한이 있는지.
     * (WRITE_SETTINGS 는 위험군 권한이 아니라 사용자가 직접 허용해야 하는 특별한 권한이다)
     */
    fun canWriteSettings(context: Context): Boolean {
        return try {
            Settings.System.canWrite(context)
        } catch (_: Exception) {
            false
        }
    }

    /** "시스템 설정에 쓰기" 권한 화면을 연다 (설정 앱에서 허용해야 한다) */
    fun openWriteSettingsPage(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 제조사 화면이 없는 경우 목록 화면으로 다시 시도
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 기본 벨소리로 설정한다.
     *  - 성공하면 true
     *  - 권한이 없거나 정책으로 거부되면 false (파일은 이미 저장되어 있음)
     */
    fun setRingtone(context: Context, uri: Uri): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_RINGTONE, uri,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 현재 시스템 기본 벨소리 URI (알 수 없으면 null) */
    fun currentRingtoneUri(context: Context): Uri? {
        return try {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 현재 기본 벨소리의 **파일명** (확장자 제외, 대소문자 무시).
     *
     * 같은 곡이라도 URI 가 다르면(음악 폴더 사본 / 루트 Ringtones 사본)
     * `isSameRingtone` 로는 잡히지 않는다 → 파일명으로 한 번 더 비교해
     * "같은 곡은 다시 설정하지 않는다" 를 확실히 한다.
     */
    fun currentRingtoneFileName(context: Context): String? {
        val uri = currentRingtoneUri(context) ?: return null
        val path = dataPath(context, uri)
        val name = if (!path.isNullOrBlank()) {
            path.substringAfterLast('/')
        } else {
            uri.lastPathSegment ?: return null
        }
        return name.substringBeforeLast('.').lowercase()
    }

    /**
     * 두 URI 가 **같은 파일** 을 가리키는지.
     *
     * URI 문자열이 달라도 같은 파일(MediaStore ID/경로)이면 true —
     * "이미 적용된 벨소리를 다시 설정" 하면 갤럭시가 저장해 둔
     * **하이라이트 구간이 풀려 처음부터 재생**되므로, 같은 파일이면 다시 설정하지 않는다.
     */
    fun isSameRingtone(context: Context, a: Uri?, b: Uri?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val pa = dataPath(context, a) ?: return false
        val pb = dataPath(context, b) ?: return false
        return pa == pb
    }

    /** 미디어 스토어 URI 의 실제 파일 경로 (미디어 URI 가 아니면 null) */
    private fun dataPath(context: Context, uri: Uri): String? {
        if (uri.authority != "media") return null
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.Audio.Media.DATA),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
