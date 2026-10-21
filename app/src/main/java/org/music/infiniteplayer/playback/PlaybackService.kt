package org.music.infiniteplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
import org.music.infiniteplayer.MainActivity
import org.music.infiniteplayer.core.Images
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.data.LyricLine
import org.music.infiniteplayer.data.LyricsRepository
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var youtube: YoutubeRepository
    private val lyricsRepo = LyricsRepository()

    private class Entry(val track: Track) {
        @Volatile var audio: ResolvedAudio? = null
        @Volatile var thumb: File? = null
        @Volatile var job: Deferred<ResolvedAudio?>? = null
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

    private var currentEntry: Entry? = null
    private var userWantsPlay = false
    private var callPaused = false
    private var lyrics: List<LyricLine> = emptyList()

    private var statusText = "대기 중"
    private var errorText = ""
    private var ticker: Job? = null
    private var fillJob: Job? = null
    private var fillAgain = false

    private var repeatMode = "all"
    private var infinite = true
    private var shuffleOn = false
    private var volume = 0.85f

    // ── 생애주기 ───────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        youtube = YoutubeRepository(Prefs.quality)

        repeatMode = Prefs.repeat
        infinite = Prefs.infinite
        shuffleOn = Prefs.shuffle
        volume = Prefs.volume

        val exo = ExoPlayer.Builder(this)
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

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openAppIntent())
            .build()

        callGuard = CallGuard(
            context = this,
            scope = scope,
            onCallStarted = { onCallStarted() },
            onCallEnded = { onCallEnded() },
        ).also { it.start() }

        PlayerBus.attach(this)
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.isPlaying) stopSelf()
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
        rebuildOrder(startIndex.coerceIn(0, entries.size - 1))
        jumpTo(0, autoPlay = true)
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
            userWantsPlay = true
            statusText = "재생 중"
            p.play()
            startTicker()
            publish()
        }
    }

    fun next() {
        if (order.isEmpty()) return
        var target = pos + 1
        if (target >= order.size) {
            if (!infinite) {
                stopPlayback()
                return
            }
            appendPermutation()
            target = pos + 1
        }
        jumpTo(target.coerceAtMost(order.size - 1), autoPlay = true)
    }

    fun prev() {
        val p = player ?: return
        if (p.currentPosition > 3_000) {
            p.seekTo(0)
            publish()
            return
        }
        if (order.isEmpty()) return
        val target = if (pos - 1 >= 0) pos - 1 else if (infinite) order.size - 1 else 0
        jumpTo(target, autoPlay = true)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val d = p.duration
        val target = if (d > 0 && ms > d - 1_500) d else ms
        p.seekTo(target.coerceAtLeast(0))
        publish()
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

    fun setRepeat(mode: String) {
        repeatMode = mode
        Prefs.repeat = mode
        publish()
    }

    fun setInfinite(enabled: Boolean) {
        infinite = enabled
        Prefs.infinite = enabled
        publish()
    }

    fun stopPlayback() {
        val p = player ?: return
        userWantsPlay = false
        p.pause()
        p.stop()
        statusText = "정지"
        publish()
    }

    // ── 재생순서 ───────────────────────────────────────────────
    private fun rebuildOrder(startIndex: Int) {
        generation++
        order.clear()
        order.add(startIndex)
        val rest = (0 until entries.size).filter { it != startIndex }.toMutableList()
        if (shuffleOn) rest.shuffle(Random(System.nanoTime()))
        order.addAll(rest)
        windowStart = 0
        pos = -1
    }

    private fun appendPermutation() {
        if (entries.isEmpty()) return
        val list = (0 until entries.size).toMutableList()
        if (shuffleOn) list.shuffle(Random(System.nanoTime()))
        order.addAll(list)
    }

    // ── 재생 제어 내부 ─────────────────────────────────────────
    private fun jumpTo(target: Int, autoPlay: Boolean) {
        val p = player ?: return
        val entry = entries.getOrNull(order.getOrNull(target) ?: -1) ?: return
        generation++
        pos = target
        statusText = "'${entry.track.title}' 준비 중..."
        errorText = ""
        publish()

        scope.launch {
            val audio = resolveEntry(entry)
            if (audio == null) {
                errorText = "'${entry.track.title}' 을(를) 찾을 수 없습니다."
                statusText = "재생 실패"
                publish()
                return@launch
            }
            val items = ArrayList<MediaItem>(1)
            items.add(mediaItem(entry, audio))
            windowStart = target
            p.setMediaItems(items, 0, 0L)
            p.prepare()
            currentEntry = entry
            if (autoPlay) {
                userWantsPlay = true
                p.play()
                startTicker()
            }
            onTrackChanged(entry)
            fillWindow()
        }
    }

    private suspend fun resolveEntry(entry: Entry): ResolvedAudio? {
        entry.audio?.let { return it }
        audioCache[entry.track.id]?.let {
            entry.audio = it
            entry.thumb = Images.thumbFile(it.thumbUrl)
            return it
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
                val audio = youtube.resolve(entry.track)
                if (audio != null) {
                    entry.audio = audio
                    entry.thumb = Images.cacheRemote(audio.thumbUrl)
                    rememberAudio(entry.track.id, audio)
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
        val gen = generation
        // 뒤로 지나간 항목은 제거하되, 직전 곡 1개는 남긴다
        // (차량/알림의 "이전 곡" 버튼이 동작하도록)
        val curIdx = p.currentMediaItemIndex
        if (curIdx > 1) {
            p.removeMediaItems(0, curIdx - 1)
            windowStart += curIdx - 1
        }
        var added = 0
        while (added < 2 && currentCoroutineContext().isActive) {
            if (gen != generation) return   // 재생목록이 바뀌었으면 중단
            val wantPos = windowStart + p.mediaItemCount
            if (wantPos >= order.size) {
                if (!infinite) break
                appendPermutation()
            }
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
        return MediaItem.Builder()
            .setUri(audio.url)
            .setMediaId(entry.track.id)
            .setMediaMetadata(md.build())
            .build()
    }

    private fun onTrackChanged(entry: Entry) {
        lyrics = emptyList()
        publish()
        scope.launch {
            val caption = entry.audio?.captionUrl ?: return@launch
            val loaded = lyricsRepo.fetch(caption)
            if (loaded.isNotEmpty() && currentEntry?.track?.id == entry.track.id) {
                lyrics = loaded
                publish()
            }
        }
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
            while (isActive) {
                publish()
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publish() {
        val p = player
        val entry = currentEntry
        val track = entry?.track
        PlayerBus.publish(
            PlayerState(
                playing = p?.isPlaying == true,
                buffering = p?.playbackState == Player.STATE_BUFFERING,
                positionMs = p?.currentPosition ?: 0L,
                durationMs = p?.duration ?: 0L,
                index = order.getOrNull(pos) ?: -1,
                count = entries.size,
                title = track?.title ?: "",
                artist = track?.artist ?: "",
                album = track?.album ?: "",
                thumbUrl = track?.thumbUrl ?: "",
                shuffle = shuffleOn,
                repeat = repeatMode,
                infinite = infinite,
                volume = volume,
                status = statusText,
                error = errorText,
                lyrics = lyrics,
                callActive = callPaused,
            )
        )
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
            val newPos = windowStart + idx
            val entry = entries.getOrNull(order.getOrNull(newPos) ?: -1) ?: return

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && repeatMode == "one") {
                // 한 곡 반복 : 방금 끝난 곡으로 되돌아가 다시 재생
                p.seekToPreviousMediaItem()
                p.play()
                return
            }

            val changed = newPos != pos
            pos = newPos
            currentEntry = entry
            if (changed) {
                statusText = "재생 중"
                errorText = ""
                onTrackChanged(entry)
            }
            fillWindow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                statusText = "재생 중"
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
            if (playbackState == Player.STATE_ENDED) {
                val p = player
                if (repeatMode == "one") {
                    p?.seekTo(0)
                    p?.play()
                    return
                }
                if (infinite) {
                    appendPermutation()
                    scope.launch {
                        fillOnce()
                        player?.prepare()
                        if (userWantsPlay) player?.play()
                    }
                    return
                }
                userWantsPlay = false
                statusText = "재생 종료"
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            errorText = "재생 오류 (${error.errorCodeName})"
            statusText = "오류"
            publish()
            // 스트림 주소 만료/변경 → 다시 해석해서 재시도
            val entry = currentEntry ?: return
            scope.launch {
                entry.job = null
                entry.audio = null
                val audio = resolveEntry(entry) ?: return@launch
                val p = player ?: return@launch
                val idx = p.currentMediaItemIndex
                if (idx >= 0) {
                    p.replaceMediaItem(idx, mediaItem(entry, audio))
                    errorText = ""
                    statusText = "재생 중"
                    publish()
                }
            }
        }
    }
}
