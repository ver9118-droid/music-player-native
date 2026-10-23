package org.music.infiniteplayer.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository

/**
 * 노래에 맞는 영상 화면 (차량 블루투스 연결 시).
 *
 * - 메인 오디오 플레이어와 별개로 영상만 재생한다 (음소거 → 차량 스피커와 겹치지 않음)
 * - 곡에 영상 ID 가 있으면 그 뮤직비디오, 없으면 제목/가수로 검색한 영상
 * - 영상을 못 찾으면 앨범아트로 대체한다
 */
@Composable
fun TrackVideo(
    videoId: String,
    title: String,
    artist: String,
    thumbUrl: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    var state by remember { mutableStateOf(VideoState.LOADING) }
    // 음악 재생 상태의 최신값 (백그라운드 복귀 시 그대로 이어서 재생하기 위해)
    val playingNow by rememberUpdatedState(playing)

    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { exo.release() } }
    }

    // 영상이 나오는 동안은 화면이 꺼지지 않게 한다 (차량 거치 상태에서 유용)
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 화면이 백그라운드로 가면 영상도 멈춘다 (데이터/배터리 절약)
    DisposableEffect(lifecycleOwner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> runCatching { exo.pause() }
                Lifecycle.Event.ON_START -> if (playingNow && exo.mediaItemCount > 0) {
                    runCatching { exo.play() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 음악 재생/일시정지에 맞춰 영상도 같이 멈추고 다시 튼다
    LaunchedEffect(playing) {
        runCatching {
            if (playing) exo.play() else exo.pause()
        }
    }

    LaunchedEffect(videoId, title, artist) {
        state = VideoState.LOADING
        runCatching {
            exo.stop()
            exo.clearMediaItems()
        }
        try {
            val repo = YoutubeRepository()
            val track = Track(
                id = "video_" + videoId.ifBlank { title },
                title = title,
                artist = artist,
                videoId = videoId,
                thumbUrl = thumbUrl,
            )
            val resolved = repo.resolveVideoStream(track)
            if (resolved == null || resolved.url.isBlank()) {
                state = VideoState.FAILED
                return@LaunchedEffect
            }
            exo.setMediaItem(MediaItem.fromUri(resolved.url))
            exo.prepare()
            // 음악이 재생 중일 때만 영상을 튼다 (일시정지 상태면 멈춘 채로 대기)
            if (playingNow) exo.play() else exo.pause()
            state = VideoState.READY
        } catch (e: Exception) {
            state = VideoState.FAILED
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (state == VideoState.FAILED) {
            // 영상을 못 찾았을 때는 앨범아트로 대체
            Artwork(url = thumbUrl, size = 268.dp, corner = 20.dp, maxPx = 720)
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(AndroidColor.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (state == VideoState.LOADING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                            color = Accent,
                        )
                        Text(
                            "노래에 맞는 영상을 준비하고 있어요",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private enum class VideoState { LOADING, READY, FAILED }
