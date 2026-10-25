package org.music.infiniteplayer.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.core.RangeDataSource
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository
import org.music.infiniteplayer.playback.PlayerBus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 곡 id + 화질 → 영상 주소 (한 번 찾은 영상은 화면을 나갔다 와도 재사용) */
private val videoCache = ConcurrentHashMap<String, String>()

/** 지금 미리 받는 중인 곡 키 (중복 요청 방지) */
private val prefetching = ConcurrentHashMap.newKeySet<String>()

/**
 * 다음 곡 영상 주소만 미리 해석한다.
 *  - 영상 해석(유튜브 서명 해독)은 CPU 를 많이 쓰므로 **한 번에 하나만**, 그것도
 *    현재 영상이 어느 정도 재생된 뒤에 시작한다. (동시에 돌리면 영상이 끊긴다)
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val prefetchScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
)

private fun videoKey(track: Track, quality: String): String = "${track.id}@$quality"

/**
 * 영상 플레이어를 **화면 전환에도 이어서 쓰기 위한 보관소.**
 *
 * 화면이 가로↔세로로 바뀌면 재생 화면의 레이아웃(가로 전체 화면 / 세로 배치)이
 * 통째로 다시 만들어진다. 그때마다 플레이어를 버리고 처음부터 다시 준비하면
 * 유튜브 스트림을 다시 받는 1~2초짜리 끊김·지연이 반드시 생긴다.
 *
 * 그래서 곡과 영상 주소가 그대로인 동안에는 **같은 플레이어 인스턴스를 재사용**하고,
 * 화면이 사라진 뒤에는 바로 해제하지 않고 잠깐(1.5초) 들고 있다가
 * 전환 중에 다시 나타나면 그대로 이어 붙인다. 아무도 다시 쓰지 않으면 그때 해제한다.
 */
private object VideoPlayerCache {
    /** 화면이 없어진 뒤 이 시간 안에 다시 쓰이면 재사용한다 */
    private const val HOLD_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var streamKey: String? = null
    private var instance: ExoPlayer? = null

    /** 같은 영상이면 지금 보관 중인 플레이어를, 다른 영상이면 새로 만든다. */
    fun acquire(key: String, create: () -> ExoPlayer): ExoPlayer {
        cancelPending()
        val current = instance
        if (current != null) {
            if (streamKey == key) {
                // 회전/레이아웃 전환 → 같은 플레이어 그대로 이어 붙인다 (재준비 없음)
                return current
            }
            // 다른 곡 → 지금 보던 플레이어는 정리하고 새로 만든다
            releaseNow()
        }
        val created = create()
        instance = created
        streamKey = key
        return created
    }

    /** 화면이 없어졌을 때: 잠시 보관했다가 아무도 안 쓰면 해제한다. */
    fun releaseLater(player: ExoPlayer?) {
        // 지금 보관 중인 플레이어와 다른(이미 교체된) 플레이어라면 건드리지 않는다
        if (player == null || instance !== player) return
        cancelPending()
        val job = Runnable { releaseNow() }
        pending = job
        handler.postDelayed(job, HOLD_MS)
    }

    /** 영상 주소가 없어졌을 때: 더 이상 쓸 수 없으니 보관 중이면 해제 예약 */
    fun discard() {
        if (instance == null) return
        releaseLater(instance)
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun releaseNow() {
        cancelPending()
        instance?.release()
        instance = null
        streamKey = null
    }
}

/**
 * 영상 스트림 하나를 재생할 플레이어를 만든다.
 * (보관소를 거쳐 회전 후에도 같은 인스턴스를 재사용한다)
 */
private fun createVideoPlayer(
    context: Context,
    streamUrl: String,
    playing: Boolean,
): ExoPlayer {
    val factory = DefaultMediaSourceFactory(RangeDataSource.Factory())
    // 기본(2.5초)보다 조금 짧게 잡아 첫 화면이 빨리 뜨되,
    // 너무 짧으면 계속 끊기므로 1초 정도는 버퍼를 허용한다.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(6_000, 30_000, 1_000, 1_500)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(factory)
        .setLoadControl(loadControl)
        // 영상은 키프레임 단위로만 이동할 수 있어서, 먼 위치로 튀지 않게 가까운 지점으로 맞춘다
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            volume = 0f                  // 소리는 메인 플레이어가 낸다
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = playing
            prepare()
        }
}

private fun rememberVideo(key: String, url: String) {
    if (videoCache.size > 80) videoCache.clear()
    videoCache[key] = url
}

private fun prefetchVideo(track: Track, quality: String) {
    if (track.isCompilation) return
    val key = videoKey(track, quality)
    if (videoCache.containsKey(key)) return
    if (prefetching.size >= 1) return
    if (!prefetching.add(key)) return
    prefetchScope.launch {
        try {
            val found = withTimeoutOrNull(25_000L) {
                YoutubeRepository(quality).resolveVideoStream(track)
            }
            if (found != null) rememberVideo(key, found.url)
        } catch (_: Exception) {
            // 미리 받기 실패는 조용히 무시 (실제 재생할 때 다시 시도한다)
        } finally {
            prefetching.remove(key)
        }
    }
}

