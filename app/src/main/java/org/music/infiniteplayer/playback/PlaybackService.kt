package org.music.infiniteplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.music.infiniteplayer.MainActivity
import org.music.infiniteplayer.core.Images
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.data.ResolvedAudio
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository
import java.io.File
import kotlin.random.Random

/**
 * 백그라운드 재생 서비스.
 *
 *  - MediaSessionService 기반 → 알림 + 차량 블루투스(AVRCP) 제어 자동 지원
 *  - ExoPlayer 오디오 포커스 처리 → 전화/다른 앱 소리와 겹치지 않음
 *  - CallGuard 로 통화 시작/종료를 직접 감지 → 통화 중에는 무조건 정지
 *
 * 재생목록은 "윈도우(슬라이딩)" 방식으로 관리한다.
 *   - 지금 곡 + 다음 2곡만 미리 해석(다운로드)해 둔다.
 *   - 뒤로 넘어간 항목은 제거해서 메모리를 아낀다.
 *   - 목록 끝에 닿으면 무한 재생이면 새 순서를 이어 붙인다.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var callGuard: CallGuard? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
                // 코루틴 예외가 앱을 죽이지 않도록 처리 (재생 유지)
                CoroutineExceptionHandler { _, error ->
                    runCatching { org.music.infiniteplayer.core.CrashLog.save(error) }
                }
    )
    private lateinit var youtube: YoutubeRepository

    private class Entry(val track: Track) {
        @Volatile var audio: ResolvedAudio? = null
        @Volatile var thumb: File? = null
        @Volatile var job: Deferred<ResolvedAudio?>? = null
        @Volatile var localFile: File? = null
    }

    private val entries = ArrayList<Entry>()

    /** 해석해 둔 스트림 캐시 (曲目 id → 스트림) */
    private val audioCache = HashMap<String, ResolvedAudio>()

    /** 재생목록 세대 번호. 바뀌면 진행 중인 미리 준비를 중단한다. */
    private var generation = 0

    /** 재생 순서 (엔트리 인덱스 목록). 무한 재생 시 계속 늘어난다. */
    private val order = ArrayList<Int>()

    /** 플레이어 재생목록의 첫 항목이 order 의 몇 번째인지 */
    private var windowStart = 0

    /** 현재 재생 위치 (order 기준) */
    private var pos = -1

    /** 연속으로 곡 재생에 실패한 횟수 (실패 폭주 시 무한 스킵 방지) */
    private var consecutiveFailures = 0
    private var lastFailureAt = 0L

    /** 목록 끝까지 다 재생하고 멈춘 상태 (▶ 누르면 처음 곡부터 다시) */
    private var endedAtList = false

    private var currentEntry: Entry? = null
    private var userWantsPlay = false
    private var callPaused = false

    private var statusText = "대기 중"
    private var errorText = ""
    private var ticker: Job? = null
    private var fillJob: Job? = null
    private var fillAgain = false

    // ── 진단/복구 ────────────────────────────────────────────
    private var streamProbe = "확인 안 됨"
    private var streamInfo = ""
    private var selfTestText = ""
    private var selfTestRunning = false
    private var retryCount = 0
    private var downloadProgress = -1
    private var preparing = false
    private var lastState = PlayerState()
    private var toastText = ""
    private var toastId = 0L

    private var repeatMode = "all"
    private var infinite = true
    private var shuffleOn = false
    private var volume = 0.85f

    // ── 생애주기 ───────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        try {
            initPlayer()
        } catch (e: Exception) {
            // 서비스 초기화 실패가 앱 전체를 죽이지 않도록
            org.music.infiniteplayer.core.CrashLog.save(e)
            errorText = "재생 엔진 초기화 실패: ${e.message ?: e.javaClass.simpleName}"
        }
        PlayerBus.attach(this)
        startDiagnosticsLoop()
        publish()
        // 서비스가 재시작된 경우 마지막 곡/위치를 복원한다
        restorePlaybackState()
    }

    private fun initPlayer() {
        youtube = YoutubeRepository(Prefs.quality)

        repeatMode = Prefs.repeat
        infinite = Prefs.infinite
        shuffleOn = Prefs.shuffle
        volume = Prefs.volume

        val exo = ExoPlayer.Builder(this)
            // 유튜브 서버는 열린 범위("bytes=0-") 요청에 403 을 주므로
            // 닫힌 Range 를 보내는 커스텀 DataSource 를 사용한다.
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(org.music.infiniteplayer.core.RangeDataSource.Factory())
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        exo.addListener(Listener())
        exo.volume = volume
        // 한 곡 반복은 플레이어가 직접 처리하게 한다 (수동 seek/play 는 기기에 따라 멈춤)
        applyPlayerRepeat()

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openAppIntent())
            .build()

        callGuard = CallGuard(
            context = this,
            scope = scope,
            onCallStarted = { onCallStarted() },
            onCallEnded = { onCallEnded() },
        ).also { it.start() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 앱을 최근 목록에서 밀어내더라도 서비스를 죽이지 않는다.
        //  - 활성 세션(UI 의 MediaController 연결)이면 Media3 가 유지한다
        //  - 비활성이어도 재생 중이면 반드시 유지한다
        if (player?.isPlaying == true) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        callGuard?.stop()
        callGuard = null
        PlayerBus.detach(this)
        ticker?.cancel()
        fillJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // ── 공개 API (UI 에서 호출) ────────────────────────────────
    fun setQueue(tracks: List<Track>, startIndex: Int) {
        // 같은 곡이면 이전에 해석해둔 스트림을 재사용한다 (재탐색 방지)
        val previous = HashMap<String, Entry>()
        entries.forEach { previous[it.track.id] = it }
        entries.clear()
        tracks.forEach { t -> entries.add(previous[t.id] ?: Entry(t)) }
        if (entries.isEmpty()) {
            order.clear()
            publish()
            return
        }
        // 서비스가 죽어도 복원되도록 목록을 저장한다
        savePlaybackState(tracks, startIndex)
        rebuildOrder(startIndex.coerceIn(0, entries.size - 1))
        jumpTo(0, autoPlay = true)
    }

    /** 재생목록/현재곡을 저장 (서비스 재시작 시 복원용) */
    private fun savePlaybackState(tracks: List<Track>, index: Int) {
        try {
            Prefs.playbackQueue = org.music.infiniteplayer.data.encodeTracks(tracks)
            Prefs.playbackIndex = index
        } catch (_: Exception) {
        }
    }

    /** 서비스가 다시 살아났을 때 마지막 곡/위치 복원 */
    private fun restorePlaybackState() {
        val json = Prefs.playbackQueue
        if (json.isBlank()) return
        val list = try {
            org.music.infiniteplayer.data.decodeTracks(json)
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isEmpty()) return
        scope.launch {
            try {
                entries.clear()
                list.forEach { entries.add(Entry(it)) }
                val idx = Prefs.playbackIndex.coerceIn(0, entries.size - 1)
                rebuildOrder(idx)
                val entry = entries[order[0]]
                val audio = resolveEntry(entry)
                if (audio == null) return@launch
                val items = ArrayList<MediaItem>(1)
                items.add(mediaItem(entry, audio))
                val p = player ?: return@launch
                windowStart = 0
                p.setMediaItems(items, 0, 0L)
                p.prepare()
                currentEntry = entry
                val savedPos = Prefs.playbackPosition
                if (savedPos > 0) p.seekTo(savedPos)
                if (Prefs.playbackPlaying) {
                    userWantsPlay = true
                    p.play()
                    startTicker()
                }
                statusText = if (Prefs.playbackPlaying) "재생 중" else "일시정지"
                publish()
                fillWindow()
            } catch (e: Exception) {
                org.music.infiniteplayer.core.CrashLog.save(e)
            }
        }
    }

    fun playTrack(index: Int) {
        if (entries.isEmpty()) return
        val i = index.coerceIn(0, entries.size - 1)
        rebuildOrder(i)
        jumpTo(0, autoPlay = true)
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            userWantsPlay = false
            p.pause()
            statusText = "일시정지"
            publish()
        } else {
            if (currentEntry == null) {
                playTrack(0)
                return
            }
            // 목록 끝까지 재생해 멈춘 상태라면 처음 곡부터 다시 시작한다
            if (endedAtList) {
                endedAtList = false
                jumpTo(0, autoPlay = true)
                return
            }
            // 목록 끝에서 stop() 된 경우(STATE_IDLE)에는 prepare() 부터 다시 해야 한다.
            // 끝까지 재생돼 멈춘 경우(STATE_ENDED)에는 처음 위치로 되감아야 한다.
            // 그냥 play() 만 하면 아무 일도 일어나지 않아 버튼이 죽은 것처럼 보인다.
            when (p.playbackState) {
                Player.STATE_IDLE -> runCatching { p.prepare() }
                Player.STATE_ENDED -> runCatching { p.seekTo(0) }
            }
            userWantsPlay = true
            statusText = "재생 중"
            p.play()
            startTicker()
            publish()
        }
    }

    fun next() {
        if (order.isEmpty()) return
        syncSettingsFromPrefs()
        val target = pos + 1
        if (target >= order.size) {
            if (!canWrap) {
                stopPlayback()
                return
            }
            // 목록 끝 → 처음 곡으로 (재생순서를 늘리지 않는다)
            jumpTo(0, autoPlay = true)
            return
        }
        jumpTo(target.coerceAtMost(order.size - 1), autoPlay = true)
    }

    /** 목록 끝에서 다시 처음으로 돌아갈 수 있는지 (전체 반복 또는 무한 재생) */
    private val canWrap: Boolean
        get() = infinite || repeatMode == "all"

    /**
     * 저장된 설정을 메모리 값에 다시 반영한다.
     * (화면에서 설정을 바꿨는데 메모리 값이 어긋나면 동작이 틀어지므로,
     *  곡 전환/목록 끝 같은 결정적인 순간에 한 번씩 맞춘다)
     */
    private fun syncSettingsFromPrefs() {
        try {
            val r = Prefs.repeat
            val i = Prefs.infinite
            if (r != repeatMode || i != infinite) {
                repeatMode = r
                infinite = i
                applyPlayerRepeat()
            }
        } catch (_: Exception) {
        }
    }

    fun prev() {
        val p = player ?: return
        if (p.currentPosition > 3_000) {
            p.seekTo(0)
            publish()
            return
        }
        if (order.isEmpty()) return
        val target = pos - 1
        if (target < 0) {
            // 첫 곡이면 처음으로 되감기만 한다
            p.seekTo(0)
            publish()
            return
        }
        jumpTo(target, autoPlay = true)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val d = p.duration
        val target = if (d > 0 && ms > d - 1_500) d else ms
        p.seekTo(target.coerceAtLeast(0))
        publish()
    }

    /**
     * 다음에 재생될 곡 (지금 재생 화면이 영상을 미리 받아두는 데 쓴다).
     * 실제 재생 순서(order)를 그대로 따르므로 셔플/반복 설정과도 일치한다.
     */
    fun peekNextTrack(): Track? {
        if (order.isEmpty() || pos < 0) return null
        val target = pos + 1
        val slot = if (target < order.size) {
            target
        } else if (canWrap) {
            0
        } else {
            return null
        }
        val index = order.getOrNull(slot) ?: return null
        return entries.getOrNull(index)?.track
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player?.volume = volume
        Prefs.volume = volume
        publish()
    }

    fun setShuffle(enabled: Boolean) {
        shuffleOn = enabled
        Prefs.shuffle = enabled
        if (entries.isNotEmpty()) {
            val currentId = currentEntry?.track?.id
            var idx = entries.indexOfFirst { it.track.id == currentId }
            if (idx < 0) idx = 0
            rebuildOrder(idx)
            if (currentEntry != null) {
                jumpTo(0, autoPlay = player?.isPlaying == true)
            }
        }
        publish()
    }

    /**
     * 목록을 섞어서 처음부터 재생 (즐겨찾기 "셔플 재생").
     *
     * 셔플을 켠 뒤 큐를 넣어야 rebuildOrder() 가 섞는다.
     * (켜자마자 setShuffle() 이 현재 곡을 다시 잡으려 하므로 setQueue 로 한 번에 처리)
     */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        shuffleOn = true
        Prefs.shuffle = true
        setQueue(tracks, 0)
    }

    fun setRepeat(mode: String) {
        repeatMode = mode
        Prefs.repeat = mode
        applyPlayerRepeat()
        // 설정이 실제로 적용됐는지 눈으로 확인할 수 있게 알려준다
        toast(
            when (mode) {
                "all" -> "반복: 전체"
                "one" -> "반복: 한 곡"
                else -> "반복: 끔"
            }
        )
        publish()
    }

    /** ExoPlayer 자체 반복 설정 (한 곡 반복 = REPEAT_MODE_ONE) */
    private fun applyPlayerRepeat() {
        val p = player ?: return
        try {
            p.repeatMode = if (repeatMode == "one") {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
        } catch (_: Exception) {
        }
    }

    fun setInfinite(enabled: Boolean) {
        infinite = enabled
        Prefs.infinite = enabled
        toast(if (enabled) "무한 재생: 켬" else "무한 재생: 끔")
        publish()
    }

    /**
     * 진단 모드 켜기/끄기.
     *
     *  - 끔(기본) : 오류 배너를 숨기고 오류/스택 기록도 남기지 않는다
     *  - 켬      : 진단 화면·오류 표시·기록을 모두 사용
     */
    fun setDiagnostics(enabled: Boolean) {
        Prefs.diagnostics = enabled
        if (!enabled) {
            errorText = ""
            runCatching { org.music.infiniteplayer.core.CrashLog.clear() }
        }
        toast(
            if (enabled) "진단 모드: 켬 (오류 표시·기록)"
            else "진단 모드: 끔 (오류 숨김)"
        )
        publish()
    }

    fun stopPlayback() {
        val p = player ?: return
        userWantsPlay = false
        endedAtList = true
        p.pause()
        p.stop()
        statusText = "정지"
        publish()
    }

    // ── 재생순서 ───────────────────────────────────────────────
    private fun rebuildOrder(startIndex: Int) {
        generation++
        order.clear()
        if (shuffleOn) {
            // 셔플 : 고른 곡 뒤로 나머지를 무작위로
            order.add(startIndex)
            val rest = (0 until entries.size).filter { it != startIndex }.toMutableList()
            rest.shuffle(Random(System.nanoTime()))
            order.addAll(rest)
        } else {
            // 셔플 아님 : 고른 곡부터 목록 "끝까지 순서대로"
            // (예전에는 고른 곡 다음이 목록 0번이라, 곡이 끝나면
            //  차트 첫 번째 곡으로 튀어버렸다)
            for (i in startIndex.coerceAtLeast(0) until entries.size) order.add(i)
        }
        windowStart = 0
        pos = -1
    }

    /**
     * 곡 id 로 재생순서(order) 상의 위치를 찾는다.
     *
     * anchor(보통 "지금 위치 + 1")에 가장 가까운 항목을 고르므로
     * 무한재생으로 같은 곡이 여러 번 들어있어도 정확히 다음 항목을 집는다.
     */
    private fun findNearestOrderIndex(id: String?, anchor: Int): Int {
        if (id.isNullOrBlank()) return -1
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in order.indices) {
            val entry = entries.getOrNull(order[i]) ?: continue
            if (entry.track.id != id) continue
            val distance = kotlin.math.abs(i - anchor)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    // ── 재생 제어 내부 ─────────────────────────────────────────
    private fun jumpTo(target: Int, autoPlay: Boolean) {
        val p = player ?: return
        val entry = entries.getOrNull(order.getOrNull(target) ?: -1) ?: return
        generation++
        pos = target
        retryCount = 0
        endedAtList = false
        downloadProgress = -1
        errorText = ""
        statusText = "'${entry.track.title}' 준비 중..."
        publish()

        scope.launch {
            preparing = true
            publish()
            val audio = resolveEntry(entry)
            preparing = false
            if (audio == null) {
                // 곡을 찾지 못했으면 오류를 띄우지 않고 조용히 다음 곡으로 넘어간다.
                // (목록에는 보이는데 재생만 안 되는 곡 때문에 앱이 멈춘 것처럼 보이던 문제)
                entry.job = null
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFailureAt > 60_000L) consecutiveFailures = 0
                lastFailureAt = now
                consecutiveFailures++
                if (consecutiveFailures >= 4) {
                    // 연속 실패 : 끝없이 곡만 넘어가지 않도록 여기서 멈춘다
                    consecutiveFailures = 0
                    errorText = "연속 재생 실패 · 네트워크를 확인해 주세요"
                    statusText = "재생 실패"
                    publish()
                    return@launch
                }
                val following = target + 1
                if (following < order.size) {
                    statusText = "'${entry.track.title}' 건너뛰는 중..."
                    publish()
                    kotlinx.coroutines.delay(250)
                    jumpTo(following, autoPlay = true)
                } else {
                    stopPlayback()
                }
                return@launch
            }
            val items = ArrayList<MediaItem>(1)
            items.add(mediaItem(entry, audio))
            windowStart = target
            // setMediaItems 가 리스너를 부르므로 그 전에 현재 곡을 확정해 둔다
            currentEntry = entry
            p.setMediaItems(items, 0, 0L)
            p.prepare()
            // 외부(블루투스/차량/알림)에서 반복 모드가 바뀌었을 수 있으므로 다시 적용
            applyPlayerRepeat()
            if (autoPlay) {
                userWantsPlay = true
                p.play()
                startTicker()
            }
            onTrackChanged(entry)
            fillWindow()
        }
    }

    private suspend fun resolveEntry(
        entry: Entry,
        prefer: List<Int>? = null,
        useCache: Boolean = true,
    ): ResolvedAudio? {
        entry.audio?.let { return it }
        if (useCache) {
            audioCache[entry.track.id]?.let {
                entry.audio = it
                entry.thumb = Images.thumbFile(it.thumbUrl)
                return it
            }
        }
        entry.job?.let { existing ->
            return try {
                existing.await()
            } catch (_: Exception) {
                null
            }
        }
        val job = scope.async {
            try {
                youtube.quality = Prefs.quality
                val audio = youtube.resolve(entry.track, prefer)
                if (audio != null) {
                    entry.audio = audio
                    entry.thumb = Images.cacheRemote(audio.thumbUrl)
                    rememberAudio(entry.track.id, audio)
                    streamInfo = "itag ${audio.itag} · ${audio.mimeType.substringBefore(";")}"
                }
                audio
            } catch (_: Exception) {
                null
            }
        }
        entry.job = job
        return try {
            job.await()
        } catch (_: Exception) {
            null
        }
    }

    /** recently 해석한 스트림 캐시 (소스 전환 후 돌아와도 바로 재생) */
    private fun rememberAudio(id: String, audio: ResolvedAudio) {
        if (audioCache.size > 120) audioCache.clear()
        audioCache[id] = audio
    }

    /** 다음 곡을 미리 준비한다. (바로 다음 곡 전환이 즉시 이뤄지도록) */
    private fun fillWindow() {
        if (fillJob?.isActive == true) {
            fillAgain = true
            return
        }
        fillJob = scope.launch {
            do {
                fillAgain = false
                fillOnce()
            } while (fillAgain)
        }
    }

    private suspend fun fillOnce() {
        val p = player ?: return
        syncSettingsFromPrefs()
        val gen = generation
        // 뒤로 지나간 항목은 제거하되, 직전 곡 1개는 남긴다
        // (차량/알림의 "이전 곡" 버튼이 동작하도록)
        val curIdx = p.currentMediaItemIndex
        if (curIdx > 1) {
            p.removeMediaItems(0, curIdx - 1)
            // 현재 곡의 order 위치는 그대로이고 플레이어 인덱스만 바뀐다.
            // (인덱스가 바뀌는 순간 리스너가 windowStart 를 다시 계산하므로
            //  여기서도 pos 기준으로 맞춰 둔다 → 곡이 1~2칸 밀리는 문제 방지)
            windowStart = pos - p.currentMediaItemIndex
        }
        var added = 0
        while (added < 2 && currentCoroutineContext().isActive) {
            if (gen != generation) return   // 재생목록이 바뀌었으면 중단
            val wantPos = windowStart + p.mediaItemCount
            // 재생순서를 늘리지 않는다. 목록 끝이면 더 붙이지 않는다.
            // (늘려 붙이면 "반복 끄기"에서도 끝없이 다음 곡이 이어진다)
            if (wantPos >= order.size) break
            val entry = entries.getOrNull(order.getOrNull(wantPos) ?: -1) ?: break
            val audio = entry.audio ?: resolveEntry(entry) ?: break
            if (gen != generation) return
            p.addMediaItem(mediaItem(entry, audio))
            added++
        }
    }

    private fun mediaItem(entry: Entry, audio: ResolvedAudio): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(entry.track.title)
            .setArtist(entry.track.artist)
            .setAlbumTitle(entry.track.album)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        entry.thumb?.let { md.setArtworkUri(Uri.fromFile(it)) }
        // 로컬로 내려받은 파일이 있으면 그걸 재생 (직접 스트리밍 실패 대비)
        val local = entry.localFile
        val uri = if (local != null && local.exists()) Uri.fromFile(local) else Uri.parse(audio.url)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(entry.track.id)
            .setMediaMetadata(md.build())
            .build()
    }

    private fun onTrackChanged(entry: Entry) {
        publish()
    }

    // ── 통화 ───────────────────────────────────────────────────
    private fun onCallStarted() {
        val p = player ?: return
        if (!p.isPlaying) return
        callPaused = true
        p.pause()
        statusText = "통화 중"
        publish()
    }

    private fun onCallEnded() {
        if (!callPaused) return
        callPaused = false
        val p = player ?: return
        if (userWantsPlay) {
            statusText = "재생 중"
            p.play()
            startTicker()
        }
        publish()
    }

    // ── 상태 ───────────────────────────────────────────────────
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var saveTick = 0
            while (isActive) {
                publish()
                // 재생 위치를 주기적으로 저장 (서비스 죽어도 복원되도록)
                saveTick++
                if (saveTick % 8 == 0) {
                    try {
                        val p = player
                        if (p != null && currentEntry != null) {
                            Prefs.playbackPosition = p.currentPosition
                            Prefs.playbackPlaying = p.isPlaying
                            // 목록 인덱스로 저장 (복원 시 order 가 아니라 목록 기준)
                            // 찾지 못했을 때 0 으로 덮어쓰면, 서비스가 다시 살아날 때
                            // 엉뚱하게 목록 첫 곡부터 재생되므로 저장하지 않는다.
                            val listIndex = entries.indexOfFirst {
                                it.track.id == currentEntry?.track?.id
                            }
                            if (listIndex >= 0) Prefs.playbackIndex = listIndex
                        }
                    } catch (_: Exception) {
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * 상태 publish.
     *
     * 주의 1 : AudioManager 호출(Binder)을 절대 넣지 않는다.
     *   여기서 getDevices() 등을 호출하면 250ms마다 메인 스레드가 막혀
     *   화면이 얼어붙는다. 진단 정보는 refreshDiagnostics() 로 필요할 때만 갱신.
     * 주의 2 : ExoPlayer 는 메인 스레드에서만 접근해야 한다.
     *   (다른 스레드에서 접근하면 "Player is accessed on the wrong thread" 로 죽는다)
     *   → 메인 스레드가 아니면 메인 스레드로 넘긴다.
     */
    private fun publish() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { publish() }
            return
        }
        val p = player
        val entry = currentEntry
        val track = entry?.track
        refreshStatus()
        val next = PlayerState(
            playing = p?.isPlaying == true,
            buffering = p?.playbackState == Player.STATE_BUFFERING,
            index = order.getOrNull(pos) ?: -1,
            count = entries.size,
            track = track,
            title = track?.title ?: "",
            artist = track?.artist ?: "",
            album = track?.album ?: "",
            thumbUrl = track?.thumbUrl ?: "",
            shuffle = shuffleOn,
            repeat = repeatMode,
            infinite = infinite,
            diagnostics = Prefs.diagnostics,
            volume = volume,
            status = statusText,
            error = errorText,
            callActive = callPaused,
            downloadProgress = downloadProgress,
            toast = toastText,
            toastId = toastId,
        )
        // 실제로 바뀔 때만 알린다 (홈 화면 불필요한 재생성 방지)
        if (next != lastState) {
            lastState = next
            PlayerBus.publish(next)
        }
        // 워치독 스택 덤프에 남길 한 줄 요약
        PlayerBus.stateLine = buildString {
            append("설정반복=").append(repeatMode)
            append(" 무한=").append(infinite)
            append(" pos=").append(pos)
            append("/").append(order.size)
            append(" 큐=").append(p?.mediaItemCount ?: -1)
            append(" 시작=").append(windowStart)
            append(" 상태=").append(p?.playbackState ?: -1)
            append(" 플레이어반복=").append(p?.repeatMode ?: -1)
            append(" 재생=").append(p?.isPlaying == true)
            append(" 버전=").append(org.music.infiniteplayer.core.CrashLog.BuildInfo.VERSION)
        }
        PlayerBus.publishTick(
            PlaybackTick(
                positionMs = p?.currentPosition ?: 0L,
                durationMs = p?.duration ?: 0L,
            )
        )
    }

    /** 진단 정보 갱신.
     *
     * 플레이어 상태는 메인 스레드에서 읽고(ExoPlayer 제약),
     * AudioManager 조회는 IO 스레드에서 한다.
     */
    fun refreshDiagnostics() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { refreshDiagnostics() }
            return
        }
        val p = player
        val state = playerStateName(p?.playbackState)
        val info = streamInfo
        val probe = streamProbe
        val queue = if (p == null) {
            "-"
        } else {
            val playerRepeat = when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> "한곡"
                Player.REPEAT_MODE_ALL -> "전체"
                else -> "없음"
            }
            "pos=$pos / 순서=${order.size} / 큐=${p.mediaItemCount} / 시작=$windowStart" +
                " / 설정=$repeatMode / 플레이어반복=$playerRepeat"
        }
        scope.launch(Dispatchers.IO) {
            PlayerBus.publishDiagnostics(
                Diagnostics(
                    playerState = state,
                    streamInfo = info,
                    streamProbe = probe,
                    systemVolume = systemVolumeText(),
                    audioRoute = audioRouteText(),
                    serviceState = serviceStateText(),
                    notificationState = notificationStateText(),
                    uiLatency = uiLatencyText(),
                    queueInfo = queue,
                    selfTest = selfTestText,
                )
            )
        }
    }

    /**
     * 연결 테스트 : 유튜브 접속 → 검색 → 음원 추출 → 음원 서버 접속까지
     * 어디서 막히는지 한 번에 확인한다. (진단 화면의 "연결 테스트" 버튼)
     */
    fun runSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        selfTestText = "테스트 중..."
        publish()
        refreshDiagnostics()
        scope.launch {
            val sb = StringBuilder()
            // 1) 유튜브 접속
            try {
                val html = org.music.infiniteplayer.core.Net.get(
                    "https://www.youtube.com/",
                    mapOf("User-Agent" to org.music.infiniteplayer.core.Net.UA_DESKTOP),
                )
                sb.appendLine("1) 유튜브 접속: OK (${html.length / 1024}KB)")
            } catch (e: Exception) {
                sb.appendLine("1) 유튜브 접속: 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
            // 2) 검색
            var probe: Track? = null
            try {
                val found = youtube.search("아이유 좋은날", 5)
                sb.appendLine("2) 검색: ${found.size}곡")
                probe = found.firstOrNull()
            } catch (e: Exception) {
                sb.appendLine("2) 검색: 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
            // 3) 음원 추출 + 4) 음원 서버 접속
            try {
                val audio = probe?.let { youtube.resolve(it) }
                if (audio == null) {
                    sb.appendLine("3) 음원 추출: 실패 (재생 주소 없음)")
                } else {
                    sb.appendLine("3) 음원 추출: OK (itag ${audio.itag})")
                    val code = try {
                        val conn = java.net.URL(audio.url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.setRequestProperty("Range", "bytes=0-1023")
                        val c = conn.responseCode
                        runCatching { conn.inputStream?.close() }
                        conn.disconnect()
                        "HTTP $c"
                    } catch (e: Exception) {
                        "실패 · ${e.message ?: e.javaClass.simpleName}"
                    }
                    sb.appendLine("4) 음원 서버: $code")
                }
            } catch (e: Exception) {
                sb.appendLine("3) 음원 추출: 예외 · ${e.message ?: e.javaClass.simpleName}")
            }
            selfTestText = sb.toString().trim()
            selfTestRunning = false
            publish()
            refreshDiagnostics()
        }
    }

    /** 서비스가 포그라운드로 살아있는지 (앱 일시정지/프로세스 동결 판별용) */
    private fun serviceStateText(): String {
        return try {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val list = am.getRunningServices(100) ?: emptyList()
            val svc = list.firstOrNull { it.service.packageName == packageName }
            when {
                svc == null -> "서비스 없음 (죽음)"
                svc.foreground -> "포그라운드 실행 중"
                svc.started -> "실행 중 (백그라운드)"
                else -> "실행 중"
            }
        } catch (e: Exception) {
            "확인 불가: ${e.javaClass.simpleName}"
        }
    }

    /** 포그라운드 알림이 표시되고 있는지 (안 보이면 시스템이 서비스를 정리할 수 있음) */
    private fun notificationStateText(): String {
        return try {
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return "확인 불가"
            }
            val mine = nm.activeNotifications.filter { it.packageName == packageName }
            when (mine.size) {
                0 -> "알림 없음"
                1 -> "알림 1개"
                else -> "알림 ${mine.size}개"
            }
        } catch (e: Exception) {
            "확인 불가"
        }
    }

    /** UI 스레드(메인) 응답 속도 측정 */
    private fun uiLatencyText(): String {
        return try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val latch = java.util.concurrent.CountDownLatch(1)
            val start = SystemClock.uptimeMillis()
            handler.post { latch.countDown() }
            val ms = if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                (SystemClock.uptimeMillis() - start).toString()
            } else {
                "5초 초과 (멈춤)"
            }
            "$ms ms"
        } catch (e: Exception) {
            "확인 불가"
        }
    }

    /** 진단 정보 주기적 갱신 (AudioManager 호출은 IO 스레드에서) */
    private fun startDiagnosticsLoop() {
        scope.launch {
            while (isActive) {
                try {
                    refreshDiagnostics()
                } catch (e: Exception) {
                    // 진단 실패가 앱을 죽이면 안 된다
                }
                delay(10_000)
            }
        }
    }

    /** ExoPlayer 의 실제 상태를 그대로 보여준다 (버퍼링/오류를 '재생 중'으로 덮지 않는다) */
    private fun refreshStatus() {
        // 진행 중 안내(다운로드/자르기 등)는 자동 상태에 덮어쓰지 않는다
        if (manualStatusText.isNotBlank()) {
            statusText = manualStatusText
            return
        }
        val p = player
        statusText = when {
            callPaused -> "통화 중"
            errorText.isNotBlank() -> "오류"
            preparing -> "음원 준비 중..."
            p == null -> "대기 중"
            p.playbackState == Player.STATE_BUFFERING -> "버퍼링 중..."
            p.playbackState == Player.STATE_READY -> if (p.isPlaying) "재생 중" else "일시정지"
            p.playbackState == Player.STATE_ENDED -> "재생 종료"
            p.playbackState == Player.STATE_IDLE -> "대기 중"
            else -> "대기 중"
        }
    }

    /** 잠깐 동안 표시할 상태 (다운로드/구간자르기 등) */
    private var manualStatusText = ""

    private fun setManualStatus(text: String) {
        manualStatusText = text
        publish()
    }

    private fun clearManualStatus() {
        manualStatusText = ""
    }

    /** 사용자에게 보여줄 토스트 메시지 */
    private fun toast(text: String) {
        toastText = text
        toastId++
        publish()
    }

    private fun playerStateName(state: Int?): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

    /** 시스템 미디어 볼륨 (0 이면 소리가 안 나옴) */
    private fun systemVolumeText(): String {
        return try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            "$cur / $max"
        } catch (_: Exception) {
            "-"
        }
    }

    /** 현재 오디오 출력 경로 (스피커/이어폰/블루투스/차량) */
    private fun audioRouteText(): String {
        return try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val names = devices.mapNotNull { d ->
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "스피커"
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화기"
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 이어폰"
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "유선 이어폰"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "블루투스"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스(통화)"
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 이어폰"
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
                    android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                    android.media.AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                    android.media.AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
                    else -> null
                }
            }.distinct()
            if (names.isEmpty()) "알 수 없음" else names.joinToString(", ")
        } catch (_: Exception) {
            "-"
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ── 플레이어 이벤트 ────────────────────────────────────────
    private inner class Listener : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            if (idx < 0) return

            // 한 곡 반복 : 자동으로 다음 곡으로 넘어가면 원래 곡으로 되돌린다.
            // (플레이어 반복 모드가 외부 요인으로 무시된 경우의 안전장치)
            if (repeatMode == "one" && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val prev = idx - 1
                if (prev >= 0) {
                    try {
                        p.seekTo(prev, 0L)
                        p.play()
                    } catch (_: Exception) {
                    }
                    windowStart = pos - p.currentMediaItemIndex
                    return
                }
            }

            val id = mediaItem?.mediaId

            // 1) 같은 곡인데 플레이어 인덱스만 바뀐 경우
            //    (앞쪽 아이템을 정리하면 인덱스가 밀린다 → windowStart 를 다시 맞춘다)
            if (id != null && currentEntry?.track?.id == id) {
                windowStart = pos - idx
                fillWindow()
                return
            }

            // 2) 곡이 바뀐 경우 : 인덱스 계산(windowStart + idx)은 목록 정리와
            //    겹치면 1~2칸씩 밀린다. 곡 id 로 order 상의 위치를 찾는다.
            val newPos = findNearestOrderIndex(id, pos + 1)
            if (newPos < 0) {
                windowStart = pos - idx
                fillWindow()
                return
            }
            val entry = entries.getOrNull(order[newPos]) ?: return
            pos = newPos
            windowStart = pos - idx
            currentEntry = entry
            statusText = "재생 중"
            errorText = ""
            onTrackChanged(entry)
            fillWindow()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // 블루투스/차량/알림 등 외부에서 반복 모드를 바꾸면
            // 앱 설정값으로 즉시 되돌린다.
            // (외부에서 ALL 로 바뀌면 반복 끔/한 곡 반복이 전부 무시된다)
            val p = player ?: return
            val want = if (this@PlaybackService.repeatMode == "one") {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            if (p.repeatMode != want) {
                try {
                    p.repeatMode = want
                } catch (_: Exception) {
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                statusText = "재생 중"
                // 실제로 재생 중이면 지난 오류 표시는 지운다.
                // (복구에 성공했는데도 상단에 오류가 계속 남아 있는 문제)
                errorText = ""
                consecutiveFailures = 0
                startTicker()
            } else if (!callPaused) {
                val p = player
                when (p?.playbackState) {
                    Player.STATE_ENDED -> statusText = if (repeatMode == "one") "반복 재생" else "재생 종료"
                    Player.STATE_BUFFERING -> statusText = "버퍼링..."
                    else -> if (userWantsPlay) "일시정지" else statusText
                }
                stopTicker()
            }
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) {
                publish()
                return
            }
            // 플레이어 재생목록의 끝까지 왔다 (미리 채우지 못한 경우 포함)
            syncSettingsFromPrefs()
            when (repeatMode) {
                "one" -> {
                    // 한 곡 반복 (플레이어 반복모드가 처리하지만 안전장치)
                    player?.seekTo(0)
                    player?.play()
                    userWantsPlay = true
                    startTicker()
                }
                else -> {
                    // 반복/무한재생이 꺼져 있으면 "플레이어 큐의 끝 = 목록의 끝"이므로
                    // 위치 계산 없이 그대로 정지한다. (인덱스가 어긋나도 안전)
                    if (!canWrap) {
                        userWantsPlay = false
                        endedAtList = true
                        statusText = "재생 종료 (목록 끝)"
                        // 끝난 상태에서도 ▶ 를 누르면 다시 재생되도록 정리
                        runCatching { player?.pause() }
                    } else {
                        // 정확히 "다음 1곡"만 이동 (중복 이동 금지)
                        statusText = "다음 곡 준비 중..."
                        next()
                    }
                }
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            val desc = describeError(error)
            runCatching { org.music.infiniteplayer.core.CrashLog.save(error) }
            // 앱을 다시 열어도 원인을 볼 수 있도록 따로 남긴다
            runCatching {
                Prefs.lastError = "'${currentEntry?.track?.title ?: "?"}' · $desc"
                Prefs.lastErrorAt = java.text.SimpleDateFormat(
                    "MM-dd HH:mm:ss", java.util.Locale.KOREA
                ).format(java.util.Date())
            }
            val p = player
            val duration = p?.duration ?: 0L
            val position = p?.currentPosition ?: 0L
            // 곡 끝부분(마지막 5초)에서 나는 IO 오류는 "끝까지 재생됨"으로 보고
            // 오류를 띄우지 않고 다음 곡으로 넘어간다 (사용자 입장에서는 정상 종료)
            if (duration > 0 && position >= duration - 5_000) {
                retryCount = 0
                errorText = ""
                // 한 곡 반복 중이면 다음 곡으로 넘어가지 않고 같은 곡을 다시 튼다
                if (this@PlaybackService.repeatMode == "one") {
                    streamProbe = "끝부분 오류($desc) → 한 곡 반복"
                    userWantsPlay = true
                    publish()
                    runCatching {
                        p?.seekTo(0)
                        p?.play()
                    }
                    startTicker()
                    return
                }
                streamProbe = "끝부분 오류($desc) → 다음 곡"
                publish()
                next()
                return
            }
            errorText = "재생 오류 ($desc)"
            publish()
            recoverFromError(desc)
        }
    }

    /** 시스템 미디어 볼륨이 0 이면 소리가 안 들린다. 60% 로 올려준다. */
    fun raiseSystemVolume() {
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val target = (max * 0.6f).toInt().coerceIn(1, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            publish()
        } catch (_: Exception) {
        }
    }

    // ── 구간 반복 (삭제됨) ─────────────────────────────────────

    /** 파일로 저장할 때 쓰는 오디오 (AAC/m4a 로 통일 → 음악라이브러리 호환) */
    private suspend fun resolveForFile(entry: Entry): ResolvedAudio? {
        val current = entry.audio
        if (current != null && current.mimeType.contains("mp4")) return current
        // webm/opus 로 잡혀 있으면 지우고 m4a 로 다시 해석한다
        entry.audio = null
        entry.job = null
        return resolveEntry(entry, prefer = listOf(140, 139), useCache = false)
    }

    /** 현재 곡을 기기(음악 라이브러리)에 저장 */
    fun downloadToDevice() {
        val entry = currentEntry
        if (entry == null) {
            toast("재생 중인 곡이 없습니다")
            return
        }
        scope.launch {
            val dest = File(cacheDir, "dl_${entry.track.id.hashCode()}.m4a")
            downloadProgress = 0
            setManualStatus("내려받는 중...")
            // 주소는 IP 로 서명되어 있어 모바일 환경에서 오래되면 403 이 된다.
            // → 실패하면 새 주소로 받아 즉시 한 번 더 시도한다.
            var audio = resolveForFile(entry)
            if (audio == null) {
                errorText = "스트림 주소를 얻지 못했습니다"
                clearManualStatus()
                toast("다운로드 실패: 스트림 주소를 얻지 못했습니다")
                publish()
                return@launch
            }
            var res = org.music.infiniteplayer.core.Downloader.download(audio.url, dest) { percent ->
                downloadProgress = percent
                publish()
            }
            if (!res.ok) {
                entry.audio = null
                entry.job = null
                audio = resolveEntry(entry, prefer = listOf(140, 139), useCache = false)
                if (audio != null) {
                    res = org.music.infiniteplayer.core.Downloader.download(audio.url, dest) { percent ->
                        downloadProgress = percent
                        publish()
                    }
                }
            }
            downloadProgress = -1
            val useAudio = audio
            if (!res.ok || useAudio == null) {
                errorText = "다운로드 실패: ${res.error.ifBlank { "알 수 없는 오류" }}"
                clearManualStatus()
                toast("다운로드 실패: ${res.error.ifBlank { "알 수 없는 오류" }}")
                publish()
                return@launch
            }
            val name = safeFileName(entry.track, useAudio.mimeType)
            val saved = org.music.infiniteplayer.core.MediaSaver.saveToMusic(
                this@PlaybackService, dest, name, useAudio.mimeType,
            )
            if (saved.uri == null) {
                errorText = "저장 실패: ${saved.error.ifBlank { "알 수 없는 오류" }}"
                clearManualStatus()
                toast("저장 실패: ${saved.error.ifBlank { "알 수 없는 오류" }}")
                publish()
                return@launch
            }
            errorText = ""
            setManualStatus("다운로드 완료")
            toast("다운로드 완료 · $name")
            publish()
        }
    }

    /**
     * 현재 곡을 벨소리로 저장하고, 바로 기본 벨소리로 적용한다.
     *
     *  1) AAC(m4a) 스트림을 내려받는다 (다운로드 버튼과 같은 경로)
     *  2) MediaStore 벨소리 목록에 "벨소리-곡명.m4a" 로 저장
     *  3) RingtoneSetter 로 시스템 기본 벨소리에 바로 설정한다
     *
     * (구간 잘라내기 · 페이드 인/아웃 · 하이라이트 자동 탐색은 제거)
     *
     * "시스템 설정에 쓰기" 권한이 없으면 권한 화면을 열고 토스트로 안내한다.
     */
    fun setAsRingtone() {
        val entry = currentEntry
        if (entry == null) {
            toast("재생 중인 곡이 없습니다")
            return
        }
        if (!org.music.infiniteplayer.core.RingtoneSetter.canWriteSettings(this)) {
            toast("벨소리 권한이 없습니다 · 시스템 설정 권한을 허용해 주세요")
            org.music.infiniteplayer.core.RingtoneSetter.openWriteSettingsPage(this)
            return
        }
        scope.launch {
            val src = File(cacheDir, "dl_${entry.track.id.hashCode()}.m4a")
            downloadProgress = 0
            setManualStatus("벨소리 저장 중...")

            var audio = resolveForFile(entry)
            if (audio == null) {
                errorText = "스트림 주소를 얻지 못했습니다"
                clearManualStatus()
                toast("벨소리 실패: 스트림 주소를 얻지 못했습니다")
                publish()
                return@launch
            }
            var res = org.music.infiniteplayer.core.Downloader.download(audio.url, src) { percent ->
                downloadProgress = percent
                publish()
            }
            if (!res.ok) {
                entry.audio = null
                entry.job = null
                audio = resolveEntry(entry, prefer = listOf(140, 139), useCache = false)
                if (audio != null) {
                    res = org.music.infiniteplayer.core.Downloader.download(audio.url, src) { percent ->
                        downloadProgress = percent
                        publish()
                    }
                }
            }
            downloadProgress = -1
            val useAudio = audio
            if (!res.ok || useAudio == null) {
                errorText = "벨소리 실패: ${res.error.ifBlank { "알 수 없는 오류" }}"
                clearManualStatus()
                toast("벨소리 실패: ${res.error.ifBlank { "알 수 없는 오류" }}")
                publish()
                return@launch
            }

            // 잘라내기/재인코딩 없이 받은 그대로 벨소리로 저장한다
            val ext = org.music.infiniteplayer.core.MediaSaver.extensionFor(useAudio.mimeType)
            val title = org.music.infiniteplayer.core.MediaSaver.safeName(
                entry.track.title.ifBlank { "곡명" },
            )
            val name = "벨소리-$title.$ext"
            val saved = org.music.infiniteplayer.core.MediaSaver.saveAsRingtone(
                this@PlaybackService, src, name, useAudio.mimeType,
            )
            if (saved.uri == null) {
                errorText = "저장 실패: ${saved.error.ifBlank { "알 수 없는 오류" }}"
                clearManualStatus()
                toast("벨소리 실패: ${saved.error.ifBlank { "알 수 없는 오류" }}")
                publish()
                return@launch
            }

            val applied = org.music.infiniteplayer.core.RingtoneSetter.setRingtone(
                this@PlaybackService, saved.uri,
            )
            errorText = ""
            if (applied) {
                setManualStatus("벨소리 적용 완료")
                toast("벨소리 적용 완료 · $name")
                // 하이라이트까지 자동으로 잡도록 갤럭시 사운드 선택기를 연다
                openSoundPicker(saved.uri)
            } else {
                setManualStatus("벨소리 저장 완료 (권한 필요)")
                toast("벨소리 저장 완료 · $name · 권한이 없어 적용은 실패했습니다")
                org.music.infiniteplayer.core.RingtoneSetter.openWriteSettingsPage(this@PlaybackService)
            }
            publish()
        }
    }

    /**
     * 벨소리 적용 직후 갤럭시 **사운드 선택기**를 연다.
     *
     * 삼성 사운드 선택기의 **"선택된 하이라이트만 재생"** 은 공개 API 가 없어
     * 앱이 직접 켜줄 수 없다. 그래서 저장한 파일이 보이는 화면으로 이동시켜 주고,
     * 사용자가 그 곡을 한 번 눌러주면 시스템이 하이라이트 구간을 자동으로 잡아
     * 벨소리로 만들어 준다. (벨소리 자체는 이미 위에서 자동 적용된 상태)
     *
     * 기기마다 열리는 앱이 다르므로 후보를 차례로 시도하고,
     * 하나도 없으면 조용히 넘어간다 (실패가 오류처럼 보이지 않게).
     */
    private fun openSoundPicker(saved: Uri?) {
        if (!Prefs.ringtoneSoundPicker) return

        val candidates = mutableListOf<Intent>()
        // 1) 삼성 사운드 선택기 앱 직접 실행
        try {
            packageManager.getLaunchIntentForPackage("com.samsung.android.app.soundpicker")
                ?.let { candidates.add(it) }
        } catch (_: Exception) {
            // 없으면 다음 후보로
        }
        // 2) 사운드 선택기로 지정한 벨소리 선택 화면
        candidates.add(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                setPackage("com.samsung.android.app.soundpicker")
                if (saved != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, saved)
            },
        )
        // 3) 일반 벨소리 선택 화면 (저장한 곡이 목록에 그대로 보인다)
        candidates.add(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                if (saved != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, saved)
            },
        )

        // 실제로 열 수 있는 첫 번째 후보만 사용한다
        var target: Intent? = null
        for (cand in candidates) {
            val resolvable = try {
                cand.resolveActivity(packageManager) != null
            } catch (_: Exception) {
                false
            }
            if (resolvable) {
                target = cand
                break
            }
        }
        val picked = target ?: return
        val started = try {
            picked.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(picked)
            true
        } catch (_: Exception) {
            false
        }
        if (!started) return

        // 선택기가 열리는 중 안내가 보이도록 시스템 토스트로 알려준다
        // (앱 안 토스트는 화면이 가려져 보이지 않으므로 시스템 토스트를 쓴다)
        try {
            android.widget.Toast.makeText(
                this,
                "사운드 선택기에서 \"선택된 하이라이트만 재생\"을 켜고 같은 곡을 선택하면\n" +
                    "하이라이트 구간이 자동으로 적용됩니다",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (_: Exception) {
            // 안내 실패는 재생에 영향을 주지 않으므로 무시한다
        }
    }

    /** 파일명 (확장자는 실제 포맷에 맞춘다) */
    private fun safeFileName(track: Track, mimeType: String): String {
        val raw = if (track.artist.isNotBlank()) "${track.artist} - ${track.title}" else track.title
        var s = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val ext = org.music.infiniteplayer.core.MediaSaver.extensionFor(mimeType)
        if (s.lowercase().endsWith(".$ext")) s = s.substring(0, s.length - ext.length - 1)
        s = s.trim().ifBlank { "audio" }
        return "$s.$ext"
    }

    // ── 오류 복구 ───────────────────────────────────────────────
    /**
     * 재생 실패 시 자동 복구 순서
     *   1) 같은 곡을 새 주소로 다시 해석 (만료/IP 바인딩 문제 해결)
     *   2) 다른 포맷(itag)으로 재시도
     *   3) 로컬 파일로 내려받아 재생
     */
    private fun recoverFromError(desc: String) {
        val entry = currentEntry ?: return
        val p = player ?: return
        if (retryCount >= 3) {
            // 복구 시도(주소 재해석·다른 포맷·파일 재생)를 모두 실패했다.
            // 여기서 멈추면 앱이 죽은 것처럼 보이므로 다음 곡으로 넘어간다.
            retryCount = 0
            entry.audio = null
            entry.job = null
            // 실패가 연달아 터지면 끝없이 곡만 넘어가므로 잠시 멈춘다
            val now = SystemClock.elapsedRealtime()
            if (now - lastFailureAt > 60_000L) consecutiveFailures = 0
            lastFailureAt = now
            consecutiveFailures++
            if (consecutiveFailures >= 4) {
                consecutiveFailures = 0
                errorText = "연속 재생 실패 ($desc) · 네트워크를 확인해 주세요"
                statusText = "재생 실패"
                runCatching { p.pause() }
                publish()
                return
            }
            if (entries.size <= 1) {
                errorText = "재생 오류 ($desc) · 재시도 실패"
                statusText = "재생 실패"
                publish()
                return
            }
            errorText = "재생 오류 ($desc) · 이 곡을 건너뜁니다"
            toast("'${entry.track.title}' 재생 실패 → 다음 곡")
            val failedId = entry.track.id
            scope.launch {
                delay(1500)
                // 그 사이 사용자가 다른 곡을 골랐으면 건너뛰지 않는다
                if (currentEntry?.track?.id == failedId) {
                    errorText = ""
                    next()
                }
            }
            return
        }
        retryCount++
        val attempt = retryCount
        if (attempt >= 3) {
            // 마지막 시도 : 스트리밍 포기하고 파일로 받아서 재생
            errorText = "스트리밍 실패 → 파일로 받는 중..."
            publish()
            downloadAndPlay()
            return
        }
        scope.launch {
            // 1) 현재 주소가 실제로 살아있는지 확인
            val probe = probeUrl(entry.audio?.url.orEmpty())
            streamProbe = probe
            publish()

            // 2) 새 주소로 재해석 (2번째 시도부터는 다른 포맷 사용)
            val prefer = when (attempt) {
                1 -> null
                2 -> listOf(251, 140, 249, 139)
                else -> listOf(139, 249, 140, 251)
            }
            entry.job = null
            entry.audio = null
            entry.localFile = null
            // 캐시를 쓰지 않는다 (그래야 진짜로 새 주소를 받는다)
            val audio = resolveEntry(entry, prefer, useCache = false)
            if (audio == null) {
                errorText = "재생 오류 ($desc) · 스트림을 얻지 못했습니다"
                publish()
                return@launch
            }
            streamInfo = "itag ${audio.itag} · ${audio.mimeType.substringBefore(";")}"
            publish()

            val idx = p.currentMediaItemIndex
            if (idx >= 0) {
                p.replaceMediaItem(idx, mediaItem(entry, audio))
                errorText = ""
                publish()
                return@launch
            }
            // 플레이어에 아이템이 없으면 새로 만들어서 재생
            playEntry(entry)
        }
    }

    /** 사용자가 직접 "다시 시도"를 눌렀을 때 */
    fun retryCurrent() {
        val entry = currentEntry ?: return
        retryCount = 0
        errorText = ""
        streamProbe = "확인 중..."
        publish()
        scope.launch {
            entry.job = null
            entry.audio = null
            val audio = resolveEntry(entry)
            if (audio == null) {
                errorText = "스트림을 얻지 못했습니다 (네트워크 확인)"
                publish()
                return@launch
            }
            streamInfo = "itag ${audio.itag} · ${audio.mimeType.substringBefore(";")}"
            playEntry(entry)
        }
    }

    /** 로컬 파일로 내려받아 재생 (직접 스트리밍이 안 될 때 대체 경로) */
    fun downloadAndPlay() {
        val entry = currentEntry ?: return
        scope.launch {
            val audio = entry.audio ?: resolveEntry(entry)
            if (audio == null) {
                errorText = "스트림 주소를 얻지 못했습니다 (네트워크 확인)"
                publish()
                return@launch
            }
            val url = audio.url
            downloadProgress = 0
            statusText = "음원 내려받는 중..."
            publish()
            val dest = File(cacheDir, "audio_${entry.track.id.hashCode()}.m4a")
            val res = org.music.infiniteplayer.core.Downloader.download(url, dest) { percent ->
                downloadProgress = percent
                publish()
            }
            downloadProgress = -1
            if (!res.ok) {
                errorText = "음원 내려받기에 실패했습니다 (${res.error})"
                publish()
                // 여기서 멈추면 앱이 멈춘 것처럼 보인다 → 다음 곡으로
                if (entries.size > 1) {
                    toast("'${entry.track.title}' 재생 실패 → 다음 곡")
                    val failedId = entry.track.id
                    scope.launch {
                        delay(1500)
                        if (currentEntry?.track?.id == failedId) {
                            errorText = ""
                            next()
                        }
                    }
                }
                return@launch
            }
            entry.localFile = dest
            entry.audio = audio
            errorText = ""
            playEntry(entry)
        }
    }

    private fun playEntry(entry: Entry) {
        val p = player ?: return
        val audio = entry.audio ?: return
        val items = ArrayList<MediaItem>(1)
        items.add(mediaItem(entry, audio))
        val entryIdx = entries.indexOf(entry)
        val orderPos = order.indexOf(entryIdx)
        if (orderPos >= 0) pos = orderPos
        windowStart = if (orderPos >= 0) orderPos else 0
        currentEntry = entry
        p.setMediaItems(items, 0, 0L)
        p.prepare()
        userWantsPlay = true
        p.play()
        startTicker()
        onTrackChanged(entry)
        fillWindow()
        publish()
    }

    /** 스트림 주소가 응답하는지 확인 ( diagnostik 용 + 복구 판단용 ) */
    private suspend fun probeUrl(url: String): String {
        if (url.isBlank()) return "주소 없음"
        return withContext(Dispatchers.IO) {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                conn.requestMethod = "GET"
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", org.music.infiniteplayer.core.Net.UA_YT)
                val code = conn.responseCode
                conn.disconnect()
                "HTTP $code"
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        }
    }

    private fun describeError(error: PlaybackException): String {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return "HTTP ${cause.responseCode}"
            }
            if (cause is androidx.media3.datasource.DataSourceException) {
                val message = cause.message
                if (!message.isNullOrBlank()) return message
            }
            if (cause is java.net.SocketTimeoutException) return "시간 초과"
            if (cause is java.net.UnknownHostException) return "DNS 실패"
            cause = cause.cause
        }
        // 원인을 특정하지 못하면 예외 이름/메시지를 함께 남긴다 (다음 진단용)
        val rootCause = error.cause
        if (rootCause == null) return error.errorCodeName
        val message = rootCause.message
        return buildString {
            append(error.errorCodeName)
            append(" · ")
            append(rootCause.javaClass.simpleName)
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message.take(60))
            }
        }
    }
}

