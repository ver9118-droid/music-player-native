package org.music.infiniteplayer.ui

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

/** 화면(Composition)과 무관하게 백그라운드에서 다음 곡 영상을 미리 받는다 */
private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun videoKey(track: Track, quality: String): String = "${track.id}@$quality"

private fun rememberVideo(key: String, url: String) {
    if (videoCache.size > 80) videoCache.clear()
    videoCache[key] = url
}

/**
 * 다음 곡의 영상 주소를 미리 받아 둔다.
 * 곡을 넘겼을 때 검색/해석 때문에 몇 초씩 기다리던 문제를 없앤다.
 */
private fun prefetchVideo(track: Track, quality: String) {
    if (track.isCompilation) return
    val key = videoKey(track, quality)
    if (videoCache.containsKey(key)) return
    if (prefetching.size >= 2) return
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
 *  - 노래 위치와 어긋나면 계속 다시 맞춘다 (곡을 넘겨도 금방 따라온다)
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

    // 영상 주소 찾기 (곡당 한 번) + 다음 곡 미리 받기
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
        // 다음 곡은 미리 받아 둔다 → 넘기면 바로 재생
        val next = PlayerBus.peekNextTrack()
        if (next != null && next.id != track.id) prefetchVideo(next, quality)
    }

    // 영상 보는 동안 화면이 꺼지지 않게
    DisposableEffect(track.id) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val player: ExoPlayer? = remember(url) {
        val streamUrl = url ?: return@remember null
        val factory = DefaultMediaSourceFactory(RangeDataSource.Factory())
        // 영상은 빨리 뜨는 게 중요하므로 첫 재생 버퍼를 짧게 잡는다
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 20_000, 400, 800)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(factory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                volume = 0f                  // 소리는 메인 플레이어가 낸다
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = playing
                prepare()
            }
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
                        if (target > 500L) runCatching { p.seekTo(target) }
                    }
                }
            }
            p.addListener(listener)
            // 화면을 열었을 때 이미 곡이 진행 중이면 그 위치부터 영상을 보여준다
            if (positionMs > 0L) runCatching { p.seekTo(positionMs) }
            onDispose {
                p.removeListener(listener)
                p.release()
            }
        }
    }

    LaunchedEffect(playing, player) {
        player?.playWhenReady = playing
    }

    // 노래와 영상 위치가 벌어지면 계속 맞춘다 (곡을 넘겨도 금방 따라온다)
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(400)
            if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                val target = latestPosition
                if (target > 0L && abs(p.currentPosition - target) > 700L) {
                    runCatching { p.seekTo(target) }
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
