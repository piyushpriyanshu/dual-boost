package com.dualboost.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.LinkStatus
import com.dualboost.app.core.LinkUi
import com.dualboost.app.core.Phase
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
) {
    val c = Db.colors
    val on = ui.phase == Phase.ON
    val busy = ui.phase == Phase.STARTING || ui.phase == Phase.STOPPING

    // Session clock
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) { while (on) { now = System.currentTimeMillis(); delay(1000) } }

    // When off, show what the phone currently has.
    val wifi = if (on) ui.wifi else LinkUi(status = if (idle.wifi) LinkStatus.READY else LinkStatus.OFF)
    val mobile = if (on) ui.mobile else LinkUi(status = if (idle.mobile) LinkStatus.READY else LinkStatus.OFF)

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Db.gutter),
        ) {
            Header(onOpenSettings)
            Spacer(Modifier.height(Db.s5))

            HeroCard(ui, on, busy, now)
            Spacer(Modifier.height(Db.s3))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Db.s3)) {
                LinkCard(Modifier.weight(1f), "Wi-Fi", R.drawable.ic_wifi, wifi, on, isMobile = false)
                LinkCard(Modifier.weight(1f), "Mobile", R.drawable.ic_mobile, mobile, on, isMobile = true)
            }

            // ---- Attention banners
            AnimatedVisibility(ui.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(Db.s3))
                    Banner("Boost stopped", ui.error ?: "", "Dismiss", onDismissError)
                }
            }
            // Only warn once the boost has had time to wake mobile data up itself.
            val mobileMissing = on && now - ui.startedAt > 6_000 &&
                ui.mobile.status == LinkStatus.OFF && ui.wifi.status != LinkStatus.OFF
            AnimatedVisibility(mobileMissing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(Db.s3))
                    Banner(
                        "Mobile data isn't active",
                        "Turn on Mobile data in quick settings. If it is already on, switch on \"Mobile data always active\" in Developer options.",
                        "Open Developer options", onOpenDeveloperOptions,
                    )
                }
            }
            AnimatedVisibility(on && ui.mobileCapReached, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(Db.s3))
                    Banner(
                        "Daily mobile limit reached",
                        "Wi-Fi now carries everything. Mobile data is only used if Wi-Fi drops. Change the limit in Settings.",
                        null, null,
                    )
                }
            }

            Spacer(Modifier.height(Db.s3))
            ChartCard(ui, on)
            Spacer(Modifier.height(Db.s3))
            MobileBudgetCard(ui.mobileTodayBytes, mobileCapMb)

            Spacer(Modifier.height(Db.s3))
            Text(
                "Tip: big downloads, app updates and speed tests in multi-connection mode get the full combined speed. A single video stream uses one connection at a time.",
                color = c.muted, style = Db.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Db.s4, vertical = Db.s2),
            )
            // Room for the floating button
            Spacer(Modifier.height(120.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        // ---- Floating main control
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = 0.92f), c.bg)))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = Db.gutter, end = Db.gutter, top = Db.s8, bottom = Db.s4),
        ) {
            PillButton(
                text = when (ui.phase) {
                    Phase.OFF -> "Start boost"
                    Phase.STARTING -> "Starting…"
                    Phase.ON -> "Stop boost"
                    Phase.STOPPING -> "Stopping…"
                },
                icon = if (on || ui.phase == Phase.STOPPING) R.drawable.ic_power else R.drawable.ic_bolt,
                filled = ui.phase == Phase.OFF,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggle,
            )
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    val c = Db.colors
    Row(
        Modifier.fillMaxWidth().padding(top = Db.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(c.accent, 10.dp)
        Spacer(Modifier.width(Db.s3))
        Text(
            "DUALBOOST", color = c.ink, fontFamily = Db.mono,
            fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.surface).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_settings), "Settings", tint = c.ink, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun HeroCard(ui: BoostUi, on: Boolean, busy: Boolean, now: Long) {
    val c = Db.colors
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow"
    )
    DbCard(Modifier.fillMaxWidth(), padding = Db.s6) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Combined download")
            Spacer(Modifier.weight(1f))
            val stateText = when (ui.phase) {
                Phase.ON -> "Live"; Phase.STARTING -> "Starting"; Phase.STOPPING -> "Stopping"; Phase.OFF -> "Off"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.alpha(if (on || busy) glow else 1f)) { Dot(if (on) c.accent else c.muted, 7.dp) }
                Spacer(Modifier.width(6.dp))
                Label(stateText, color = if (on) c.ink else c.muted)
            }
        }
        Spacer(Modifier.height(Db.s8))

        CenterBox(Modifier.fillMaxWidth()) {
            DotMatrixText(
                text = formatMbps(if (on) ui.totalDown else 0.0),
                lit = if (on) c.ink else c.muted.copy(alpha = 0.55f),
                unlit = c.faint,
                dot = 10.dp,
            )
        }
        Spacer(Modifier.height(Db.s3))
        Text(
            "Mbps", color = c.muted, fontFamily = Db.mono, fontSize = 13.sp, letterSpacing = 2.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Db.s6))

        // Share of traffic on each link
        val total = ui.wifi.downMbps + ui.mobile.downMbps
        val wifiShare = if (total > 0.05) (ui.wifi.downMbps / total).toFloat() else 0.5f
        SplitBar(wifiShare, active = on && total > 0.05)
        Spacer(Modifier.height(Db.s2 + 2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Dot(c.ink, 7.dp); Spacer(Modifier.width(6.dp))
            Label(if (on && total > 0.05) "Wi-Fi ${(wifiShare * 100).toInt()}%" else "Wi-Fi", color = c.ink)
            Spacer(Modifier.weight(1f))
            Label(if (on && total > 0.05) "${100 - (wifiShare * 100).toInt()}% Mobile" else "Mobile", color = c.ink)
            Spacer(Modifier.width(6.dp)); Dot(c.accent, 7.dp)
        }

        Spacer(Modifier.height(Db.s5))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Spacer(Modifier.height(Db.s4))

        Row(Modifier.fillMaxWidth()) {
            MiniStat(Modifier.weight(1f), "Upload", if (on) formatMbps(ui.totalUp) else "–", "Mbps")
            MiniStat(Modifier.weight(1f), "Peak", if (on) formatMbps(ui.peakMbps) else "–", "Mbps")
            MiniStat(Modifier.weight(1f), "Session", if (on && ui.startedAt > 0) formatDuration(now - ui.startedAt) else "–", "")
        }
    }
}

