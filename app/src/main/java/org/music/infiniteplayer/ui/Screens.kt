package org.music.infiniteplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.music.infiniteplayer.data.LyricLine
import org.music.infiniteplayer.data.Source
import org.music.infiniteplayer.data.Sources
import org.music.infiniteplayer.data.Track
import org.music.infiniteplayer.playback.Diagnostics
import org.music.infiniteplayer.playback.PlaybackTick
import org.music.infiniteplayer.playback.PlayerState
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────
// 홈 화면
// ─────────────────────────────────────────────────────────────

private data class SourceItem(val group: String, val source: Source?)

@Composable
fun HomeScreen(
    tracks: List<Track>,
    state: PlayerState,
    sourceKey: String,
    loading: Boolean,
    message: String,
    searchQuery: String,
    searchActive: Boolean,
    searching: Boolean,
    crashLog: String,
    freezeDetected: Boolean,
    onOpenDiagnostics: () -> Unit,
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

        // 직전 실행에서 발생한 오류 안내
        if (crashLog.isNotBlank() || freezeDetected) {
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
                    text = if (freezeDetected)
                        "지난 실행에서 앱이 멈췄습니다 (진단 가능)" else "지난 실행에서 오류가 발생했습니다",
                    color = Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Danger)
                        .clickable(onClick = onOpenDiagnostics)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("확인", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 검색창 (통합검색 : 노래관련 영상만)
        SearchField(
            query = searchQuery,
            searching = searching,
            onQueryChange = onQueryChange,
            onSubmit = onSearchSubmit,
        )

        // 소스 선택 (검색 중에는 숨김)
        if (!searchActive) {
            SourceChips(sourceKey = sourceKey, onSelect = onSelectSource)
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

        if (message.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Danger.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(message, color = Danger, fontSize = 13.sp)
            }
        }

        // 재생 오류 안내
        if (state.error.isNotBlank()) {
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
private fun SourceChips(sourceKey: String, onSelect: (String) -> Unit) {    val items = remember {
        buildList {
            var lastGroup = ""
            Sources.all.forEach { src ->
                if (src.group != lastGroup) {
                    add(SourceItem(src.group, null))
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
    onSection: (Long, Long) -> Unit,
    onMarkStart: () -> Unit,
    onMarkEnd: () -> Unit,
    onRingtone: () -> Unit,
) {
    // 재생 위치는 이 화면에서만 구독 (홈 화면 재렌더링 방지)
    val tick by org.music.infiniteplayer.playback.PlayerBus.tick
        .collectAsStateWithLifecycle()
    var showLyrics by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 상단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
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
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(
                    Icons.Rounded.Lyrics,
                    "가사",
                    tint = if (showLyrics) Accent else TextSecondary,
                )
            }
        }

        // 앨범아트 / 가사
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showLyrics) {
                LyricsPane(
                    lines = state.lyrics,
                    positionMs = tick.positionMs,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Artwork(
                    url = state.thumbUrl,
                    size = 268.dp,
                    corner = 24.dp,
                    maxPx = 720,
                )
            }
        }

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
            positionMs = tick.positionMs,
            durationMs = tick.durationMs,
            onSeek = onSeek,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(tick.positionMs), color = TextSecondary, fontSize = 11.5.sp)
            Text(formatTime(tick.durationMs), color = TextSecondary, fontSize = 11.5.sp)
        }

        // 구간/벨소리/다운로드
        ActionChips(
            state = state,
            onFirstHalf = { onSection(0L, tick.durationMs / 2) },
            onSecondHalf = { onSection(tick.durationMs / 2, tick.durationMs) },
            onMarkStart = onMarkStart,
            onMarkEnd = onMarkEnd,
            onPlayHighlight = {
                onSection(state.highlightStartMs, state.highlightEndMs)
            },
            onRingtone = onRingtone,
            onDownload = onDownload,
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
}

/** 재생 진단 화면 */
@Composable
fun DiagnosticsScreen(
    state: PlayerState,
    diag: Diagnostics,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val crash = remember { org.music.infiniteplayer.core.CrashLog.read() }
    val stackDump = remember { org.music.infiniteplayer.core.CrashLog.readStack() }
    val report = remember(state, diag) {
        buildString {
            appendLine("무한 음악 플레이어 진단")
            appendLine("곡: ${state.title} / ${state.artist}")
            appendLine("상태: ${state.status} (player=${diag.playerState}, playing=${state.playing}, buffering=${state.buffering})")
            appendLine("스트림: ${diag.streamInfo}")
            appendLine("주소 응답: ${diag.streamProbe}")
            appendLine("시스템 볼륨: ${diag.systemVolume}")
            appendLine("출력 경로: ${diag.audioRoute}")
            appendLine("앱 볼륨: ${state.volume}")
            appendLine("오류: ${state.error.ifBlank { "없음" }}")
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
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    DiagRow("재생 상태", "${state.status}  (${diag.playerState})")
                    DiagRow("재생 중", if (state.playing) "예" else "아니오")
                    DiagRow("버퍼링", if (state.buffering) "예" else "아니오")
                    DiagRow("스트림", diag.streamInfo.ifBlank { "-" })
                    DiagRow("주소 응답", diag.streamProbe.ifBlank { "-" })
                    DiagRow("시스템 볼륨", diag.systemVolume.ifBlank { "-" })
                    DiagRow("출력 경로", diag.audioRoute.ifBlank { "-" })
                    DiagRow("서비스 상태", diag.serviceState.ifBlank { "-" })
                    DiagRow("UI 응답", diag.uiLatency.ifBlank { "-" })
                    DiagRow("앱 볼륨", "%.0f%%".format(state.volume * 100))
                    DiagRow("오류", state.error.ifBlank { "없음" })
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

/** 구간 · 벨소리 · 다운로드 칩 */
@Composable
private fun ActionChips(
    state: PlayerState,
    onFirstHalf: () -> Unit,
    onSecondHalf: () -> Unit,
    onMarkStart: () -> Unit,
    onMarkEnd: () -> Unit,
    onPlayHighlight: () -> Unit,
    onRingtone: () -> Unit,
    onDownload: () -> Unit,
) {
    val hasHighlight = state.highlightStartMs >= 0 && state.highlightEndMs > state.highlightStartMs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallChip("전반부", false, null) { onFirstHalf() }
        SmallChip("후반부", false, null) { onSecondHalf() }
        SmallChip(
            text = if (state.highlightStartMs >= 0) "A ${formatTime(state.highlightStartMs)}" else "A 지정",
            selected = state.highlightStartMs >= 0,
            icon = null,
        ) { onMarkStart() }
        SmallChip(
            text = if (state.highlightEndMs >= 0) "B ${formatTime(state.highlightEndMs)}" else "B 지정",
            selected = state.highlightEndMs >= 0,
            icon = null,
        ) { onMarkEnd() }
        if (hasHighlight) {
            SmallChip("하이라이트", true, null) { onPlayHighlight() }
        }
        SmallChip("벨소리", false, Icons.Rounded.Notifications) { onRingtone() }
        SmallChip(
            text = if (state.downloadProgress >= 0) "내려받는 중 ${state.downloadProgress}%" else "다운로드",
            selected = false,
            icon = Icons.Rounded.Download,
        ) { onDownload() }
    }
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
private fun LyricsPane(
    lines: List<LyricLine>,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("가사를 불러오는 중입니다...", color = TextSecondary, fontSize = 14.sp)
        }
        return
    }
    val idx = lines.indexOfFirst { it.startMs > positionMs }
        .let { if (it < 0) lines.size - 1 else max(0, it - 1) }
    val from = max(0, idx - 2)
    val to = min(lines.size, idx + 4)

    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (i in from until to) {
            val active = i == idx
            Text(
                text = lines[i].text,
                color = if (active) TextPrimary else TextSecondary.copy(alpha = 0.38f),
                fontSize = if (active) 21.sp else 17.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
        }
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
    onQuality: (String) -> Unit,
    favorites: List<Track>,
    onRemoveFavorite: (Track) -> Unit,
    onPlayFavorite: (Track) -> Unit,
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
                    subtitle = "목록 끝나면 자동으로 다음 순서를 이어 붙입니다",
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
                        "all" -> "전체 반복"
                        "one" -> "한 곡 반복"
                        else -> "반복 없음"
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
                        else -> "높음 (권장)"
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RepeatChip("높음", quality == "high") { onQuality("high") }
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
                SectionTitle("즐겨찾기 (${favorites.size})")
            }
            if (favorites.isEmpty()) {
                item {
                    Text(
                        "즐겨찾기한 곡이 없습니다",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(favorites, key = { index, t -> "${t.id}#$index" }) { _, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayFavorite(track) }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveFavorite(track) }) {
                            Icon(
                                Icons.Rounded.Delete,
                                "삭제",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("재생 진단")
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
                    "무한 음악 플레이어 2.0.0\n" +
                            "네이티브(코틀린) + ExoPlayer 기반\n" +
                            "차트: 멜론 · 지니 · 유튜브 장르",
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

