package org.music.infiniteplayer.playback

import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.music.infiniteplayer.core.Images
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.core.RangeDataSource
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository
import kotlin.math.abs

/**
 * 차량(외부 디스플레이) 영상 출력.
 *
 * 블루투스(AVRCP) 는 **메타데이터만** 실어 보낼 수 있어서 영상 자체가 전달되지 않는다.
 * 그래서 차량 화면에 영상을 보여주려면 실제 디스플레이가 필요하다:
 *   - HDMI/DP 로 연결된 외부 디스플레이 (유선 미러링, 차량 모니터)
 *   - 미러링을 지원하는 헤드유닛 (앱이 열거할 수 있는 디스플레이로 노출될 때만)
 *
 * 이 클래스는 DisplayManager 가 알려주는 "Presentation 분류" 디스플레이를 감시하고,
 * 그 위에 영상 + 곡 정보를 그린다.
 *
 * 주의:
 *  - 소리는 **메인 플레이어**가 낸다. 영상 플레이어는 음소거(volume=0)이고
 *    오디오 포커스를 요구하지 않는다 (포커스를 빼앗으면 음악이 멈춘다).
 *  - 폰 화면이 켜져 있는 동안에는 앱이 폰에서 영상을 보여주고 있으므로,
 *    두 디코더를 동시에 돌리지 않는다 (배터리). → 폰 화면이 **꺼졌을 때**만 차량으로 출력.
 *  - Presentation 은 메인 스레드(Looper)에서만 만들 수 있고, 디스플레이가 빠지면
 *    스스로 cancel() 된다. show() 는 InvalidDisplayException 을 던질 수 있다.
 *  - TYPE_PRESENTATION 은 시스템 권한이 필요 없다 (PhoneWindowManager 가 ADD_OKAY 를 준다).
 *    단, private 디스플레이는 이 앱만 볼 수 있어서 미러링 헤드유닛에 안 보일 수 있다.
 */
