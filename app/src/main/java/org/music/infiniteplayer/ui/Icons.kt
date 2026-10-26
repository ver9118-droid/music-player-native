package org.music.infiniteplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 직접 그리는 아이콘.
 *
 * R8 을 꺼두면 `material-icons-extended` 는 약 8,500 개 클래스(압축 6~8MB) 가 APK 에 그대로 들어온다.
 * core 패키지(98 개) 에는 실제로 쓰는 아이콘이 거의 없으므로, 필요한 11 개만 그려서 APK 를 줄인다.
 *
 * path data 출처: material-icons/material-icons(Apache-2.0) svg/{name}/round.svg
 * core 에 이미 있는 아이콘(Settings/Favorite/FavoriteBorder/ArrowBack/Search/Close/PlayArrow/
 * Lock/KeyboardArrowDown/Notifications/Delete) 은 `Icons.Rounded.*` 를 그대로 쓴다.
 */
object AppIcons {

    val Shuffle: ImageVector = rounded(
        "shuffle",
        "M10.59 9.17L6.12 4.7a.996.996 0 1 0-1.41 1.41l4.46 4.46l1.42-1.4zm4.76-4.32l1.19 1.19L4.7 17.88a.996.996 0 1 0 1.41 1.41L17.96 7.46l1.19 1.19a.5.5 0 0 0 .85-.36V4.5c0-.28-.22-.5-.5-.5h-3.79a.5.5 0 0 0-.36.85zm-.52 8.56l-1.41 1.41l3.13 3.13l-1.2 1.2a.5.5 0 0 0 .36.85h3.79c.28 0 .5-.22.5-.5v-3.79c0-.45-.54-.67-.85-.35l-1.19 1.19l-3.13-3.14z",
    )

    val Pause: ImageVector = rounded(
        "pause",
        "M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2 2 2s2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2z",
    )

    val SkipNext: ImageVector = rounded(
        "skip_next",
        "M7.58 16.89l5.77-4.07c.56-.4.56-1.24 0-1.63L7.58 7.11C6.91 6.65 6 7.12 6 7.93v8.14c0 .81.91 1.28 1.58.82zM16 7v10c0 .55.45 1 1 1s1-.45 1-1V7c0-.55-.45-1-1-1s-1 .45-1 1z",
    )

    val SkipPrevious: ImageVector = rounded(
        "skip_previous",
        "M7 6c.55 0 1 .45 1 1v10c0 .55-.45 1-1 1s-1-.45-1-1V7c0-.55.45-1 1-1zm3.66 6.82l5.77 4.07c.66.47 1.58-.01 1.58-.82V7.93c0-.81-.91-1.28-1.58-.82l-5.77 4.07a1 1 0 0 0 0 1.64z",
    )

    val LockOpen: ImageVector = rounded(
        "lock_open",
        "M12 13c-1.1 0-2 .9-2 2s.9 2 2 2s2-.9 2-2s-.9-2-2-2zm6-5h-1V6c0-2.76-2.24-5-5-5c-2.28 0-4.27 1.54-4.84 3.75c-.14.54.18 1.08.72 1.22a1 1 0 0 0 1.22-.72A2.996 2.996 0 0 1 12 3c1.65 0 3 1.35 3 3v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 11c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-8c0-.55.45-1 1-1h10c.55 0 1 .45 1 1v8z",
    )

    val Download: ImageVector = rounded(
        "download",
        "M16.59 9H15V4c0-.55-.45-1-1-1h-4c-.55 0-1 .45-1 1v5H7.41c-.89 0-1.34 1.08-.71 1.71l4.59 4.59c.39.39 1.02.39 1.41 0l4.59-4.59c.63-.63.19-1.71-.7-1.71zM5 19c0 .55.45 1 1 1h12c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1 1z",
    )

    val MusicNote: ImageVector = rounded(
        "music_note",
        "M12 5v8.55c-.94-.54-2.1-.75-3.33-.32c-1.34.48-2.37 1.67-2.61 3.07a4.007 4.007 0 0 0 4.59 4.65c1.96-.31 3.35-2.11 3.35-4.1V7h2c1.1 0 2-.9 2-2s-.9-2-2-2h-2c-1.1 0-2 .9-2 2z",
    )

    val VolumeDown: ImageVector = rounded(
        "volume_down",
        "M18.5 12A4.5 4.5 0 0 0 16 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L9 9H6c-.55 0-1 .45-1 1z",
    )

    val VolumeUp: ImageVector = rounded(
        "volume_up",
        "M3 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L7 9H4c-.55 0-1 .45-1 1zm13.5 2A4.5 4.5 0 0 0 14 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 4.45v.2c0 .38.25.71.6.85C17.18 6.53 19 9.06 19 12s-1.82 5.47-4.4 6.5c-.36.14-.6.47-.6.85v.2c0 .63.63 1.07 1.21.85C18.6 19.11 21 15.84 21 12s-2.4-7.11-5.79-8.4c-.58-.23-1.21.22-1.21.85z",
    )

