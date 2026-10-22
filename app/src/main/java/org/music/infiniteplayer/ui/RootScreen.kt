package org.music.infiniteplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.music.infiniteplayer.core.Prefs
import org.music.infiniteplayer.data.ChartRepository
import org.music.infiniteplayer.data.Sources
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.data.YoutubeRepository
import org.music.infiniteplayer.data.decodeTracks
import org.music.infiniteplayer.data.encodeTracks
import org.music.infiniteplayer.playback.PlayerBus
import org.music.infiniteplayer.playback.PlayerState

private const val PAGE_HOME = 0
private const val PAGE_PLAYER = 1
private const val PAGE_SETTINGS = 2
private const val PAGE_DIAG = 3

@Composable
fun RootScreen() {
    val state by PlayerBus.state.collectAsStateWithLifecycle()
    // 위치(tick)는 250ms 마다 갱신되므로 "지금 재생" 화면에서만 구독한다.
    // (홈 화면에서 구독하면 목록 전체가 매 프레임 다시 그려져 느려진다)
    val diag by PlayerBus.diagnostics.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableIntStateOf(PAGE_HOME) }
    var sourceKey by rememberSaveable {
        mutableStateOf(Prefs.lastSource.ifBlank { Sources.default.key })
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Prefs.quality) }
    var favorites by remember { mutableStateOf(loadFavorites()) }
    var crashLog by remember { mutableStateOf(org.music.infiniteplayer.core.CrashLog.read()) }
    // 지난 실행에서 멈춤이 감지됐는지 (감시자 기록)
    var freezeDetected by remember {
        mutableStateOf(org.music.infiniteplayer.core.CrashLog.readStack().contains("멈춤 감지"))
    }

    // ── 통합 검색 ────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }

    val scope = rememberCoroutineScope {
        // UI 코루틴 예외가 앱을 죽이지 않도록
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            org.music.infiniteplayer.core.CrashLog.save(error)
        }
    }
    val repo = remember { ChartRepository(YoutubeRepository()) }
    val currentSource by rememberUpdatedState(sourceKey)

    // 차트 로드 : 캐시를 먼저 보여줘 즉시 표시 → 그다음 최신본으로 교체
    LaunchedEffect(sourceKey) {
        try {
            Prefs.lastSource = sourceKey
            loading = true
            message = ""
            val cached = Prefs.cachedChart(sourceKey)
            tracks = if (cached.isNotEmpty()) decodeTracks(cached) else emptyList()
            try {
                val fresh = repo.load(Sources.byKey(sourceKey) ?: Sources.default)
                if (currentSource == sourceKey) {
                    tracks = fresh
                    Prefs.saveChart(sourceKey, encodeTracks(fresh))
                }
            } catch (e: Exception) {
                if (currentSource == sourceKey) {
                    message = e.message ?: "차트를 불러오지 못했습니다."
                }
            }
            if (currentSource == sourceKey) loading = false
        } catch (e: Exception) {
            org.music.infiniteplayer.core.CrashLog.save(e)
            loading = false
        }
    }

    BackHandler(enabled = page != PAGE_HOME) { page = PAGE_HOME }

    // 서비스에서 온 알림(다운로드/벨소리 결과)을 토스트로 표시
    val toastContext = LocalContext.current
    LaunchedEffect(state.toastId) {
        if (state.toastId > 0L && state.toast.isNotBlank()) {
            android.widget.Toast.makeText(
                toastContext, state.toast, android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 안전망 : 서비스가 죽었는데 UI 가 살아 있으면 다시 살린다
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            if (!PlayerBus.isAttached()) {
                try {
                    appContext.startService(
                        android.content.Intent(appContext, org.music.infiniteplayer.playback.PlaybackService::class.java)
                    )
                } catch (_: Exception) {
                    // 백그라운드에서는 제한될 수 있음 (무시)
                }
            }
        }
    }

    // 즐겨찾기 곡 재생 : 누른 곡부터 즐겨찾기 목록 전체를 재생
    val playFavorite: (Track) -> Unit = { track ->
        val list = favorites
        val idx = list.indexOfFirst { it.id == track.id }
        if (list.isEmpty()) {
            PlayerBus.post { it.setQueue(listOf(track), 0) }
        } else {
            PlayerBus.post { it.setQueue(list, if (idx < 0) 0 else idx) }
        }
        page = PAGE_PLAYER
    }

    // 상태바/네비게이션바 영역과 겹치지 않도록 inset 적용
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        when (page) {
            PAGE_PLAYER -> NowPlayingScreen(
                state = state,
                diag = diag,
                isFavorite = favorites.any { it.id == currentTrackId(state, tracks) },
                onToggleFavorite = {
                    val id = currentTrackId(state, tracks)
                    val track = tracks.firstOrNull { it.id == id }
                    if (track != null) {
                        Prefs.toggleFavoriteTrack(track.toJson())
                        favorites = loadFavorites()
                    }
                },
                onRetry = { PlayerBus.post { it.retryCurrent() } },
                onDownload = { PlayerBus.post { it.downloadToDevice() } },
                onRaiseVolume = { PlayerBus.post { it.raiseSystemVolume() } },
                onBack = { page = PAGE_HOME },
                onTogglePlay = { PlayerBus.post { it.toggle() } },
                onNext = { PlayerBus.post { it.next() } },
                onPrev = { PlayerBus.post { it.prev() } },
                onSeek = { ms -> PlayerBus.post { it.seekTo(ms) } },
                onVolume = { v -> PlayerBus.post { it.setVolume(v) } },
                onShuffle = { b -> PlayerBus.post { it.setShuffle(b) } },
                onRepeat = { m -> PlayerBus.post { it.setRepeat(m) } },
                onInfinite = { b -> PlayerBus.post { it.setInfinite(b) } },
            )

            PAGE_SETTINGS -> SettingsScreen(
                state = state,
                quality = quality,
                favorites = favorites,
                onBack = { page = PAGE_HOME },
                onOpenDiagnostics = { page = PAGE_DIAG },
                onVolume = { v -> PlayerBus.post { it.setVolume(v) } },
                onShuffle = { b -> PlayerBus.post { it.setShuffle(b) } },
                onRepeat = { m -> PlayerBus.post { it.setRepeat(m) } },
                onInfinite = { b -> PlayerBus.post { it.setInfinite(b) } },
                onQuality = { q ->
                    quality = q
                    Prefs.quality = q
                },
                onRemoveFavorite = { track ->
                    Prefs.toggleFavoriteTrack(track.toJson())
                    favorites = loadFavorites()
                },
                onPlayFavorite = playFavorite,
            )

            PAGE_DIAG -> DiagnosticsScreen(
                state = state,
                diag = diag,
                onBack = { page = PAGE_SETTINGS },
                onRetry = { PlayerBus.post { it.retryCurrent() } },
                onDownload = { PlayerBus.post { it.downloadAndPlay() } },
            )

            else -> {
                val shown = if (searchActive) searchResults else tracks
                HomeScreen(
                    tracks = shown,
                    state = state,
                    sourceKey = sourceKey,
                    loading = loading,
                    message = message,
                    searchQuery = searchQuery,
                    searchActive = searchActive,
                    searching = searching,
                    crashLog = crashLog,
                    freezeDetected = freezeDetected,
                    favorites = favorites,
                    onPlayFavorite = playFavorite,
                    onPlayAllFavorites = {
                        if (favorites.isNotEmpty()) {
                            PlayerBus.post { it.setQueue(favorites, 0) }
                            page = PAGE_PLAYER
                        }
                    },
                    onOpenDiagnostics = { page = PAGE_DIAG },
                    onQueryChange = { q ->
                        searchQuery = q
                        if (q.isBlank()) {
                            searchActive = false
                            searchResults = emptyList()
                        }
                    },
                    onSearchSubmit = {
                        val q = searchQuery.trim()
                        if (q.isEmpty()) return@HomeScreen
                        scope.launch {
                            searching = true
                            searchActive = true
                            message = ""
                            try {
                                val yt = repo.searchMusic(q, 40)
                                val chart = tracks.filter {
                                    it.title.contains(q, true) || it.artist.contains(q, true)
                                }
                                val merged = chart + yt.filter { y ->
                                    chart.none { it.title.equals(y.title, true) }
                                }
                                searchResults = merged
                            } catch (e: Exception) {
                                message = "검색에 실패했습니다: ${e.message ?: ""}"
                            }
                            searching = false
                        }
                    },
                    onClearSearch = {
                        searchQuery = ""
                        searchActive = false
                        searchResults = emptyList()
                    },
                    onSelectSource = { key ->
                        searchActive = false
                        searchQuery = ""
                        searchResults = emptyList()
                        sourceKey = key
                    },
                    onTrackClick = { index ->
                        val list = shown
                        PlayerBus.post { it.setQueue(list, index) }
                    },
                    onOpenPlayer = { page = PAGE_PLAYER },
                    onOpenSettings = { page = PAGE_SETTINGS },
                    onRetry = { PlayerBus.post { it.retryCurrent() } },
                )
            }
        }
    }
}

/** 현재 재생 중인 곡의 id (목록에서 찾기) */
private fun currentTrackId(state: PlayerState, tracks: List<Track>): String {
    if (state.index in tracks.indices) return tracks[state.index].id
    return tracks.firstOrNull { it.title == state.title && it.artist == state.artist }?.id ?: ""
}

private fun loadFavorites(): List<Track> = Prefs.favoriteTracks().map { Track.fromJson(it) }
