package org.music.infiniteplayer.core

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/** 벨소리(통화벨) 설정 */
object RingtoneSetter {

    /**
     * 기본 벨소리로 설정한다.
     *  - 성공하면 true
     *  - 벨소리 권한/정책으로 거부되면 false (파일은 이미 저장되어 있음)
     */
    fun setRingtone(context: Context, uri: Uri): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_RINGTONE, uri,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 기본 알림음으로 설정 */
    fun setNotification(context: Context, uri: Uri): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_NOTIFICATION, uri,
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
