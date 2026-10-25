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
}
