package com.dualboost.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import kotlin.math.PI

// ---------------------------------------------------------------- surfaces

@Composable
fun DbCard(
    modifier: Modifier = Modifier,
    padding: Dp = Db.s5,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Db.radius)
    Column(
        modifier
            .clip(shape)
            .background(Db.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Rounded square with a tinted background, the app's icon container. */
@Composable
fun IconTile(@DrawableRes icon: Int, color: Color, size: Dp = 40.dp, corner: Dp = 12.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** Round icon button used in top bars (48 dp touch target). */
@Composable
fun RoundIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    val c = Db.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.surface)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = c.ink, modifier = Modifier.size(22.dp))
    }
}

/** Status pill: coloured dot + short text on a soft background. */
@Composable
fun StatusPill(text: String, dot: Color) {
    val c = Db.colors
    Row(
        Modifier.clip(CircleShape).background(c.surfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot, 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Number with a smaller unit after it, baseline-aligned. */
@Composable
fun ValueWithUnit(value: String, unit: String?, style: TextStyle, color: Color = Db.colors.ink) {
    val c = Db.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, style = style, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = c.muted, style = Db.unit, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

// ---------------------------------------------------------------- primary button

/**
 * The main Start/Stop pill: solid [color] (Wi-Fi blue to start, mobile orange to stop),
 * cross-fading when the state changes.
 */
@Composable
fun MainButton(
    text: String,
    @DrawableRes icon: Int?,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 700f), label = "press")
    val bg by animateColorAsState(color, tween(350), label = "bg")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.75f, tween(200), label = "alpha")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .scale(scale)
            .height(60.dp)
            .clip(shape)
            .background(bg.copy(alpha = alpha), shape)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Db.s6),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SmallPill(text: String, filled: Boolean = true, onClick: () -> Unit) {
    val c = Db.colors
    Box(
        Modifier.clip(CircleShape)
            .background(if (filled) c.primary else c.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) c.onPrimary else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- data visuals

/**
 * Speed ring. Track in [DbColors.faint]; when active, a blue Wi-Fi arc and an orange mobile
 * arc show each link's share, starting at 12 o'clock and running clockwise.
 */
@Composable
fun SpeedRing(wifiShare: Float, active: Boolean, modifier: Modifier = Modifier, stroke: Dp = 14.dp) {
    val c = Db.colors
    val share by animateFloatAsState(wifiShare.coerceIn(0f, 1f), tween(700), label = "share")
    val reveal by animateFloatAsState(if (active) 1f else 0f, tween(700), label = "reveal")
    Canvas(modifier) {
        val sw = stroke.toPx()
        val d = size.minDimension - sw
        val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
        val arcSize = Size(d, d)
        drawArc(c.faint, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
        if (reveal <= 0.001f) return@Canvas
        // Round caps add half the stroke at each end, so widen the gap by the cap's angle.
        val r = d / 2
        val capDeg = ((sw / 2) / r) * (180f / PI.toFloat())
        val gap = 6f + 2 * capDeg
        val total = 360f * reveal
        val wifiSweep = total * share
        val mobileSweep = total - wifiSweep
        val style = Stroke(sw, cap = StrokeCap.Round)
        val both = share > 0.02f && share < 0.98f
        if (wifiSweep > 0.5f) {
            val sweep = if (both) (wifiSweep - gap).coerceAtLeast(0.1f) else wifiSweep
            drawArc(c.wifi, -90f + (if (both) gap / 2 else 0f), sweep, false, topLeft, arcSize, style = style)
        }
        if (mobileSweep > 0.5f) {
            val sweep = if (both) (mobileSweep - gap).coerceAtLeast(0.1f) else mobileSweep
            drawArc(c.mobile, -90f + wifiSweep + (if (both) gap / 2 else 0f), sweep, false, topLeft, arcSize, style = style)
        }
    }
}

/** 60-second live chart: Wi-Fi and mobile download lines with a soft fill under each. */
@Composable
fun SpeedChart(wifi: List<Float>, mobile: List<Float>, modifier: Modifier = Modifier) {
    val c = Db.colors
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val maxV = (listOf(wifi.maxOrNull() ?: 0f, mobile.maxOrNull() ?: 0f).max() * 1.2f).coerceAtLeast(5f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
        for (q in 1..3) {
            val y = h * q / 4f
            drawLine(c.faint, Offset(0f, y), Offset(w, y), 1.dp.toPx(), pathEffect = dash)
        }
        fun series(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val n = 60
            val stepX = w / (n - 1)
            val startIdx = n - values.size
            val line = Path()
            values.forEachIndexed { i, v ->
                val x = (startIdx + i) * stepX
                val y = h - (v / maxV) * h
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(w, h); lineTo(startIdx * stepX, h); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(line, color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        series(wifi, c.wifi)
        series(mobile, c.mobile)
    }
}

@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val c = Db.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "progress")
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(c.faint, cornerRadius = r)
        if (f > 0f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    val c = Db.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.muted, style = Db.caption)
    }
}

// ---------------------------------------------------------------- lists & settings

@Composable
fun SectionHeader(text: String) {
    Text(
        text, color = Db.colors.muted, style = Db.section,
        modifier = Modifier.padding(start = Db.s1, top = Db.s6, bottom = 10.dp),
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    DbCard(Modifier.fillMaxWidth(), padding = 0.dp, content = content)
}

@Composable
fun RowDivider(inset: Boolean = true) {
    Box(
        Modifier.fillMaxWidth().padding(start = if (inset) 68.dp else 0.dp)
            .height(1.dp).background(Db.colors.faint)
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconColor: Color = Db.colors.primary,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Db.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 18.dp, vertical = Db.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, iconColor, size = 36.dp, corner = 11.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = Db.rowTitle)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = c.muted, style = Db.small)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Db.s3))
            trailing()
        }
    }
}

