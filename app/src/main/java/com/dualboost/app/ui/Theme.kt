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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens. Wi-Fi is always blue, mobile data always orange, so every chart,
 * ring, icon and legend reads the same way. [primary] (Wi-Fi blue) drives controls.
 */
@Immutable
data class DbColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val wifi: Color,
    val mobile: Color,
    val good: Color,
    val warn: Color,
    val purple: Color,
    val primary: Color,
    val onPrimary: Color,
    val dark: Boolean,
)

private val Dark = DbColors(
    bg = Color(0xFF0E0F12),
    surface = Color(0xFF17191D),
    surfaceHigh = Color(0xFF202328),
    ink = Color(0xFFF2F3F5),
    muted = Color(0xFF8B9099),
    faint = Color(0xFF2A2D33),
    wifi = Color(0xFF4C8DFF),
    mobile = Color(0xFFFF7A45),
    good = Color(0xFF3DD68C),
    warn = Color(0xFFFFB547),
    purple = Color(0xFFB07CFF),
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    dark = true,
)

private val Light = DbColors(
    bg = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEEF0F3),
    ink = Color(0xFF111317),
    muted = Color(0xFF6B7079),
    faint = Color(0xFFE3E6EA),
    wifi = Color(0xFF2F6FED),
    mobile = Color(0xFFF2612B),
    good = Color(0xFF1E9E5A),
    warn = Color(0xFFD98A00),
    purple = Color(0xFF8B5CF6),
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    dark = false,
)

val LocalDb = staticCompositionLocalOf { Dark }

/** The user's chosen speed unit, provided once at the top of the UI. */
val LocalSpeedUnit = androidx.compose.runtime.compositionLocalOf { com.dualboost.app.core.SpeedUnit.MBPS }

/** Shrinks a number style as the text gets longer (e.g. "78,400" in Kbps) so it always fits. */
fun TextStyle.fitted(text: String, fitsUpTo: Int, minScale: Float = 0.7f): TextStyle {
    if (text.length <= fitsUpTo) return this
    val scale = (fitsUpTo.toFloat() / text.length).coerceAtLeast(minScale)
    return copy(fontSize = fontSize * scale, lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight)
}

object Db {
    val colors: DbColors @Composable get() = LocalDb.current

    // 4-point spacing scale
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val gutter = 20.dp
    val radius = 24.dp

    /** Tabular figures keep numbers from shifting width while they update. */
    private const val TNUM = "tnum"

    val display = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeight = 64.sp, fontFeatureSettings = TNUM)
    val displayOff = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 48.sp)
    val h1 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val value = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, fontFeatureSettings = TNUM)
    val stat = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, fontFeatureSettings = TNUM)
    val cardTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val section = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val unit = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun DualBoostTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted,
        outline = c.faint, outlineVariant = c.faint,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.muted,
        outline = c.faint, outlineVariant = c.faint,
    )
    CompositionLocalProvider(LocalDb provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
