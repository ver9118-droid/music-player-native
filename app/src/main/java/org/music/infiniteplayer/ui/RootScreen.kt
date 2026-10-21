package org.music.infiniteplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    var page by rememberSaveable { mutableIntStateOf(PAGE_HOME) }
    var sourceKey by rememberSaveable {
        mutableStateOf(Prefs.lastSource.ifBlank { Sources.default.key })
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Prefs.quality) }
    var favorites by remember { mutableStateOf(loadFavorites()) }

    val repo = remember { ChartRepository(YoutubeRepository()) }
    val currentSource by rememberUpdatedState(sourceKey)

    // 차트 로드 : 캐시를 먼저 보여줘 즉시 표시 → 그다음 최신본으로 교체
    LaunchedEffect(sourceKey) {
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
    }

    BackHandler(enabled = page != PAGE_HOME) { page = PAGE_HOME }

    when (page) {
        PAGE_PLAYER -> NowPlayingScreen(
            state = state,
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
            onDownload = { PlayerBus.post { it.downloadAndPlay() } },
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
            onPlayFavorite = { track ->
                PlayerBus.post { it.setQueue(listOf(track), 0) }
                page = PAGE_PLAYER
            },
        )

        PAGE_DIAG -> DiagnosticsScreen(
            state = state,
            onBack = { page = PAGE_SETTINGS },
            onRetry = { PlayerBus.post { it.retryCurrent() } },
            onDownload = { PlayerBus.post { it.downloadAndPlay() } },
        )

        else -> HomeScreen(
            tracks = tracks,
            state = state,
            sourceKey = sourceKey,
            loading = loading,
            message = message,
            onSelectSource = { key -> sourceKey = key },
            onTrackClick = { index ->
                val list = tracks
                PlayerBus.post { it.setQueue(list, index) }
            },
            onOpenPlayer = { page = PAGE_PLAYER },
            onOpenSettings = { page = PAGE_SETTINGS },
            onRetry = { PlayerBus.post { it.retryCurrent() } },
        )
    }
}

/** 현재 재생 중인 곡의 id (목록에서 찾기) */
private fun currentTrackId(state: PlayerState, tracks: List<Track>): String {
    if (state.index in tracks.indices) return tracks[state.index].id
    return tracks.firstOrNull { it.title == state.title && it.artist == state.artist }?.id ?: ""
}

private fun loadFavorites(): List<Track> = Prefs.favoriteTracks().map { Track.fromJson(it) }
