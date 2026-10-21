package org.music.infiniteplayer.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 통화 감시.
 *
 * 오디오 포커스만으로는 일부 기기/통신사에서 통화가 정확히 감지되지 않아,
 * 전화 상태를 직접 감시해서
 *   - 통화 시작(벨소리/통화중) → 음악 일시정지 (노래와 전화가 겹치지 않게)
 *   - 통화 종료 → 사용자가 듣던 상태였다면 다시 재생
 * 을 수행한다.
 *
 *  - Android 12(API 31) 이상: TelephonyCallback
 *  - 그 이하: 1초 주기로 통화 상태 조회 (READ_PHONE_STATE 필요)
 *
 * READ_PHONE_STATE 권한이 없으면 아무것도 하지 않는다
 * (권한 거부해도 ExoPlayer 의 오디오 포커스로 동일 효과 보장).
 */
class CallGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit,
) {

    private var manager: TelephonyManager? = null
    private var callback: Any? = null
    private var pollJob: Job? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var running = false

    fun start() {
        if (running) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        manager = tm
        running = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val cb = ModernCallback()
                callback = cb
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } catch (_: Exception) {
                running = false
                callback = null
            }
        } else {
            startPolling(tm)
        }
    }

    /** 구형 버전: 통화 상태를 1초마다 확인 */
    private fun startPolling(tm: TelephonyManager) {
        pollJob = scope.launch {
            while (isActive) {
                val state = try {
                    tm.callState
                } catch (_: Exception) {
                    TelephonyManager.CALL_STATE_IDLE
                }
                if (state != lastState) {
                    val previous = lastState
                    lastState = state
                    val wasTalking = previous == TelephonyManager.CALL_STATE_OFFHOOK ||
                        previous == TelephonyManager.CALL_STATE_RINGING
                    val isTalking = state == TelephonyManager.CALL_STATE_OFFHOOK ||
                        state == TelephonyManager.CALL_STATE_RINGING
                    if (isTalking && !wasTalking) onCallStarted()
                    if (!isTalking && wasTalking) onCallEnded()
                }
                delay(1000)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        pollJob?.cancel()
        pollJob = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback as? TelephonyCallback)?.let { manager?.unregisterTelephonyCallback(it) }
            }
        } catch (_: Exception) {
        }
        callback = null
        manager = null
        lastState = TelephonyManager.CALL_STATE_IDLE
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class ModernCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> onCallStarted()
                TelephonyManager.CALL_STATE_IDLE -> onCallEnded()
            }
        }
    }
}
