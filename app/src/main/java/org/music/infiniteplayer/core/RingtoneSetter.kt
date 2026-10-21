package org.music.infiniteplayer.core

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/** 벨소리(통화벨) 설정 */
object RingtoneSetter {

    /** 기본 벨소리로 설정한다. 성공하면 true */
    fun setRingtone(context: Context, uri: Uri): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                context, uri, RingtoneManager.TYPE_RINGTONE,
            )
        } catch (_: Exception) {
            false
        }
    }

    /** 알림음으로 설정 */
    fun setNotification(context: Context, uri: Uri): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                context, uri, RingtoneManager.TYPE_NOTIFICATION,
            )
        } catch (_: Exception) {
            false
        }
    }
}