    val Repeat: ImageVector = rounded(
        "repeat",
        "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79a.5.5 0 0 0-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79a.5.5 0 0 0 .85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3z",
    )

    val RepeatOne: ImageVector = rounded(
        "repeat_one",
        "M7 7h10v1.79c0 .45.54.67.85.35l2.79-2.79c.2-.2.2-.51 0-.71l-2.79-2.79a.5.5 0 0 0-.85.36V5H6c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1s1-.45 1-1V7zm10 10H7v-1.79c0-.45-.54-.67-.85-.35l-2.79 2.79c-.2.2-.2.51 0 .71l2.79 2.79a.5.5 0 0 0 .85-.36V19h11c.55 0 1-.45 1-1v-4c0-.55-.45-1-1-1s-1 .45-1 1v3zm-4-2.75V9.81c0-.45-.36-.81-.81-.81a.74.74 0 0 0-.36.09l-1.49.74a.61.61 0 0 0-.34.55c0 .34.28.62.62.62h.88v3.25c0 .41.34.75.75.75s.75-.34.75-.75z",
    )

    val FolderOpen: ImageVector = symbol(
        "folder_open",
        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h360q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640H447l-80-80H160v480l79-263q8-26 29.5-41.5T316-560h516q41 0 64.5 32.5T909-457l-72 240q-8 26-29.5 41.5T760-160H160Zm84-80h516l72-240H316l-72 240Z",
    )
}

private fun rounded(name: String, data: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        // fill 을 지정하지 않으면 아무것도 그려지지 않는다 (기본값 null)
        .path(fill = SolidColor(Color.Black)) { applyPathData(data) }
        .build()

/**
 * Material Symbols svg(0 -960 960 960 viewBox) 를 24x24 로 축약한다.
 * (축소 배율 1/40, y 는 24 만큼 아래로 이동)
 */
private fun symbol(name: String, data: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .path(fill = SolidColor(Color.Black)) { applyPathData(data, 1f / 40f, 24f) }
        .build()

/** svg path data 를 PathBuilder DSL 로 옮겨 담는다(extendRule = NonZero, svg fill-rule 없음). */
private fun PathBuilder.applyPathData(data: String, k: Float = 1f, oy: Float = 0f) {
    fun y(v: Float) = v * k + oy
    for (node in PathParser().parsePathString(data).toNodes()) {
        when (node) {
            is PathNode.MoveTo -> moveTo(node.x * k, y(node.y))
            is PathNode.RelativeMoveTo -> moveToRelative(node.dx * k, node.dy * k)
            is PathNode.LineTo -> lineTo(node.x * k, y(node.y))
            is PathNode.RelativeLineTo -> lineToRelative(node.dx * k, node.dy * k)
            is PathNode.HorizontalTo -> horizontalLineTo(node.x * k)
            is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx * k)
            is PathNode.VerticalTo -> verticalLineTo(y(node.y))
            is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy * k)
            is PathNode.CurveTo -> curveTo(
                node.x1 * k, y(node.y1), node.x2 * k, y(node.y2), node.x3 * k, y(node.y3),
            )
            is PathNode.RelativeCurveTo ->
                curveToRelative(node.dx1 * k, node.dy1 * k, node.dx2 * k, node.dy2 * k, node.dx3 * k, node.dy3 * k)
            is PathNode.ReflectiveCurveTo -> reflectiveCurveTo(node.x1 * k, y(node.y1), node.x2 * k, y(node.y2))
            is PathNode.RelativeReflectiveCurveTo ->
                reflectiveCurveToRelative(node.dx1 * k, node.dy1 * k, node.dx2 * k, node.dy2 * k)
            is PathNode.QuadTo -> quadTo(node.x1 * k, y(node.y1), node.x2 * k, y(node.y2))
            is PathNode.RelativeQuadTo -> quadToRelative(node.dx1 * k, node.dy1 * k, node.dx2 * k, node.dy2 * k)
            is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x * k, y(node.y))
            is PathNode.RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx * k, node.dy * k)
            is PathNode.ArcTo -> arcTo(
                node.horizontalEllipseRadius * k,
                node.verticalEllipseRadius * k,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartX * k,
                y(node.arcStartY),
            )
            is PathNode.RelativeArcTo -> arcToRelative(
                node.horizontalEllipseRadius * k,
                node.verticalEllipseRadius * k,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                node.arcStartDx * k,
                node.arcStartDy * k,
            )
            is PathNode.Close -> close()
        }
    }
}
