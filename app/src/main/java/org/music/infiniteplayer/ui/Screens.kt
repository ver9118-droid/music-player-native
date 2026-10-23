package org.music.infiniteplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.music.infiniteplayer.data.Source
import org.music.infiniteplayer.data.Sources
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.core.Storage
import org.music.infiniteplayer.playback.Diagnostics
import org.music.infiniteplayer.playback.PlaybackTick
import org.music.infiniteplayer.playback.PlayerState

// ─────────────────────────────────────────────────────────────
// 홈 화면
// ─────────────────────────────────────────────────────────────

private data class SourceItem(val group: String, val source: Source?)

@Composable
fun HomeScreen(
    tracks: List<Track>,
    state: PlayerState,
    sourceKey: String,
    mvEnabled: Boolean,
    loading: Boolean,
    searchQuery: String,
    searchActive: Boolean,
    searching: Boolean,
    favorites: List<Track>,
    onPlayFavorite: (Track) -> Unit,
    onPlayAllFavorites: () -> Unit,
    onOpenFavorites: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectSource: (String) -> Unit,
    onTrackClick: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "무한 음악 플레이어",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        searching -> "검색 중..."
                        searchActive -> "검색 결과 ${tracks.size}곡"
                        loading -> "차트 불러오는 중..."
                        else -> "${tracks.size}곡"
                    },
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, "설정", tint = TextSecondary)
            }
        }

        // 즐겨찾기 바로가기 (메인 화면 상단)
        FavoritesStrip(
            favorites = favorites,
            onPlayFavorite = onPlayFavorite,
            onPlayAll = onPlayAllFavorites,
            onOpenAll = onOpenFavorites,
        )

        // 검색창 (통합검색 : 노래관련 영상만)
        SearchField(
            query = searchQuery,
            searching = searching,
            onQueryChange = onQueryChange,
            onSubmit = onSearchSubmit,
        )

        // 소스 선택 (검색 중에는 숨김)
        if (!searchActive) {
            SourceChips(
                sourceKey = sourceKey,
                mvEnabled = mvEnabled,
                onSelect = onSelectSource,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip("검색: $searchQuery", Accent)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Surface2)
                        .clickable(onClick = onClearSearch)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    Text("차트 보기", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 재생 오류 안내 (진단 모드가 켜져 있을 때만 표시)
        if (state.diagnostics && state.error.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Danger.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.error,
                    color = Danger,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent)
                        .clickable { onRetry() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("다시 시도", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 차트 목록
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(tracks, key = { index, t -> "${t.id}#$index" }) { index, track ->
                TrackRow(
                    track = track,
                    rank = track.rank.takeIf { it > 0 } ?: (index + 1),
                    playing = state.playing && state.index == index,
                    active = state.index == index,
                    favorite = favorites.any { it.id == track.id },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onClick = { onTrackClick(index) },
                )
            }
            if (tracks.isEmpty() && !loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        Text("표시할 차트가 없습니다", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }

        // 미니 플레이어
        AnimatedVisibility(visible = state.title.isNotBlank()) {
            MiniPlayer(state = state, onOpen = onOpenPlayer)
        }
    }
}

/**
 * 메인 화면 상단 "즐겨찾기" 바로가기.
 *
 * 설정 화면까지 들어가지 않고도 즐겨찾기한 곡을 바로 재생할 수 있게 한다.
 * (가로 스크롤 카드 · 누르면 그 곡부터 즐겨찾기 목록 전체가 재생된다)
 */
@Composable
private fun FavoritesStrip(
    favorites: List<Track>,
    onPlayFavorite: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Favorite,
                null,
                tint = Accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "즐겨찾기",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (favorites.isEmpty()) "곡 옆 하트를 눌러 추가하세요" else "${favorites.size}곡",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Surface2)
                    .clickable(onClick = onOpenAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "전체 보기",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (favorites.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Accent.copy(alpha = 0.18f))
                        .clickable(onClick = onPlayAll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "전체 재생",
                        color = Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (favorites.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(favorites, key = { t -> t.id }) { track ->
                    Column(
                        modifier = Modifier
                            .width(70.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .clickable { onPlayFavorite(track) }
                            .padding(bottom = 2.dp),
                    ) {
                        Artwork(url = track.thumbUrl, size = 66.dp, corner = 11.dp)
                        Text(
                            text = track.title,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, start = 1.dp, end = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 즐겨찾기 전용 재생목록 화면.
 *
 * 홈 상단의 "전체 보기" 로 들어온다.
 *  - 곡을 누르면 그 곡부터 즐겨찾기 목록 전체가 재생된다
 *  - 하트를 다시 누르면 목록에서 빠진다
 */
@Composable
fun FavoritesScreen(
    favorites: List<Track>,
    state: PlayerState,
    onBack: () -> Unit,
    onPlayFrom: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onRemoveFavorite: (Track) -> Unit,
    onOpenHome: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "뒤로", tint = TextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "내 즐겨찾기",
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "곡 · 나만의 재생목록 · ${favorites.size}곡",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        if (favorites.isEmpty()) {
            // 비어 있을 때 안내
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(54.dp))
                Icon(
                    Icons.Rounded.FavoriteBorder,
                    null,
                    tint = Accent.copy(alpha = 0.55f),
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "아직 즐겨찾기한 곡이 없어요",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "좋아하는 곡을 한곳에 모아보세요",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(22.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface1)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        "홈에서 곡 추가",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "홈의 곡 목록에서 하트를 누르면\n이곳에 재생목록으로 모입니다.",
                        color = TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable(onClick = onOpenHome)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        "홈으로 가기",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            // 전체 재생 / 셔플 재생
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable(onClick = onPlayAll)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("전체 재생", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .clickable(onClick = onShufflePlay)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("셔플 재생", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                "곡을 누르면 그 곡부터 재생 · 하트를 누르면 목록에서 빠집니다",
                color = TextSecondary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 16.dp),
            ) {
                itemsIndexed(favorites, key = { index, t -> "${t.id}#$index" }) { index, track ->
                    TrackRow(
                        track = track,
                        rank = index + 1,
                        playing = state.playing && state.track?.id == track.id,
                        active = state.track?.id == track.id,
                        favorite = true,
                        onToggleFavorite = { onRemoveFavorite(track) },
                        onClick = { onPlayFrom(track) },
                    )
                }
            }
        }
    }
}

/** 통합 검색 입력창 */
@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            null,
            tint = TextSecondary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("곡 제목 · 가수명 검색", color = TextSecondary, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (searching) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Accent,
            )
        } else if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Surface2)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    "지우기",
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceChips(
    sourceKey: String,
    mvEnabled: Boolean,
    onSelect: (String) -> Unit,
) {
    // "멜론 차트" 그룹 제목만 표시하지 않는다. (소스 구성은 그대로)
    // 나머지 그룹(최신 뮤직비디오 / 한국대중음악)은 구분을 위해 남긴다.
    val items = remember(mvEnabled) {
        buildList {
            var lastGroup = ""
            Sources.list(mvEnabled).forEach { src ->
                if (src.group != lastGroup) {
                    if (src.group != Sources.GROUP_MELON) add(SourceItem(src.group, null))
                    lastGroup = src.group
                }
                add(SourceItem(src.group, src))
            }
        }
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items) { item ->
            val src = item.source
            if (src == null) {
                Text(
                    text = item.group,
                    color = TextSecondary.copy(alpha = 0.75f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                )
            } else {
                val selected = src.key == sourceKey
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Accent else Surface1)
                        .clickable { onSelect(src.key) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = src.label,
                        color = if (selected) Color.White else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url = state.thumbUrl, size = 46.dp, corner = 10.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "재생할 곡을 선택하세요" },
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.status.ifBlank { state.artist },
                color = if (state.error.isNotBlank()) Danger else TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(
            icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (state.playing) "일시정지" else "재생",
            size = 40.dp,
            iconSize = 20.dp,
            background = Accent,
            tint = Color.White,
            onClick = { org.music.infiniteplayer.playback.PlayerBus.post { it.toggle() } },
        )
        Spacer(Modifier.width(6.dp))
        RoundIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "다음 곡",
            size = 40.dp,
            iconSize = 20.dp,
            onClick = { org.music.infiniteplayer.playback.PlayerBus.post { it.next() } },
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 지금 재생 화면
// ─────────────────────────────────────────────────────────────

@Composable
fun NowPlayingScreen(
    state: PlayerState,
    diag: Diagnostics,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onRingtone: () -> Unit,
    onRaiseVolume: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: (String) -> Unit,
    onInfinite: (Boolean) -> Unit,
) {
    // 재생 위치는 이 화면에서만 구독 (홈 화면 재렌더링 방지)
    val tick by org.music.infiniteplayer.playback.PlayerBus.tick
        .collectAsStateWithLifecycle()
    var showRingtone by remember { mutableStateOf(false) }
    val track = state.track

    // 화면을 돌려도 잘리거나 겹치지 않도록 가로/세로 레이아웃을 나눈다.
    //  - 세로: 위에서 아래로 (내용이 길면 스크롤)
    //  - 가로: 왼쪽 영상/앨범아트, 오른쪽 정보·컨트롤
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val landscape = screenWidth > screenHeight

        if (landscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerTopBar(
                    isFavorite = isFavorite,
                    onBack = onBack,
                    onToggleFavorite = onToggleFavorite,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerMedia(
                        track = track,
                        thumbUrl = state.thumbUrl,
                        playing = state.playing,
                        positionMs = tick.positionMs,
                        artworkSize = minOf(screenHeight - 120.dp, screenWidth / 2 - 48.dp)
                            .coerceAtLeast(96.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 14.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerDetails(
                            state = state,
                            positionMs = tick.positionMs,
                            durationMs = tick.durationMs,
                            diag = diag,
                            onRetry = onRetry,
                            onDownload = onDownload,
                            onRingtone = { showRingtone = true },
                            onRaiseVolume = onRaiseVolume,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onPrev = onPrev,
                            onSeek = onSeek,
                            onVolume = onVolume,
                            onShuffle = onShuffle,
                            onRepeat = onRepeat,
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerTopBar(
                    isFavorite = isFavorite,
                    onBack = onBack,
                    onToggleFavorite = onToggleFavorite,
                )
                PlayerMedia(
                    track = track,
                    thumbUrl = state.thumbUrl,
                    playing = state.playing,
                    positionMs = tick.positionMs,
                    artworkSize = minOf(268.dp, screenWidth - 96.dp).coerceAtLeast(120.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(292.dp)
                        .padding(vertical = 12.dp),
                )
                PlayerDetails(
                    state = state,
                    positionMs = tick.positionMs,
                    durationMs = tick.durationMs,
                    diag = diag,
                    onRetry = onRetry,
                    onDownload = onDownload,
                    onRingtone = { showRingtone = true },
                    onRaiseVolume = onRaiseVolume,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrev = onPrev,
                    onSeek = onSeek,
                    onVolume = onVolume,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                )
            }
        }

        if (showRingtone) {
            RingtoneConfirmDialog(
                onDismiss = { showRingtone = false },
                onConfirm = {
                    showRingtone = false
                    onRingtone()
                },
            )
        }
    }
}

/** 지금 재생 상단 바 (접기 · 제목 · 즐겨찾기) */
@Composable
private fun PlayerTopBar(
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.KeyboardArrowDown, "접기", tint = TextPrimary)
        }
        Text(
            text = "지금 재생",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "즐겨찾기",
                tint = if (isFavorite) Accent else TextSecondary,
            )
        }
    }
}

/**
 * 앨범아트 / 뮤직비디오 영상.
 *
 *  - 모음(플레이리스트) 곡은 영상이 수십 분~수 시간이라 이미지만 보여준다
 *  - 가로/세로 어디서든 주어진 영역 안에 맞춘다 (넘쳐서 다른 UI 를 덮지 않게)
 */
@Composable
private fun PlayerMedia(
    track: Track?,
    thumbUrl: String,
    playing: Boolean,
    positionMs: Long,
    artworkSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (track == null || track.isCompilation) {
            Artwork(
                url = thumbUrl,
                size = artworkSize,
                corner = 24.dp,
                maxPx = 720,
            )
        } else {
            TrackVideo(
                track = track,
                playing = playing,
                positionMs = positionMs,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
                fallback = {
                    Artwork(
                        url = thumbUrl,
                        size = artworkSize,
                        corner = 24.dp,
                        maxPx = 720,
                    )
                },
            )
        }
    }
}

/** 곡 정보 · 진행바 · 다운로드/벨소리 · 재생 컨트롤 · 볼륨 · 상태 */
@Composable
private fun PlayerDetails(
    state: PlayerState,
    positionMs: Long,
    durationMs: Long,
    diag: Diagnostics,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onRingtone: () -> Unit,
    onRaiseVolume: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: (String) -> Unit,
) {
    // 곡 정보
    Text(
        text = state.title.ifBlank { "-" },
        color = TextPrimary,
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(3.dp))
    Text(
        text = listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { " " },
        color = TextSecondary,
        fontSize = 13.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))

    // 진행 바
    SeekBar(
        positionMs = positionMs,
        durationMs = durationMs,
        onSeek = onSeek,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(positionMs), color = TextSecondary, fontSize = 11.5.sp)
        Text(formatTime(durationMs), color = TextSecondary, fontSize = 11.5.sp)
    }

    // 다운로드 / 벨소리
    ActionChips(
        state = state,
        onDownload = onDownload,
        onRingtone = onRingtone,
    )

    Spacer(Modifier.height(10.dp))

    // 재생 컨트롤
    EvenRow {
        RoundIconButton(
            icon = Icons.Rounded.Shuffle,
            contentDescription = "셔플",
            size = 42.dp,
            iconSize = 19.dp,
            background = if (state.shuffle) Accent.copy(alpha = 0.22f) else Surface2,
            tint = if (state.shuffle) Accent else TextSecondary,
            onClick = { onShuffle(!state.shuffle) },
        )
        RoundIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "이전 곡",
            size = 54.dp,
            iconSize = 30.dp,
            onClick = onPrev,
        )
        RoundIconButton(
            icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (state.playing) "일시정지" else "재생",
            size = 68.dp,
            iconSize = 36.dp,
            background = Accent,
            tint = Color.White,
            onClick = onTogglePlay,
        )
        RoundIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "다음 곡",
            size = 54.dp,
            iconSize = 30.dp,
            onClick = onNext,
        )
        RoundIconButton(
            icon = if (state.repeat == "one") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            contentDescription = "반복",
            size = 42.dp,
            iconSize = 19.dp,
            background = if (state.repeat != "off") Accent.copy(alpha = 0.22f) else Surface2,
            tint = if (state.repeat != "off") Accent else TextSecondary,
            onClick = {
                onRepeat(if (state.repeat == "all") "one" else if (state.repeat == "one") "off" else "all")
            },
        )
    }

    Spacer(Modifier.height(10.dp))

    // 볼륨
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeDown, "볼륨", tint = TextSecondary, modifier = Modifier.size(18.dp))
        Slider(
            value = state.volume,
            onValueChange = onVolume,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface2,
            ),
        )
        Icon(Icons.Rounded.VolumeUp, "볼륨", tint = TextSecondary, modifier = Modifier.size(18.dp))
    }

    // 상태 표시
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            text = state.status.ifBlank { "대기" },
            color = if (state.error.isNotBlank()) Danger else AccentSoft,
        )
        if (state.infinite) StatusChip("무한 재생", Accent)
        if (state.callActive) StatusChip("통화 중", Danger)
        if (diag.systemVolume.startsWith("0 ")) StatusChip("시스템 볼륨 0", Danger)
    }

    // 시스템 볼륨이 0 이면 소리가 안 난다 (가장 흔한 원인)
    if (diag.systemVolume.startsWith("0 ")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AccentSoft.copy(alpha = 0.16f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "시스템 미디어 볼륨이 0 입니다 (소리가 나지 않는 원인)",
                color = AccentSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentSoft)
                    .clickable(onClick = onRaiseVolume)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("볼륨 올리기", color = Color(0xFF04231C), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // 오류 안내 + 복구 버튼
    if (state.error.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Danger.copy(alpha = 0.13f))
                .padding(12.dp),
        ) {
            Column {
                Text(state.error, color = Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Accent)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text("다시 시도", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .clickable(onClick = onDownload)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = if (state.downloadProgress >= 0)
                                "내려받는 중 ${state.downloadProgress}%" else "파일로 받기",
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 재생 진단 화면 */
@Composable
fun DiagnosticsScreen(
    state: PlayerState,
    diag: Diagnostics,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onSelfTest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val crash = remember { org.music.infiniteplayer.core.CrashLog.read() }
    val stackDump = remember { org.music.infiniteplayer.core.CrashLog.readStack() }
    val lastError = remember { org.music.infiniteplayer.core.Prefs.lastError }
    val lastErrorAt = remember { org.music.infiniteplayer.core.Prefs.lastErrorAt }
    val report = remember(state, diag) {
        buildString {
            appendLine("무한 음악 플레이어 진단 v${org.music.infiniteplayer.core.CrashLog.BuildInfo.VERSION}")
            appendLine("곡: ${state.title} / ${state.artist}  (${state.index + 1}/${state.count})")
            appendLine("반복 모드: ${state.repeat} (무한 재생=${state.infinite}, 셔플=${state.shuffle})")
            appendLine("상태: ${state.status} (player=${diag.playerState}, playing=${state.playing}, buffering=${state.buffering})")
            appendLine("재생 순서: ${diag.queueInfo}")
            appendLine("스트림: ${diag.streamInfo}")
            appendLine("주소 응답: ${diag.streamProbe}")
            appendLine("시스템 볼륨: ${diag.systemVolume}")
            appendLine("출력 경로: ${diag.audioRoute}")
            appendLine("앱 볼륨: ${state.volume}")
            appendLine("오류: ${state.error.ifBlank { "없음" }}")
            appendLine("최근 오류: ${lastError.ifBlank { "없음" }}${if (lastErrorAt.isBlank()) "" else " ($lastErrorAt)"}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "뒤로", tint = TextPrimary)
            }
            Text("재생 진단", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (!state.diagnostics) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface1)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "진단 모드가 꺼져 있습니다.\n" +
                                "설정 → 재생 진단에서 켜면 오류 메시지와 기록이 표시됩니다.",
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "연결 테스트",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        StorageButton("실행") { onSelfTest() }
                    }
                    if (diag.selfTest.isNotBlank()) {
                        Text(
                            diag.selfTest,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    DiagRow("버전", org.music.infiniteplayer.core.CrashLog.BuildInfo.VERSION)
                    DiagRow("재생 상태", "${state.status}  (${diag.playerState})")
                    DiagRow("재생 순서", diag.queueInfo.ifBlank { "-" })
                    DiagRow("재생 중", if (state.playing) "예" else "아니오")
                    DiagRow("버퍼링", if (state.buffering) "예" else "아니오")
                    DiagRow("스트림", diag.streamInfo.ifBlank { "-" })
                    DiagRow("주소 응답", diag.streamProbe.ifBlank { "-" })
                    DiagRow("시스템 볼륨", diag.systemVolume.ifBlank { "-" })
                    DiagRow("출력 경로", diag.audioRoute.ifBlank { "-" })
                    DiagRow("서비스 상태", diag.serviceState.ifBlank { "-" })
                    DiagRow("알림", diag.notificationState.ifBlank { "-" })
                    DiagRow("UI 응답", diag.uiLatency.ifBlank { "-" })
                    DiagRow("앱 볼륨", "%.0f%%".format(state.volume * 100))
                    DiagRow("오류", state.error.ifBlank { "없음" })
                    DiagRow(
                        "최근 오류",
                        if (lastError.isBlank()) "없음" else "$lastError${if (lastErrorAt.isBlank()) "" else "  ($lastErrorAt)"}",
                    )
                    DiagRow("최근 크래시", if (crash.isBlank()) "없음" else "있음 (아래에서 복사)")
                    DiagRow("스택 덤프", if (stackDump.isBlank()) "없음" else "있음 (아래에서 복사)")
                    if (state.downloadProgress >= 0) {
                        DiagRow("다운로드", "${state.downloadProgress}%")
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("다시 시도", Accent, Color.White, onRetry)
                        ActionButton("파일로 받기", Surface2, TextPrimary, onDownload)
                    }
                    Spacer(Modifier.height(10.dp))
                    ActionButton("진단 내용 복사", Surface2, TextPrimary) {
                        clipboard.setText(AnnotatedString(report))
                    }
                    if (crash.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ActionButton("크래시 로그 복사", Surface2, TextPrimary) {
                            clipboard.setText(AnnotatedString(crash))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "최근 크래시 로그입니다. 복사해서 개발자에게 보내주세요.",
                            color = Danger,
                            fontSize = 11.5.sp,
                        )
                    }
                    if (stackDump.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ActionButton("메인 스레드 스택 복사", Surface2, TextPrimary) {
                            clipboard.setText(AnnotatedString(stackDump))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "앱이 멈췄을 때 메인 스레드가 뭘 하고 있었는지 기록된 것입니다.",
                            color = TextSecondary,
                            fontSize = 11.5.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "소리가 안 나면 '시스템 볼륨'이 0 인지, '주소 응답'이 403 인지 먼저 확인하세요.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            item { VSpace(30.dp) }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 다운로드 / 벨소리 칩 */
@Composable
private fun ActionChips(
    state: PlayerState,
    onDownload: () -> Unit,
    onRingtone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallChip(
            text = if (state.downloadProgress >= 0) "내려받는 중 ${state.downloadProgress}%" else "다운로드",
            selected = false,
            icon = Icons.Rounded.Download,
        ) { onDownload() }
        SmallChip(
            text = "벨소리",
            selected = false,
            icon = Icons.Rounded.Notifications,
        ) { onRingtone() }
    }
}

/**
 * 벨소리 설정 확인 창.
 *
 * 초/길이를 고르게 하지 않는다. 곡마다 다른 하이라이트 구간은
 * [org.music.infiniteplayer.core.HighlightFinder] 가 자동으로 찾는다.
 */
@Composable
private fun RingtoneConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("벨소리로 설정", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "이 곡의 하이라이트(후렴) 구간을 자동으로 찾아 30초로 잘라 벨소리로 설정합니다.",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "저장 위치: 음악/Ringtones · 기기 설정에서 벨소리로 선택하세요",
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("자동으로 설정", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = TextSecondary)
            }
        },
        containerColor = Surface1,
    )
}

@Composable
private fun SmallChip(
    text: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.22f) else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = if (selected) Accent else TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            color = if (selected) Accent else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L).toFloat()
    val value = if (dragging) dragValue else positionMs.toFloat().coerceIn(0f, duration)
    Slider(
        value = value,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(dragValue.toLong())
        },
        valueRange = 0f..duration,
        colors = SliderDefaults.colors(
            thumbColor = Accent,
            activeTrackColor = Accent,
            inactiveTrackColor = Surface2,
        ),
    )
}

// ─────────────────────────────────────────────────────────────
// 설정 화면
// ─────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    state: PlayerState,
    quality: String,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onVolume: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onRepeat: (String) -> Unit,
    onInfinite: (Boolean) -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onQuality: (String) -> Unit,
    mvEnabled: Boolean,
    onMelonMv: (Boolean) -> Unit,
    favorites: List<Track>,
    onOpenFavorites: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "뒤로", tint = TextPrimary)
            }
            Text("설정", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionTitle("재생")
                SettingRow(
                    title = "무한 재생",
                    subtitle = "목록 끝(마지막 곡)에서 처음 곡으로 이어서 재생합니다",
                ) {
                    Switch(
                        checked = state.infinite,
                        onCheckedChange = onInfinite,
                        colors = switchColors(),
                    )
                }
                SettingRow(
                    title = "셔플",
                    subtitle = "재생 순서를 섞습니다",
                ) {
                    Switch(
                        checked = state.shuffle,
                        onCheckedChange = onShuffle,
                        colors = switchColors(),
                    )
                }
                SettingRow(
                    title = "반복 모드",
                    subtitle = when (state.repeat) {
                        "all" -> "전체 반복 · 목록 끝에서 처음 곡으로"
                        "one" -> "한 곡 반복 · 같은 곡을 계속"
                        else -> if (state.infinite) {
                            "반복 없음 · 무한재생이 켜져 있어 계속 재생"
                        } else {
                            "반복 없음 · 목록 끝(마지막 곡)에서 정지"
                        }
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatChip("전체", state.repeat == "all") { onRepeat("all") }
                        RepeatChip("한곡", state.repeat == "one") { onRepeat("one") }
                        RepeatChip("끄기", state.repeat == "off") { onRepeat("off") }
                    }
                }
            }

            item {
                SectionTitle("차트")
                SettingRow(
                    title = "멜론 최신 뮤직비디오",
                    subtitle = if (mvEnabled) {
                        "홈 소스 목록에 최신 뮤직비디오가 표시됩니다"
                    } else {
                        "사용 안 함 · 소스 목록에서 숨겨집니다"
                    },
                ) {
                    Switch(
                        checked = mvEnabled,
                        onCheckedChange = onMelonMv,
                        colors = switchColors(),
                    )
                }
            }

            item {
                SectionTitle("음향")
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeDown, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                        Slider(
                            value = state.volume,
                            onValueChange = onVolume,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = Surface2,
                            ),
                        )
                        Icon(Icons.Rounded.VolumeUp, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                SettingRow(
                    title = "음질",
                    subtitle = when (quality) {
                        "medium" -> "보통 (비트레이트 낮음)"
                        "low" -> "낮음 (데이터 절약)"
                        else -> "최고 (가용 최고 음질)"
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatChip("최고", quality == "high") { onQuality("high") }
                        RepeatChip("보통", quality == "medium") { onQuality("medium") }
                        RepeatChip("낮음", quality == "low") { onQuality("low") }
                    }
                }
            }

            item {
                SectionTitle("차량 블루투스 연결")
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Text(
                        "1. 휴대전화 설정에서 블루투스를 켭니다\n" +
                                "2. 블루투스 화면에서 차량을 검색해 연결합니다\n" +
                                "3. 차량 오디오 소스에서 '무한 음악 플레이어'를 선택합니다\n" +
                                "4. 스티어링휠이나 키패드로 재생·일시정지·곡 전환이 됩니다\n" +
                                "5. 차량 화면에 곡 정보와 앨범아트가 표시됩니다",
                        color = TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    )
                }
            }

            item {
                SectionTitle("재생목록")
                SettingRow(
                    title = "내 즐겨찾기 재생목록",
                    subtitle = if (favorites.isEmpty()) {
                        "아직 추가한 곡이 없습니다 · 홈에서 하트를 눌러 추가"
                    } else {
                        "${favorites.size}곡 · 별도 화면에서 재생·관리"
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .clickable(onClick = onOpenFavorites)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("열기", color = Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                val ctx = LocalContext.current
                var cacheText by remember { mutableStateOf(Storage.cacheSizeText(ctx)) }
                var logText by remember { mutableStateOf(Storage.logSizeText(ctx)) }
                SectionTitle("저장공간")
                SettingRow(
                    title = "캐시 비우기",
                    subtitle = "썸네일·임시 파일 · 현재 $cacheText",
                ) {
                    StorageButton("비우기") {
                        Storage.clearCaches(ctx)
                        cacheText = Storage.cacheSizeText(ctx)
                        android.widget.Toast.makeText(
                            ctx, "캐시를 비웠습니다", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                SettingRow(
                    title = "기록 삭제",
                    subtitle = "오류·스택 덤프 기록 · 현재 $logText",
                ) {
                    StorageButton("삭제") {
                        Storage.clearLogs(ctx)
                        logText = Storage.logSizeText(ctx)
                        android.widget.Toast.makeText(
                            ctx, "기록을 삭제했습니다", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }

            item {
                SectionTitle("재생 진단")
                SettingRow(
                    title = "진단 모드",
                    subtitle = if (state.diagnostics) {
                        "오류 메시지 표시 (기록은 항상 저장)"
                    } else {
                        "오류 메시지 숨김 (기록은 항상 저장)"
                    },
                ) {
                    Switch(
                        checked = state.diagnostics,
                        onCheckedChange = onDiagnostics,
                        colors = switchColors(),
                    )
                }
                SettingRow(
                    title = "진단 정보 보기",
                    subtitle = "소리가 안 나거나 재생이 안 될 때 확인하세요",
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .clickable(onClick = onOpenDiagnostics)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("열기", color = Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                SectionTitle("정보")
                Text(
                    "무한 음악 플레이어 ${org.music.infiniteplayer.core.CrashLog.BuildInfo.VERSION}\n" +
                            "네이티브(코틀린) + ExoPlayer 기반\n" +
                            "차트: 멜론(TOP100·장르별) · 멜론 최신 뮤직비디오 · 유튜브 장르",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
                VSpace(30.dp)
            }
        }
    }
}

@Composable
private fun StorageButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun RepeatChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Accent,
    uncheckedThumbColor = TextSecondary,
    uncheckedTrackColor = Surface2,
    uncheckedBorderColor = Color.Transparent,
)