@Composable
private fun MiniStat(modifier: Modifier, label: String, value: String, unit: String) {
    val c = Db.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = c.ink, fontFamily = Db.mono, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (unit.isNotEmpty() && value != "–") {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
private fun LinkCard(
    modifier: Modifier,
    name: String,
    @DrawableRes icon: Int,
    link: LinkUi,
    on: Boolean,
    isMobile: Boolean,
) {
    val c = Db.colors
    val tint = if (isMobile) c.accent else c.ink
    DbCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, tint = if (isMobile) c.onAccent else c.bg, bg = tint, size = 38.dp)
            Spacer(Modifier.weight(1f))
            StatusChip(link.status)
        }
        Spacer(Modifier.height(Db.s5))
        Label(name)
        Spacer(Modifier.height(Db.s1))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (on) formatMbps(link.downMbps) else "–",
                color = c.ink, style = Db.number,
            )
            Spacer(Modifier.width(4.dp))
            Text("Mbps", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(Db.s3))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Spacer(Modifier.height(Db.s3))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Label("Up")
                Text(if (on) formatMbps(link.upMbps) else "–", color = c.ink, fontFamily = Db.mono, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Label("Active")
                Text(if (on) "${link.connections}" else "–", color = c.ink, fontFamily = Db.mono, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ChartCard(ui: BoostUi, on: Boolean) {
    val c = Db.colors
    DbCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Last 60 seconds")
            Spacer(Modifier.weight(1f))
            Dot(c.ink, 7.dp); Spacer(Modifier.width(6.dp)); Label("Wi-Fi", color = c.ink)
            Spacer(Modifier.width(Db.s3))
            Dot(c.accent, 7.dp); Spacer(Modifier.width(6.dp)); Label("Mobile", color = c.ink)
        }
        Spacer(Modifier.height(Db.s4))
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            SpeedChart(
                if (on) ui.wifiHistory else emptyList(),
                if (on) ui.mobileHistory else emptyList(),
                Modifier.fillMaxSize(),
            )
            if (!on) {
                Text(
                    "Start the boost to see live speed",
                    color = c.muted, style = Db.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun MobileBudgetCard(usedBytes: Long, capMb: Int) {
    val c = Db.colors
    DbCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Mobile data today")
            Spacer(Modifier.weight(1f))
            Text(formatBytes(usedBytes), color = c.ink, fontFamily = Db.mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Db.s3))
        if (capMb > 0) {
            val frac = usedBytes / (capMb * 1_000_000f)
            ProgressLine(frac, if (frac >= 1f) c.accent else c.ink)
            Spacer(Modifier.height(Db.s2))
            Text("of ${formatBytes(capMb * 1_000_000L)} daily limit", color = c.muted, style = Db.bodySmall)
        } else {
            Text("No daily limit. You can set one in Settings.", color = c.muted, style = Db.bodySmall)
        }
    }
}
