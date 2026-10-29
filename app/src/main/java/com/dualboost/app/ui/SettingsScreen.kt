package com.dualboost.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dualboost.app.BuildConfig
import com.dualboost.app.R
import com.dualboost.app.core.BoostMode
import com.dualboost.app.core.SettingsSnapshot
import com.dualboost.app.core.SpeedUnit
import com.dualboost.app.update.UpdatePhase
import com.dualboost.app.update.UpdateUi

val CAP_OPTIONS = listOf(0, 500, 1000, 2000, 5000)
private fun capLabel(mb: Int) = when (mb) { 0 -> "Off"; 500 -> "500 MB"; else -> "${mb / 1000} GB" }

@Composable
fun SettingsScreen(
    s: SettingsSnapshot,
    ui: com.dualboost.app.core.BoostUi,
    batteryUnrestricted: Boolean,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenBond: () -> Unit,
    onAlwaysOn: () -> Unit,
    onBattery: () -> Unit,
    onDeveloper: () -> Unit,
    update: UpdateUi,
    canInstall: Boolean,
    onUpdate: () -> Unit,
    onAllowInstall: () -> Unit,
) {
    val c = Db.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Db.gutter),
    ) {
        TopBar("Settings", onBack)

        if (update.phase == UpdatePhase.AVAILABLE) {
            Spacer(Modifier.height(Db.s3))
            UpdateNotice(update, canInstall, onUpdate, onAllowInstall)
        }

        // ---- Mode
        SectionHeader("Boost mode")
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Segmented(
                listOf("Combine", "Backup"),
                selected = if (s.mode == BoostMode.COMBINE) 0 else 1,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp),
            ) { i -> onChange { it.copy(mode = if (i == 0) BoostMode.COMBINE else BoostMode.BACKUP) } }
            Text(
                if (s.mode == BoostMode.COMBINE)
                    "Uses Wi-Fi and mobile data together. Each new connection goes to whichever link is faster right now."
                else
                    "Uses Wi-Fi only. If Wi-Fi drops or stops working, mobile data takes over instantly.",
                color = c.muted, style = Db.small,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            )
        }

        // ---- Bond server
        SectionHeader("Bond server")
        SettingsGroup {
            val cfg = com.dualboost.app.core.BondConfig.state.collectAsState().value
            val b = ui.bond
            ChevronRow(
                "Bond server",
                when {
                    !cfg.configured -> "Free server that joins both links into one connection"
                    !cfg.enabled -> "Off · uses normal combining"
                    ui.phase != com.dualboost.app.core.Phase.ON -> "On · connects when the boost starts"
                    b.status == com.dualboost.app.core.BondStatus.CONNECTED -> "Connected · ${if (b.pingMs >= 0) "${b.pingMs} ms" else "both links joined"}"
                    b.status == com.dualboost.app.core.BondStatus.UNREACHABLE -> "Not answering · using normal combining"
                    else -> "Connecting…"
                },
                R.drawable.ic_server, c.purple,
                value = when { !cfg.configured -> "Set up"; cfg.enabled -> "On"; else -> "Off" },
                onClick = onOpenBond,
            )
        }

        // ---- Mobile limit
        SectionHeader("Daily mobile data limit")
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
                ChipRow(CAP_OPTIONS.map(::capLabel), CAP_OPTIONS.indexOf(s.mobileCapMb).coerceAtLeast(0)) { i ->
                    onChange { it.copy(mobileCapMb = CAP_OPTIONS[i]) }
                }
            }
            Text(
                "After the limit, mobile data is only used if Wi-Fi fails. Resets at midnight.",
                color = c.muted, style = Db.small,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            )
        }

        // ---- Display
        SectionHeader("Speed unit")
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Segmented(
                SpeedUnit.entries.map { it.label },
                selected = s.speedUnit.ordinal,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp),
            ) { i -> onChange { it.copy(speedUnit = SpeedUnit.entries[i]) } }
            Text(
                when (s.speedUnit) {
                    SpeedUnit.MBPS -> "Speeds show in megabits per second, e.g. 78.4 Mbps."
                    SpeedUnit.KBPS -> "Speeds show in kilobits per second, e.g. 78,400 Kbps."
                    SpeedUnit.AUTO -> "Mbps normally, switching to Kbps when speed is below 1 Mbps."
                },
                color = c.muted, style = Db.small,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            )
        }

        // ---- Behaviour
        SectionHeader("Behaviour")
        SettingsGroup {
            SwitchRow(
                "Treat boost as Wi-Fi", "Apps skip their mobile data-saver limits",
                R.drawable.ic_wifi, c.wifi, s.treatAsWifi,
            ) { v -> onChange { it.copy(treatAsWifi = v) } }
            RowDivider()
            SwitchRow(
                "Keep sites on one link", "Fixes sites that keep logging you out",
                R.drawable.ic_pin, c.purple, s.stickySites,
            ) { v -> onChange { it.copy(stickySites = v) } }
            RowDivider()
            SwitchRow(
                "Use IPv6", "Only when both Wi-Fi and mobile support it",
                R.drawable.ic_globe, c.good, s.ipv6,
            ) { v -> onChange { it.copy(ipv6 = v) } }
            RowDivider()
            SwitchRow(
                "Start after reboot", "Turns the boost back on if it was on",
                R.drawable.ic_restart, c.mobile, s.autoStart,
            ) { v -> onChange { it.copy(autoStart = v) } }
            RowDivider()
            SwitchRow(
                "Low-ping mode", "Games and calls stay on the link with the lowest ping",
                R.drawable.ic_speed, c.purple, s.lowPingMode,
            ) { v -> onChange { it.copy(lowPingMode = v) } }
            RowDivider()
            val ruled = s.appRules.count { it.value != com.dualboost.app.core.AppRule.BOTH } + s.excludedApps.size
            ChevronRow(
                "App rules", "Both links, low ping, Wi-Fi only, mobile, or bypass per app",
                R.drawable.ic_apps, c.warn,
                value = if (ruled == 0) "Default" else "$ruled set",
                onClick = onOpenApps,
            )
        }

        // ---- Ad blocking (DNS + AdGuard app)
        AdBlockSection(s, ui, onChange)

        // ---- Updates
        SectionHeader("Updates")
        SettingsGroup {
            ChevronRow(
                "Check for updates",
                "${s.updateCheck.shortLabel()} · checked ${ago(update.lastChecked)}",
                R.drawable.ic_update, c.mobile,
                onClick = onOpenUpdates,
            )
            RowDivider()
            SwitchRow(
                "Install automatically", "No prompt after the first update",
                R.drawable.ic_download, c.purple, s.autoUpdate,
            ) { v -> onChange { it.copy(autoUpdate = v) } }
        }

        // ---- Phone setup
        SectionHeader("Phone setup")
        SettingsGroup {
            ChevronRow(
                "Battery: unrestricted",
                if (batteryUnrestricted) "Done. Android won't pause DualBoost" else "Recommended so Android never pauses the boost",
                R.drawable.ic_battery, c.good,
                value = if (batteryUnrestricted) "On" else "Off",
                onClick = onBattery,
            )
            RowDivider()
            ChevronRow(
                "Always-on VPN", "Optional: Android keeps DualBoost running",
                R.drawable.ic_shield, c.wifi, onClick = onAlwaysOn,
            )
            RowDivider()
            ChevronRow(
                "Mobile data always active", "Developer option that keeps mobile data ready",
                R.drawable.ic_mobile, c.mobile, onClick = onDeveloper,
            )
        }

        // ---- Help
        SectionHeader("Help")
        SettingsGroup {
            ChevronRow(
                "Diagnostics", "Ping, signal and a plain answer to \"why is it slow?\"",
                R.drawable.ic_info, c.wifi, onClick = onOpenDiagnostics,
            )
            RowDivider()
            ChevronRow(
                "Report a problem", "Sends a ready-filled report with logs to GitHub",
                R.drawable.ic_bug, c.mobile, onClick = onOpenReport,
            )
        }

        // ---- About
        SectionHeader("How it works")
        DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
            Text(
                "DualBoost runs a private VPN on your phone only. No outside server is used. " +
                    "Each connection an app opens is sent over Wi-Fi or mobile data, so busy apps use both at once.\n\n" +
                    "Downloads, app updates, backups and multi-connection speed tests reach the combined speed. " +
                    "A single stream, such as one video, stays on one link.",
                color = c.muted, style = Db.small,
            )
        }

        Spacer(Modifier.height(Db.s6))
        Text(
            "DualBoost ${BuildConfig.VERSION_NAME}",
            color = c.muted, style = Db.caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}