@Composable
fun DbSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Db.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.primary, checkedBorderColor = c.primary,
            uncheckedThumbColor = if (c.dark) Color(0xFFB9BDC4) else Color.White,
            uncheckedTrackColor = c.faint, uncheckedBorderColor = c.faint,
        ),
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    @DrawableRes icon: Int?,
    iconColor: Color,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(title, subtitle, icon, iconColor, onClick = { onChange(!checked) }, role = Role.Switch) {
        DbSwitch(checked, onChange)
    }
}

@Composable
fun ChevronRow(
    title: String,
    subtitle: String?,
    @DrawableRes icon: Int?,
    iconColor: Color,
    value: String? = null,
    onClick: () -> Unit,
) {
    val c = Db.colors
    SettingRow(title, subtitle, icon, iconColor, onClick = onClick, role = Role.Button) {
        if (value != null) {
            Text(value, color = c.muted, style = Db.small, maxLines = 1)
            Spacer(Modifier.width(2.dp))
        }
        Icon(painterResource(R.drawable.ic_chevron), null, tint = c.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Db.colors
    SettingRow(title, subtitle, onClick = onClick, role = Role.RadioButton) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) c.primary else c.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(c.primary))
        }
    }
}

/** Equal-width segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = Db.colors
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(c.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) c.ink else Color.Transparent, tween(200), label = "seg")
            val fg by animateColorAsState(if (sel) c.bg else c.muted, tween(200), label = "segfg")
            Box(
                Modifier.weight(1f).clip(CircleShape).background(bg)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Equal-width option chips (single choice). */
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Db.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) c.ink else c.surfaceHigh, tween(200), label = "chip")
            val fg by animateColorAsState(if (sel) c.bg else c.ink, tween(200), label = "chipfg")
            Box(
                Modifier.weight(1f).clip(CircleShape).background(bg)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Card for things that need the user's attention. */
@Composable
fun NoticeCard(
    title: String,
    text: String,
    color: Color,
    @DrawableRes icon: Int,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Db.colors
    DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color, size = 36.dp, corner = 11.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, style = Db.rowTitle)
                Spacer(Modifier.height(2.dp))
                Text(text, color = c.muted, style = Db.small)
            }
            if (action != null && onAction != null) {
                Spacer(Modifier.width(Db.s3))
                SmallPill(action, onClick = onAction)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center, content = content)

/** Top bar for inner screens: back button and a left-aligned title. */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    val c = Db.colors
    Row(
        Modifier.fillMaxWidth().padding(top = Db.s3, bottom = Db.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(R.drawable.ic_back, "Back", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, color = c.ink, style = Db.h1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The DualBoost launcher icon (dark tile, blue/orange ring, white bolt) drawn in-app.
 * A faint outline keeps the tile visible on the dark background.
 */
@Composable
fun AppIcon(size: Dp) {
    val c = Db.colors
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        Modifier.size(size).clip(shape).background(Color(0xFF0E0F12))
            .then(if (c.dark) Modifier.border(1.dp, c.faint, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            // The artwork sits in the middle of a 108-unit canvas; enlarge it to fill the tile.
            modifier = Modifier.size(size).graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
        )
    }
}
