package com.dualboost.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens. Wi-Fi is always drawn in [ink], mobile data always in [accent]. */
@Immutable
data class DbColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val dark: Boolean,
)

val Accent = Color(0xFFE8332A)

private val Dark = DbColors(
    bg = Color(0xFF0B0B0C),
    surface = Color(0xFF151517),
    surfaceHigh = Color(0xFF1E1E21),
    ink = Color(0xFFF4F4F1),
    muted = Color(0xFF8C8C91),
    faint = Color(0xFF26262A),
    hairline = Color(0xFF242427),
    accent = Accent,
    onAccent = Color.White,
    good = Color(0xFF4CD27A),
    dark = true,
)

private val Light = DbColors(
    bg = Color(0xFFF4F3EF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFECEBE6),
    ink = Color(0xFF0B0B0C),
    muted = Color(0xFF6C6C71),
    faint = Color(0xFFE2E1DB),
    hairline = Color(0xFFE6E5E0),
    accent = Accent,
    onAccent = Color.White,
    good = Color(0xFF1F9D52),
    dark = false,
)

val LocalDb = staticCompositionLocalOf { Dark }

object Db {
    val colors: DbColors @Composable get() = LocalDb.current

    // 8-point spacing grid
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val gutter = 20.dp
    val radius = 28.dp
    val radiusSmall = 18.dp

    val mono = FontFamily.Monospace

    /** Small spaced-out uppercase label, the signature of the design. */
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val number = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
}

@Composable
fun DualBoostTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted,
        outline = c.faint, outlineVariant = c.hairline,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted,
        outline = c.faint, outlineVariant = c.hairline,
    )
    CompositionLocalProvider(LocalDb provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
