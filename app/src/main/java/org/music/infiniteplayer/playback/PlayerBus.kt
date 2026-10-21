package org.music.infiniteplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.music.infiniteplayer.data.LyricLine

/** UI 가 보는 재생 상태. */
data class PlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
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
    // ── 진단 정보 ───────────────────────────────────────────
    val playerState: String = "IDLE",     // ExoPlayer 실제 상태
    val streamInfo: String = "",          // itag / mime
    val streamProbe: String = "",         // 스트림 URL HTTP 응답 (예: HTTP 206)
    val systemVolume: String = "",        // 시스템 미디어 볼륨 (예: 7/15)
    val audioRoute: String = "",          // 출력 경로 (스피커/이어폰/블루투스…)
    val downloadProgress: Int = -1,       // 로컬 다운로드 진행률 (-1 = 없음)
    // ── 구간/하이라이트 ───────────────────────────────────────
    val highlightStartMs: Long = -1,      // 하이라이트 시작점 (A)
    val highlightEndMs: Long = -1,        // 하이라이트 끝점 (B)
    val sectionStartMs: Long = -1,        // 반복 재생 구간 시작
    val sectionEndMs: Long = -1,          // 반복 재생 구간 끝
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

    @Volatile
    private var service: PlaybackService? = null

    private var pending: ((PlaybackService) -> Unit)? = null

    fun attach(svc: PlaybackService) {
        service = svc
        pending?.let { it(svc) }
        pending = null
    }

    fun detach(svc: PlaybackService) {
        if (service === svc) service = null
    }

    /** 서비스에 명령을 전달한다. (서비스가 아직 없으면 준비되면 실행) */
    fun post(block: (PlaybackService) -> Unit) {
        val svc = service
        if (svc != null) block(svc) else pending = block
    }

    internal fun publish(next: PlayerState) {
        _state.value = next
    }
}
