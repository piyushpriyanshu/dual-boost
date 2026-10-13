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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.core.LinkStatus

// ---------------------------------------------------------------- surfaces & text

@Composable
fun DbCard(
    modifier: Modifier = Modifier,
    padding: Dp = Db.s5,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Db.colors
    val shape = RoundedCornerShape(Db.radius)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Db.colors.muted, align: TextAlign? = null) {
    Text(text.uppercase(), modifier, color = color, style = Db.label, textAlign = align, maxLines = 1)
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun IconCircle(@DrawableRes icon: Int, tint: Color, bg: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun StatusChip(status: LinkStatus) {
    val c = Db.colors
    val (text, dot) = when (status) {
        LinkStatus.READY -> "Ready" to c.good
        LinkStatus.NO_INTERNET -> "No internet" to c.accent
        LinkStatus.OFF -> "Off" to c.muted
    }
    Row(
        Modifier.clip(CircleShape).background(c.surfaceHigh).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot, 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- the main button

@Composable
fun PillButton(
    text: String,
    @DrawableRes icon: Int?,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Db.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    val bg by animateColorAsState(if (filled) c.accent else c.surface, tween(250), label = "bg")
    val fg by animateColorAsState(if (filled) c.onAccent else c.ink, tween(250), label = "fg")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .scale(scale)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (filled) Color.Transparent else c.faint, shape)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Db.s6),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Db.s3))
        }
        Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    }
}

// ---------------------------------------------------------------- data visuals

/** Horizontal bar split into Wi-Fi (ink) and mobile (accent) shares. */
@Composable
fun SplitBar(wifiFraction: Float, active: Boolean, modifier: Modifier = Modifier) {
    val c = Db.colors
    val f by animateFloatAsState(wifiFraction.coerceIn(0f, 1f), tween(600), label = "split")
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val h = size.height
        val gap = if (active && f > 0.02f && f < 0.98f) 4.dp.toPx() else 0f
        if (!active) {
            drawRoundRect(c.faint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
            return@Canvas
        }
        val wW = (size.width - gap) * f
        if (wW > 0f) drawRoundRect(
            c.ink, size = androidx.compose.ui.geometry.Size(wW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
        )
        val mW = size.width - wW - gap
        if (mW > 0f) drawRoundRect(
            c.accent, topLeft = Offset(wW + gap, 0f), size = androidx.compose.ui.geometry.Size(mW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
        )
    }
}

/** 60-second live chart: Wi-Fi and mobile download lines with a soft fill under each. */
@Composable
fun SpeedChart(wifi: List<Float>, mobile: List<Float>, modifier: Modifier = Modifier) {
    val c = Db.colors
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val maxV = (listOf(wifi.maxOrNull() ?: 0f, mobile.maxOrNull() ?: 0f).max() * 1.15f).coerceAtLeast(5f)
        // Dotted guide lines (quarters)
        val dotStep = 7.dp.toPx()
        for (q in 1..3) {
            val y = h * q / 4f
            var x = 0f
            while (x <= w) { drawCircle(c.faint, 1.2.dp.toPx(), Offset(x, y)); x += dotStep }
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
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent)))
            drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        series(wifi, c.ink)
        series(mobile, c.accent)
    }
}

/** Thin progress bar for the daily mobile budget. */
@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val c = Db.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.faint)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(color))
    }
}

// ---------------------------------------------------------------- settings building blocks

@Composable
fun SectionTitle(text: String) {
    Label(text, Modifier.padding(start = Db.s2, top = Db.s6, bottom = Db.s3))
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    DbCard(Modifier.fillMaxWidth(), padding = 0.dp, content = content)
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().padding(horizontal = Db.s5).height(1.dp).background(Db.colors.hairline))
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Db.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = Db.s5, vertical = Db.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = Db.body, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = c.muted, style = Db.bodySmall)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Db.s4))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Db.colors
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.faint,
            ),
        )
    }
}

@Composable
fun ChevronRow(title: String, subtitle: String?, value: String? = null, onClick: () -> Unit) {
    val c = Db.colors
    SettingRow(title, subtitle, onClick = onClick) {
        if (value != null) {
            Text(value, color = c.muted, style = Db.bodySmall)
            Spacer(Modifier.width(Db.s1))
        }
        Icon(painterResource(com.dualboost.app.R.drawable.ic_chevron), null, tint = c.muted, modifier = Modifier.size(20.dp))
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

/** Banner for problems that need the user's attention. */
@Composable
fun Banner(title: String, text: String, action: String?, onAction: (() -> Unit)?) {
    val c = Db.colors
    DbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c.accent)
            Spacer(Modifier.width(Db.s3))
            Text(title, color = c.ink, style = Db.body, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Db.s2))
        Text(text, color = c.muted, style = Db.bodySmall)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Db.s3))
            Text(
                action.uppercase(), color = c.accent, style = Db.label,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center, content = content)
