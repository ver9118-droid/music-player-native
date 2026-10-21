package org.music.infiniteplayer.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * 통화 감시.
 *
 * 오디오 포커스만으로는 일부 기기에서 통화가 정확히 감지되지 않아,
 * 전화 상태를 직접 감시해서
 *   - 통화 시작(벨소리/통화중) → 음악 일시정지 (노래와 전화가 겹치지 않게)
 *   - 통화 종료 → 사용자가 듣던 상태였다면 다시 재생
 * 을 수행한다.
 *
 * READ_PHONE_STATE 권한이 없으면 아무것도 하지 않는다
 * (권한 거부해도 ExoPlayer 의 오디오 포커스로 동일 효과 보장).
 */
class CallGuard(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit,
) {

    private var manager: TelephonyManager? = null
    private var callback: Any? = null
    private var listening = false

    fun start() {
        if (listening) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        manager = tm
        listening = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = ModernCallback()
                callback = cb
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } else {
                val listener = LegacyListener()
                callback = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: Exception) {
            listening = false
            callback = null
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback as? TelephonyCallback)?.let { manager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                (callback as? PhoneStateListener)?.let {
                    manager?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {
        }
        callback = null
        manager = null
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> onCallStarted()
            TelephonyManager.CALL_STATE_IDLE -> onCallEnded()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ModernCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handle(state)
    }

    @Suppress("DEPRECATION")
    private inner class LegacyListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int) = handle(state)
    }
}
