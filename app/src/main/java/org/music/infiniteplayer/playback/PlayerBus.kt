package org.music.infiniteplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.music.infiniteplayer.data.LyricLine

/**
 * 자주 바뀌는 재생 정보 (위치/길이).
 *
 * 위치는 250ms 마다 갱신되므로, 곡 정보와 분리해 두어야
 * 홈 화면 목록이 매 프레임 다시 그려지지 않는다. (성능/끝김 방지)
 */
data class PlaybackTick(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * 진단 정보.
 *
 * AudioManager 를 호출해야 하므로(昂贵) 필요할 때만 갱신한다.
 */
data class Diagnostics(
    val playerState: String = "IDLE",
    val streamInfo: String = "",
    val streamProbe: String = "",
    val systemVolume: String = "",
    val audioRoute: String = "",
    val serviceState: String = "",
    val uiLatency: String = "",
)

/** UI 가 보는 재생 상태 (曲目 정보/설정 등 자주 안 바뀌는 것들). */
data class PlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val index: Int = -1,
    val count: Int = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val thumbUrl: String = "",
    val shuffle: Boolean = false,
    val repeat: String = "all",
    val infinite: Boolean = true,
    val volume: Float = 0.85f,
    val status: String = "",
    val error: String = "",
    val lyrics: List<LyricLine> = emptyList(),
    val callActive: Boolean = false,
    val downloadProgress: Int = -1,       // 로컬 다운로드 진행률 (-1 = 없음)
    // ── 구간 반복 ───────────────────────────────────────────────
    val sectionStartMs: Long = -1,        // 반복 재생 구간 시작
    val sectionEndMs: Long = -1,          // 반복 재생 구간 끝
    val toast: String = "",               // 사용자에게 보여줄 메시지
    val toastId: Long = 0L,               // 같은 메시지도 반복 표시되도록 증가
)

/**
 * UI ↔ 서비스 통로.
 *
 * 서비스와 액티비티가 같은 프로세스이므로 바인딩 없이 바로 호출할 수 있다.
 * (바인딩/코루틴 지연 없이 즉시 반응 → 컨트롤 지연 0)
 */
object PlayerBus {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _tick = MutableStateFlow(PlaybackTick())
    val tick: StateFlow<PlaybackTick> = _tick

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics

    @Volatile
    private var service: PlaybackService? = null

    private var pending: ((PlaybackService) -> Unit)? = null

    fun attach(svc: PlaybackService) {
        service = svc
        pending?.let { cmd ->
            pending = null
            try {
                cmd(svc)
            } catch (e: Exception) {
                org.music.infiniteplayer.core.CrashLog.save(e)
            }
        }
    }

    fun detach(svc: PlaybackService) {
        if (service === svc) service = null
    }

    fun isAttached(): Boolean = service != null

    /**
     * 서비스에 명령을 전달한다.
     * 서비스가 아직 없으면 대기열에 넣고, 서비스가 살아나면 실행한다.
     */
    fun post(block: (PlaybackService) -> Unit) {
        val svc = service
        if (svc != null) {
            try {
                block(svc)
            } catch (e: Exception) {
                org.music.infiniteplayer.core.CrashLog.save(e)
            }
            pending = null
        } else {
            pending = block
        }
    }

    internal fun publish(next: PlayerState) {
        _state.value = next
    }

    internal fun publishTick(next: PlaybackTick) {
        _tick.value = next
    }

    internal fun publishDiagnostics(next: Diagnostics) {
        _diagnostics.value = next
    }
}
