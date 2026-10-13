package com.dualboost.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.LinkStatus
import com.dualboost.app.core.LinkUi
import com.dualboost.app.core.Phase
import com.dualboost.app.update.UpdatePhase
import com.dualboost.app.update.UpdateUi
import kotlinx.coroutines.delay

/** What the phone reports while the boost is off, so the cards are never empty. */
data class IdleLinks(val wifi: Boolean, val mobile: Boolean)

@Composable
fun HomeScreen(
    ui: BoostUi,
    idle: IdleLinks,
    mobileCapMb: Int,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onDismissError: () -> Unit,
    update: UpdateUi,
    canInstall: Boolean,
    onUpdate: () -> Unit,
    onAllowInstall: () -> Unit,
) {
    val c = Db.colors
    val on = ui.phase == Phase.ON
    val busy = ui.phase == Phase.STARTING || ui.phase == Phase.STOPPING

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) { while (on) { now = System.currentTimeMillis(); delay(1000) } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Db.gutter),
        ) {
            // ---- Header
            Row(Modifier.fillMaxWidth().padding(top = Db.s3), verticalAlignment = Alignment.CenterVertically) {
                Text("DualBoost", color = c.ink, style = Db.h1)
                Spacer(Modifier.weight(1f))
                RoundIconButton(R.drawable.ic_settings, "Settings", onOpenSettings)
            }
            Spacer(Modifier.height(Db.s3))

            HeroCard(ui, on)
            Spacer(Modifier.height(Db.s3))

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Db.s3)) {
                LinkCard(Modifier.weight(1f).fillMaxHeight(), "Wi-Fi", R.drawable.ic_wifi, c.wifi, ui.wifi, idle.wifi, on)
                LinkCard(Modifier.weight(1f).fillMaxHeight(), "Mobile data", R.drawable.ic_mobile, c.mobile, ui.mobile, idle.mobile, on)
            }

            Notices(ui, on, now, update, canInstall, onUpdate, onAllowInstall, onOpenDeveloperOptions, onDismissError)

            Spacer(Modifier.height(Db.s3))
            ChartCard(ui, on)
            Spacer(Modifier.height(Db.s3))
            StatsCard(ui, on, now, mobileCapMb)

            Spacer(Modifier.height(Db.s4))
            Text(
                "Downloads, app updates and multi-connection speed tests get the full combined speed. A single video stream uses one link at a time.",
                color = c.muted, style = Db.small, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Db.s4),
            )
            Spacer(Modifier.height(112.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        // ---- Floating main control
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.bg, 1f to c.bg))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = Db.gutter, end = Db.gutter, top = 28.dp, bottom = Db.s4),
        ) {
            MainButton(
                text = when (ui.phase) {
                    Phase.OFF -> "Start boost"
                    Phase.STARTING -> "Starting…"
                    Phase.ON -> "Stop boost"
                    Phase.STOPPING -> "Stopping…"
                },
                icon = if (ui.phase == Phase.OFF) R.drawable.ic_bolt else R.drawable.ic_power,
                prominent = ui.phase == Phase.OFF,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggle,
            )
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun HeroCard(ui: BoostUi, on: Boolean) {
    val c = Db.colors
    val total = ui.wifi.downMbps + ui.mobile.downMbps
    val flowing = on && total > 0.05
    val wifiShare = if (flowing) (ui.wifi.downMbps / total).toFloat() else 0.5f
    val readyLinks = listOf(ui.wifi, ui.mobile).count { it.status == LinkStatus.READY }

    DbCard(Modifier.fillMaxWidth(), padding = Db.s5) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val (pillText, pillDot) = when (ui.phase) {
                Phase.ON -> (if (readyLinks >= 2) "Boosting · 2 links" else "Running · 1 link") to (if (readyLinks >= 2) c.good else c.warn)
                Phase.STARTING -> "Starting…" to c.warn
                Phase.STOPPING -> "Stopping…" to c.muted
                Phase.OFF -> "Off" to c.muted
            }
            StatusPill(pillText, pillDot)
            Spacer(Modifier.height(18.dp))

            Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
                SpeedRing(wifiShare, active = flowing, modifier = Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (on) {
                        Text(formatMbps(total), color = c.ink, style = Db.display, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Text("Mbps combined", color = c.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Text(
                            if (ui.phase == Phase.STARTING) "…" else "Off",
                            color = c.muted, style = Db.displayOff,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (ui.phase == Phase.STARTING) "Connecting both links" else "Tap Start to combine",
                            color = c.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(c.wifi, 8.dp); Spacer(Modifier.width(6.dp))
                Text("Wi-Fi ", color = c.muted, style = Db.small)
                Text(if (flowing) "${(wifiShare * 100).toInt()}%" else "–", color = c.ink, style = Db.small, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("Mobile ", color = c.muted, style = Db.small)
                Text(if (flowing) "${100 - (wifiShare * 100).toInt()}%" else "–", color = c.ink, style = Db.small, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp)); Dot(c.mobile, 8.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- link cards

@Composable
private fun LinkCard(
    modifier: Modifier,
    name: String,
    @DrawableRes icon: Int,
    color: Color,
    link: LinkUi,
    idleAvailable: Boolean,
    on: Boolean,
) {
    val c = Db.colors
    val (statusText, statusColor) = if (on) when (link.status) {
        LinkStatus.READY -> "Connected" to c.good
        LinkStatus.NO_INTERNET -> "No internet" to c.warn
        LinkStatus.OFF -> "Off" to c.muted
    } else if (idleAvailable) "Available" to c.good else "Off" to c.muted

    DbCard(modifier, padding = 18.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color)
            Spacer(Modifier.weight(1f))
            Dot(statusColor, 6.dp)
            Spacer(Modifier.width(6.dp))
            Text(statusText, color = c.muted, style = Db.caption, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Db.s4))
        if (on && link.status != LinkStatus.OFF) {
            ValueWithUnit(formatMbps(link.downMbps), "Mbps", Db.value)
        } else {
            Text("—", color = c.muted, style = Db.value)
        }
        Spacer(Modifier.height(2.dp))
        Text(name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                on && link.status != LinkStatus.OFF -> "↑ ${formatMbps(link.upMbps)} Mbps · ${link.connections} active"
                on -> "Not connected"
                idleAvailable -> "Ready to combine"
                else -> "Not connected"
            },
            color = c.muted, style = Db.caption, maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- notices

@Composable
private fun Notices(
    ui: BoostUi,
    on: Boolean,
    now: Long,
    update: UpdateUi,
    canInstall: Boolean,
    onUpdate: () -> Unit,
    onAllowInstall: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onDismissError: () -> Unit,
) {
    val c = Db.colors
    val showUpdate = update.phase in setOf(
        UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING, UpdatePhase.WAITING_CONFIRM,
    ) || (update.phase == UpdatePhase.ERROR && update.info != null)
    Animated(showUpdate) { UpdateNotice(update, canInstall, onUpdate, onAllowInstall) }

    Animated(ui.error != null) {
        NoticeCard(
            "Boost stopped", ui.error ?: "", c.warn, R.drawable.ic_info,
            action = "OK", onAction = onDismissError,
        )
    }
    // Only warn once the boost has had time to wake mobile data up itself.
    val mobileMissing = on && now - ui.startedAt > 6_000 &&
        ui.mobile.status == LinkStatus.OFF && ui.wifi.status != LinkStatus.OFF
    Animated(mobileMissing) {
        NoticeCard(
            "Mobile data isn't active",
            "Turn on Mobile data. If it's on, enable \"Mobile data always active\" in Developer options.",
            c.mobile, R.drawable.ic_mobile, action = "Fix", onAction = onOpenDeveloperOptions,
        )
    }
    Animated(on && ui.mobileCapReached) {
        NoticeCard(
            "Daily mobile limit reached",
            "Wi-Fi carries everything now. Mobile data is used only if Wi-Fi drops.",
            c.mobile, R.drawable.ic_mobile,
        )
    }
}

@Composable
private fun Animated(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            Spacer(Modifier.height(Db.s3))
            content()
        }
    }
}

// ---------------------------------------------------------------- chart & stats

@Composable
private fun ChartCard(ui: BoostUi, on: Boolean) {
    val c = Db.colors
    DbCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live speed", color = c.ink, style = Db.cardTitle)
            Spacer(Modifier.weight(1f))
            LegendItem(c.wifi, "Wi-Fi")
            Spacer(Modifier.width(Db.s3))
            LegendItem(c.mobile, "Mobile")
        }
        Spacer(Modifier.height(Db.s4))
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            SpeedChart(
                if (on) ui.wifiHistory else emptyList(),
                if (on) ui.mobileHistory else emptyList(),
                Modifier.fillMaxSize(),
            )
            if (!on) {
                Text(
                    "Start the boost to see live speed",
                    color = c.muted, style = Db.small,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(Db.s2))
        Row(Modifier.fillMaxWidth()) {
            Text("60 s ago", color = c.muted, style = Db.caption)
            Spacer(Modifier.weight(1f))
            Text("now", color = c.muted, style = Db.caption)
        }
    }
}

@Composable
private fun StatsCard(ui: BoostUi, on: Boolean, now: Long, capMb: Int) {
    val c = Db.colors
    val (usedNum, usedUnit) = formatBytes(ui.mobileTodayBytes).split(" ").let { it[0] to it.getOrNull(1) }
    DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCell(Modifier.weight(1f), "Upload", if (on) formatMbps(ui.totalUp) else null, "Mbps")
            VLine()
            StatCell(Modifier.weight(1f), "Peak", if (on) formatMbps(ui.peakMbps) else null, "Mbps")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.faint))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCell(
                Modifier.weight(1f), "Session",
                if (on && ui.startedAt > 0) formatDuration(now - ui.startedAt) else null, null,
            )
            VLine()
            StatCell(
                Modifier.weight(1f), "Mobile today", usedNum, usedUnit,
                footer = if (capMb > 0) {
                    { ProgressLine(ui.mobileTodayBytes / (capMb * 1_000_000f), if (ui.mobileCapReached) c.mobile else c.wifi) }
                } else null,
            )
        }
    }
}

@Composable
private fun VLine() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Db.colors.faint))
}

@Composable
private fun StatCell(
    modifier: Modifier,
    label: String,
    value: String?,
    unit: String?,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = Db.colors
    Column(modifier.padding(horizontal = Db.s5, vertical = Db.s4)) {
        Text(label, color = c.muted, style = Db.caption)
        Spacer(Modifier.height(Db.s1))
        if (value != null) ValueWithUnit(value, unit, Db.stat) else Text("—", color = c.muted, style = Db.stat)
        if (footer != null) {
            Spacer(Modifier.height(Db.s2))
            footer()
        }
    }
}
