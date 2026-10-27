package com.dualboost.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dualboost.app.R
import com.dualboost.app.core.DiagResult
import com.dualboost.app.core.Diagnostics
import com.dualboost.app.core.LinkDiag
import com.dualboost.app.core.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onSpeedTest: () -> Unit, onReport: () -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    var result by remember { mutableStateOf<DiagResult?>(null) }
    var running by remember { mutableStateOf(true) }
    var runId by remember { mutableIntStateOf(0) }
    LaunchedEffect(runId) {
        running = true
        result = withContext(Dispatchers.IO) { runCatching { Diagnostics.measure(context) }.getOrNull() }
        running = false
    }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runId++ }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Db.gutter),
        ) {
            TopBar("Diagnostics", onBack)

            // ---- Summary
            SectionHeader(if (running) "Checking both links…" else "Summary")
            val r = result
            if (r == null) {
                DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Text(if (running) "This takes a few seconds." else "Couldn't run the check. Try again.", color = c.muted, style = Db.small)
                    if (running) { Spacer(Modifier.height(Db.s3)); ProgressLine(0.4f, c.wifi) }
                }
            } else {
                SettingsGroup {
                    r.verdicts.forEachIndexed { i, v ->
                        if (i > 0) RowDivider()
                        val col = when (v.severity) {
                            Severity.GOOD -> c.good; Severity.INFO -> c.wifi; Severity.WARN -> c.warn; Severity.BAD -> c.mobile
                        }
                        SettingRow(
                            v.title, v.detail,
                            icon = when (v.severity) { Severity.GOOD -> R.drawable.ic_shield; else -> R.drawable.ic_info },
                            iconColor = col,
                        )
                    }
                }

                LinkSection("Wi-Fi", c.wifi, r.wifi, wifi = true)
                LinkSection("Mobile data", c.mobile, r.mobile, wifi = false,
                    showTypeAction = r.needsPhonePermission && r.mobile.present,
                    onShowType = { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) })

                Spacer(Modifier.height(Db.s3))
                Text(
                    "Checked ${ago(r.at)}. Ping is the time to reach the internet; jitter is how much it varies.",
                    color = c.muted, style = Db.caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Db.s4))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Db.s3)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Speed test", onClick = onSpeedTest) }
                Box(Modifier.weight(1f)) { SecondaryButton("Report a problem", onClick = onReport) }
            }
            Spacer(Modifier.height(120.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.bg, 1f to c.bg))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = Db.gutter, end = Db.gutter, top = 28.dp, bottom = Db.s4),
        ) {
            MainButton(
                text = if (running) "Checking…" else "Check again",
                icon = R.drawable.ic_restart, color = c.wifi, enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { runId++ }
        }
    }
}

private fun signalWord(dbm: Int, wifi: Boolean): String = if (wifi) when {
    dbm >= -55 -> "Excellent"; dbm >= -67 -> "Good"; dbm >= -75 -> "Fair"; else -> "Weak"
} else when {
    dbm >= -85 -> "Excellent"; dbm >= -95 -> "Good"; dbm >= -105 -> "Fair"; else -> "Weak"
}

@Composable
private fun LinkSection(
    name: String, color: Color, d: LinkDiag, wifi: Boolean,
    showTypeAction: Boolean = false, onShowType: () -> Unit = {},
) {
    val c = Db.colors
    SectionHeader(name)
    SettingsGroup {
        val status = when {
            !d.present -> "Not connected"
            !d.internet -> "Connected, no internet"
            else -> "Working"
        }
        Stat("Status", status, if (d.present && d.internet) c.good else if (d.present) c.mobile else c.muted, dot = true)
        RowDivider(inset = false)
        Stat("Ping", d.pingMs?.let { "$it ms" })
        RowDivider(inset = false)
        Stat("Jitter", d.jitterMs?.let { "±$it ms" })
        RowDivider(inset = false)
        Stat("Failed attempts", d.lossPercent?.let { "$it%" })
        RowDivider(inset = false)
        Stat("Signal", d.signalDbm?.let { "${signalWord(it, wifi)} · $it dBm" })
        if (wifi) {
            RowDivider(inset = false)
            Stat("Band", d.band)
            RowDivider(inset = false)
            Stat("Link speed", d.linkSpeedMbps?.let { "$it Mbps" })
            RowDivider(inset = false)
            Stat("Standard", d.standard)
        } else {
            RowDivider(inset = false)
            if (showTypeAction) {
                SettingRow("Network", "Showing 4G/5G needs phone-state access") {
                    SmallPill("Show", onClick = onShowType)
                }
            } else Stat("Network", d.networkType)
            RowDivider(inset = false)
            Stat("Operator", d.operator)
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, valueColor: Color? = null, dot: Boolean = false) {
    val c = Db.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.muted, style = Db.small, modifier = Modifier.weight(1f))
        if (dot && valueColor != null) { Dot(valueColor, 8.dp); Spacer(Modifier.width(8.dp)) }
        Text(value ?: "—", color = if (value == null) c.muted else c.ink, style = Db.small, maxLines = 1)
    }
}