/**
 * 지금 재생 화면의 영상 영역.
 *
 *  - 곡의 뮤직비디오(유튜브) 스트림을 찾아서 **음소거** 로 재생한다.
 *    (소리는 메인 플레이어가 담당 → 두 소리가 겹치지 않는다)
 *  - 메인 플레이어의 재생/일시정지 상태에 맞춰 함께 멈추고 다시 튼다.
 *  - 노래 위치와 크게 어긋날 때만(1.5초 이상) 다시 맞춘다.
 *    너무 자주 맞추면 영상이 끊겨 보이므로 최소 2초 간격을 둔다.
 *  - 영상을 못 찾거나 403 등으로 실패하면 앨범아트(fallback)를 보여준다.
 *    (모음/플레이리스트 곡은 호출하는 쪽에서 아예 이미지만 그린다)
 */
@OptIn(UnstableApi::class)
@Composable
fun TrackVideo(
    track: Track,
    playing: Boolean,
    positionMs: Long,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val quality = remember(track.id) { Prefs.quality }
    // 미리 받아 둔 주소가 있으면 첫 프레임부터 바로 영상을 띄운다
    var url by remember(track.id) { mutableStateOf(videoCache[videoKey(track, quality)]) }
    var failed by remember(track.id) { mutableStateOf(false) }

    // 노래 위치는 계속 바뀌므로 최신 값을 콜백에서 읽는다
    val latestPosition by rememberUpdatedState(positionMs)

    // 영상 주소 찾기 (곡당 한 번)
    LaunchedEffect(track.id) {
        failed = false
        val key = videoKey(track, quality)
        if (url == null) {
            val found = try {
                withTimeoutOrNull(15_000L) {
                    YoutubeRepository(quality).resolveVideoStream(track)
                }
            } catch (_: Exception) {
                null
            }
            if (found == null) {
                failed = true
            } else {
                rememberVideo(key, found.url)
                url = found.url
            }
        }
    }

    // 영상 보는 동안 화면이 꺼지지 않게
    DisposableEffect(track.id) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 지금 영상이 자리를 잡은 뒤에 다음 곡 영상을 미리 받아 둔다
    // (동시에 하면 현재 영상이 끊기므로 4초 뒤에 시작)
    LaunchedEffect(track.id, url) {
        if (url == null) return@LaunchedEffect
        delay(4_000L)
        val next = PlayerBus.peekNextTrack()
        if (next != null && next.id != track.id) prefetchVideo(next, quality)
    }

    val player: ExoPlayer? = remember(url) {
        val streamUrl = url ?: return@remember null
        // 회전/화면 전환으로 레이아웃이 다시 만들어져도 같은 플레이어를 이어 쓴다
        // (다시 준비하면 1~2초짜리 끊김이 생긴다)
        VideoPlayerCache.acquire(streamUrl) {
            createVideoPlayer(context, streamUrl, playing)
        }
    }

    // 영상 주소가 없어졌으면(실패/재해석 중) 보관 중인 플레이어를 정리한다
    LaunchedEffect(url) {
        if (url == null) VideoPlayerCache.discard()
    }

    DisposableEffect(player) {
        val p = player
        if (p == null) {
            onDispose { }
        } else {
            var firstReady = false
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 영상 재생 실패 → 앨범아트로 대체 (재생 자체는 계속된다)
                    failed = true
                    url = null
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // 버퍼링하는 동안 노래가 흘러갔을 수 있으니, 준비되면 현재 위치로 맞춘다
                    if (state == Player.STATE_READY && !firstReady) {
                        firstReady = true
                        val target = latestPosition
                        if (target > 1_500L) runCatching { p.seekTo(target) }
                    }
                }
            }
            p.addListener(listener)
            // 화면을 열었을 때 이미 곡이 진행 중이면 그 위치부터 영상을 보여준다
            // (회전으로 다시 붙일 때는 위치가 거의 같으니 불필요한 이동을 하지 않는다)
            if (positionMs > 1_500L && abs(p.currentPosition - positionMs) > 1_500L) {
                runCatching { p.seekTo(positionMs) }
            }
            onDispose {
                p.removeListener(listener)
                // 회전 직후 다시 붙일 수 있으니 바로 해제하지 않고 잠시 보관한다
                VideoPlayerCache.releaseLater(p)
            }
        }
    }

    LaunchedEffect(playing, player) {
        player?.playWhenReady = playing
    }

    // 노래와 영상이 크게 어긋날 때만 다시 맞춘다 (자주 맞추면 끊긴다)
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                val target = latestPosition
                if (target > 1_500L && abs(p.currentPosition - target) > 1_500L) {
                    runCatching { p.seekTo(target) }
                    // 다시 맞춘 직후에는 버퍼가 다시 차는 동안 기다린다
                    delay(2_000)
                }
            }
        }
    }

    if (url == null || player == null) {
        // 영상을 찾는 동안에도 앨범아트를 보여준다 (검은 화면 방지)
        Box(modifier = modifier) {
            fallback()
            if (!failed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp).size(18.dp),
                        color = Accent,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        return
    }

    // 가로/세로 어디서나 같은 방식으로 맞춘다 (영상 잘림 없음 → 전체가 다 보인다)
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                player?.let { this.player = it }
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}
