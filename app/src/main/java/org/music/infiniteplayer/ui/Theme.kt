package org.music.infiniteplayer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF7C83FF)
val AccentSoft = Color(0xFF2BD9B0)
val Bg = Color(0xFF080B12)
val Surface1 = Color(0xFF131826)
val Surface2 = Color(0xFF1C2333)
val Stroke = Color(0xFF263049)
val TextPrimary = Color(0xFFF1F4FF)
val TextSecondary = Color(0xFF93A0C2)
val Danger = Color(0xFFFF6B7A)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentSoft,
    onSecondary = Color(0xFF04231C),
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Stroke,
    error = Danger,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, color = TextPrimary),
    bodySmall = TextStyle(fontSize = 12.5.sp, color = TextSecondary),
    labelSmall = TextStyle(fontSize = 11.5.sp, color = TextSecondary),
)

@Composable
fun AppTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        content = content,
    )
}