@OptIn(UnstableApi::class)
class CarDisplay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val youtube: YoutubeRepository,
    private val mainPlayer: () -> ExoPlayer?,
) {

    companion object {
        /** 위치가 이 이상 어긋날 때만 다시 맞춘다 (자주 맞추면 영상이 끊겨 보인다) */
        private const val DRIFT_MS = 1_500L

        /** 재생 위치 확인 간격 */
        private const val SYNC_MS = 2_000L

        /** 영상 주소 해석 대기 시간 */
        private const val RESOLVE_TIMEOUT_MS = 25_000L

        private const val BG = 0xFF0B0E14.toInt()
    }

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var presentation: CarPresentation? = null
    private var videoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var artworkView: ImageView? = null
    private var labelView: TextView? = null

    private var track: Track? = null
    private var streamUrl = ""
    private var resolving = false
    private var lastSyncAt = 0L
    private var screenOn = true
    private var displayName = ""
    private var receiverRegistered = false

    /** 곡 id → 영상 주소 (한 번 찾은 영상은 재사용) */
    private val videoCache = HashMap<String, String>()

    /** 영상을 못 찾은 곡 (같은 곡을 계속 다시 해석하지 않는다) */
    private val failedFor = HashSet<String>()

    // ── 생애주기 ───────────────────────────────────────────────
    fun start() {
        if (displayManager == null) return
        screenOn = powerManager?.isInteractive == true

        displayManager.registerDisplayListener(displayListener, handler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            ContextCompat.registerReceiver(
                context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (_: Exception) {
        }

        attachBestDisplay()
    }

    fun stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        displayName = ""
        detach()
    }

    /** 진단 화면에 표시할 연결 디스플레이 이름 */
    fun info(): String {
        if (displayName.isBlank()) return ""
        return displayName + (if (Prefs.carVideo) "" else " (영상 끔)")
    }

    // ── 디스플레이 ─────────────────────────────────────────────
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            attachBestDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
            val p = presentation
            if (p != null && p.display.displayId == displayId) {
                detach()
                displayName = ""
            }
            attachBestDisplay()
        }

        override fun onDisplayChanged(displayId: Int) {
            // 크기/해상도 변화 → PlayerView 가 스스로 다시 맞추므로 아무것도 하지 않는다
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val on = intent?.action == Intent.ACTION_SCREEN_ON
            if (screenOn == on) return
            screenOn = on
            if (on) {
                // 폰 화면이 켜지면 앱이 폰에서 영상을 보여준다 → 차량 디코더는 멈춘다
                videoPlayer?.playWhenReady = false
            } else {
                sync()
            }
        }
    }

    private fun attachBestDisplay() {
        val dm = displayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val best = displays.firstOrNull {
            it != null && (it.flags and Display.FLAG_PRESENTATION) != 0
        } ?: displays.firstOrNull()

        if (best == null) {
            if (presentation != null) detach()
            displayName = ""
            return
        }

        val existing = presentation
        if (existing != null && existing.display.displayId == best.displayId) return

        detach()
        displayName = best.name ?: ""

        val created = try {
            CarPresentation(context, best)
        } catch (_: Exception) {
            null
        } ?: return

        presentation = created
        buildContent(created)
        try {
            created.show()
        } catch (_: Exception) {
            // InvalidDisplayException : 디스플레이가 이미 사라짐
            presentation = null
            displayName = ""
            return
        }
        // 화면이 새로 만들어졌으니 지금 곡을 다시 그린다
        track?.let { showArtwork(it) }
        track?.let { openCachedStream(it) }
    }

    private inner class CarPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
        override fun onDisplayRemoved() {
            super.onDisplayRemoved()
            if (presentation === this) detach()
        }
    }

    private fun detach() {
        val p = presentation
        presentation = null
        playerView = null
        artworkView = null
        labelView = null
        streamUrl = ""
        try {
            playerView?.setPlayer(null)
        } catch (_: Exception) {
        }
        try {
            p?.cancel()
        } catch (_: Exception) {
        }
        try {
            videoPlayer?.release()
        } catch (_: Exception) {
        }
        videoPlayer = null
    }

    /** Presentation 위에 내용을 그린다 (프로젝트에 drawable 리소스가 없어 프로그램으로 구성) */
    private fun buildContent(p: CarPresentation) {
        val root = FrameLayout(p.context)
        root.setBackgroundColor(BG)

        val art = ImageView(p.context)
        art.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(art, FrameLayout.LayoutParams(-1, -1))

        val pv = PlayerView(p.context)
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        // 영상이 없을 때 앨범아트가 비치도록 투명 처리
        pv.setShutterBackgroundColor(Color.TRANSPARENT)
        pv.setBackgroundColor(Color.TRANSPARENT)
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        val label = TextView(p.context)
        label.setTextColor(Color.WHITE)
        label.textSize = 15f
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setPadding(24, 24, 24, 24)
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(label, lp)

        artworkView = art
        playerView = pv
        labelView = label

        val player = ensurePlayer()
        if (player != null) pv.setPlayer(player)
    }

    // ── 영상 플레이어 ──────────────────────────────────────────
    private fun ensurePlayer(): ExoPlayer? {
        videoPlayer?.let { return it }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 30_000, 1_000, 1_500)
            .build()
        val created = try {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(RangeDataSource.Factory()))
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                // 오디오 포커스를 요구하지 않는다 → 메인 플레이어 소리에 영향 없음
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .build()
        } catch (_: Exception) {
            return null
        }
        created.volume = 0f
        created.repeatMode = Player.REPEAT_MODE_OFF
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 영상을 못 읽은 곡 → 다시 해석하지 않고 앨범아트로 돌아간다
                val t = track
                if (t != null) failedFor.add(t.id)
                streamUrl = ""
                showArtwork(t)
            }
        })
        videoPlayer = created
        return created
    }

    // ── 곡 / 상태 이벤트 ───────────────────────────────────────
    fun onTrackChanged(t: Track?) {
        track = t
        if (t == null) return
        showArtwork(t)
        if (!active) return
        if (t.isCompilation) {
            // 모음 영상은 영상으로 쓰기 부적합 → 앨범아트만
            streamUrl = ""
            return
        }
        openCachedStream(t)
    }

    fun onPlayState(playing: Boolean) {
        val p = videoPlayer ?: return
        if (!active) {
            p.playWhenReady = false
            return
        }
        p.playWhenReady = playing
    }

    fun onSeek(ms: Long) {
        val p = videoPlayer ?: return
        if (!active) return
        try {
            p.seekTo(ms)
            lastSyncAt = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
        }
    }

    /** 메인 플레이어와 위치/재생 상태를 맞춘다 (ticker 에서 호출) */
    fun sync() {
        val p = videoPlayer ?: return
        val main = mainPlayer() ?: return
        if (!active) {
            p.playWhenReady = false
            return
        }
        p.playWhenReady = main.isPlaying
        if (p.playbackState == Player.STATE_BUFFERING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSyncAt < SYNC_MS) return
        if (abs(p.currentPosition - main.currentPosition) > DRIFT_MS) {
            try {
                p.seekTo(main.currentPosition)
                lastSyncAt = now
            } catch (_: Exception) {
            }
        }
    }

    /** 폰 화면이 꺼진 상태에서 외부 디스플레이로 출력할 준비가 됐는지 */
    private val active: Boolean
        get() = Prefs.carVideo && presentation != null && !screenOn

    private fun openCachedStream(t: Track) {
        val cached = videoCache[t.id]
        if (cached != null) {
            openStream(cached)
            return
        }
        if (failedFor.contains(t.id) || resolving) return
        resolving = true
        scope.launch(Dispatchers.IO) {
            val found = try {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { youtube.resolveVideoStream(t) }
            } catch (_: Exception) {
                null
            }
            resolving = false
            if (found == null) {
                failedFor.add(t.id)
                return@launch
            }
            if (videoCache.size > 60) videoCache.clear()
            videoCache[t.id] = found.url
            if (track?.id == t.id && active) {
                // 메인 스레드에서 뷰/플레이어를 건드려야 한다
                scope.launch { openStream(found.url) }
            }
        }
    }

    private fun openStream(url: String) {
        if (url.isBlank()) return
        val p = ensurePlayer() ?: return
        if (streamUrl == url) return
        streamUrl = url
        artworkView?.visibility = View.GONE
        try {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            val main = mainPlayer()
            if (main != null) {
                p.seekTo(main.currentPosition)
                p.playWhenReady = main.isPlaying
            }
        } catch (_: Exception) {
            streamUrl = ""
            showArtwork(track)
            return
        }
        lastSyncAt = SystemClock.elapsedRealtime()
    }

    /** 영상을 못 찾은 곡은 앨범아트 + 곡 정보를 보여준다 */
    private fun showArtwork(t: Track?) {
        if (t == null) return
        val art = artworkView
        val label = labelView
        if (art == null && label == null) return
        val text = t.display
        if (label != null) label.text = text
        if (art == null) return

        val cached = Images.cachedBitmap(t.thumbUrl, 256)
        if (cached != null) {
            if (streamUrl.isBlank()) art.visibility = View.VISIBLE
            art.setImageBitmap(cached)
            return
        }
        if (t.thumbUrl.isBlank()) return
        // 캐시에 없는 이미지 — 채운 뒤 그린다 (메인 스레드에서 블록하면 화면이 얼어붙는다)
        scope.launch(Dispatchers.IO) {
            val bmp = try {
                Images.load(t.thumbUrl, 256)
            } catch (_: Exception) {
                null
            }
            if (bmp == null || track?.id != t.id) return@launch
            scope.launch {
                if (artworkView == null || track?.id != t.id) return@launch
                if (streamUrl.isBlank()) artworkView?.visibility = View.VISIBLE
                artworkView?.setImageBitmap(bmp)
            }
        }
    }
}
