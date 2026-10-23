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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.withTimeoutOrNull
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.core.RangeDataSource
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository
import kotlin.math.abs

/** 곡 id → 영상 주소 (한 번 찾은 영상은 화면을 나갔다 와도 재사용) */
private val videoCache = HashMap<String, String>()

/**
 * 지금 재생 화면의 영상 영역.
 *
 *  - 곡의 뮤직비디오(유튜브) 스트림을 찾아서 **음소거** 로 재생한다.
 *    (소리는 메인 플레이어가 담당 → 두 소리가 겹치지 않는다)
 *  - 메인 플레이어의 재생/일시정지 상태에 맞춰 함께 멈추고 다시 튼다.
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

    var url by remember(track.id) { mutableStateOf<String?>(null) }
    var failed by remember(track.id) { mutableStateOf(false) }

    // 영상 주소 찾기 (곡당 한 번)
    LaunchedEffect(track.id) {
        failed = false
        url = null
        val quality = Prefs.quality
        val cacheKey = "${track.id}@$quality"
        val cached = videoCache[cacheKey]
        if (cached != null) {
            url = cached
            return@LaunchedEffect
        }
        val found = try {
            withTimeoutOrNull(20_000L) {
                YoutubeRepository(quality).resolveVideoStream(track)
            }
        } catch (_: Exception) {
            null
        }
        if (found == null) {
            failed = true
        } else {
            videoCache[cacheKey] = found.url
            url = found.url
        }
    }

    // 영상 보는 동안 화면이 꺼지지 않게
    DisposableEffect(track.id) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val player: ExoPlayer? = remember(url) {
        val streamUrl = url ?: return@remember null
        val factory = DefaultMediaSourceFactory(RangeDataSource.Factory())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(factory)
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
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 영상 재생 실패 → 앨범아트로 대체 (재생 자체는 계속된다)
                    failed = true
                    url = null
                }
            }
            p.addListener(listener)
            // 화면을 열었을 때 이미 곡이 진행 중이면 그 위치부터 영상을 보여준다
            if (positionMs > 0L) p.seekTo(positionMs)
            onDispose {
                p.removeListener(listener)
                p.release()
            }
        }
    }

    LaunchedEffect(playing, player) {
        player?.playWhenReady = playing
    }

    // 사용자가 진행바를 크게 움직이면 영상도 따라간다 (10초 이상 차이날 때만)
    LaunchedEffect(positionMs, player) {
        val p = player ?: return@LaunchedEffect
        if (!p.isPlaying) return@LaunchedEffect
        if (abs(p.currentPosition - positionMs) > 10_000L) p.seekTo(positionMs)
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
