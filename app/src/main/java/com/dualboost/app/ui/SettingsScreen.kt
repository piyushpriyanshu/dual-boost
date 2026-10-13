package com.dualboost.app.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.BuildConfig
import com.dualboost.app.R
import com.dualboost.app.core.BoostMode
import com.dualboost.app.core.SettingsSnapshot
import com.dualboost.app.update.UpdateUi

val CAP_OPTIONS = listOf(0, 500, 1000, 2000, 5000)
private fun capLabel(mb: Int) = when (mb) { 0 -> "Off"; 500 -> "500 MB"; else -> "${mb / 1000} GB" }

@Composable
fun SettingsScreen(
    s: SettingsSnapshot,
    running: Boolean,
    batteryUnrestricted: Boolean,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onRestart: () -> Unit,
    onAlwaysOn: () -> Unit,
    onBattery: () -> Unit,
    onDeveloper: () -> Unit,
    update: UpdateUi,
    canInstall: Boolean,
    selfInstalled: Boolean,
    onCheckNow: () -> Unit,
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

        if (running) {
            Spacer(Modifier.height(Db.s4))
            DbCard(Modifier.fillMaxWidth(), onClick = onRestart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(c.accent)
                    Spacer(Modifier.width(Db.s3))
                    Column(Modifier.weight(1f)) {
                        Text("Restart to apply", color = c.ink, style = Db.body, fontWeight = FontWeight.SemiBold)
                        Text("Changes marked * take effect after a restart.", color = c.muted, style = Db.bodySmall)
                    }
                    Text("RESTART", color = c.accent, style = Db.label)
                }
            }
        }

        // ---- How to combine
        SectionTitle("How to combine")
        DbCard(Modifier.fillMaxWidth()) {
            Segmented(
                listOf("Combine", "Backup"),
                selected = if (s.mode == BoostMode.COMBINE) 0 else 1,
            ) { i -> onChange { it.copy(mode = if (i == 0) BoostMode.COMBINE else BoostMode.BACKUP) } }
            Spacer(Modifier.height(Db.s3))
            Text(
                if (s.mode == BoostMode.COMBINE)
                    "Uses Wi-Fi and mobile data together. Each new connection goes to whichever link will be faster right now."
                else
                    "Uses Wi-Fi only. If Wi-Fi drops or stops working, mobile data takes over instantly so nothing disconnects.",
                color = c.muted, style = Db.bodySmall,
            )
        }

        // ---- Mobile data limit
        SectionTitle("Daily mobile data limit")
        DbCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CAP_OPTIONS.forEach { mb ->
                    val sel = s.mobileCapMb == mb
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (sel) c.ink else c.surfaceHigh)
                            .clickable { onChange { it.copy(mobileCapMb = mb) } }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            capLabel(mb), color = if (sel) c.bg else c.ink,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Db.s3))
            Text(
                "After the limit, mobile data is only used if Wi-Fi fails. Resets every midnight.",
                color = c.muted, style = Db.bodySmall,
            )
        }

        // ---- Behaviour
        SectionTitle("Behaviour")
        SettingsGroup {
            SwitchRow(
                "Treat boost as Wi-Fi *",
                "Apps like Play Store and YouTube behave as if on Wi-Fi (no data-saver limits).",
                s.treatAsWifi,
            ) { v -> onChange { it.copy(treatAsWifi = v) } }
            Hairline()
            SwitchRow(
                "Keep each website on one link",
                "Turn on if a banking or shopping site keeps logging you out.",
                s.stickySites,
            ) { v -> onChange { it.copy(stickySites = v) } }
            Hairline()
            SwitchRow(
                "Use IPv6 *",
                "Only when both Wi-Fi and mobile support it.",
                s.ipv6,
            ) { v -> onChange { it.copy(ipv6 = v) } }
            Hairline()
            SwitchRow(
                "Start after reboot",
                "Turns the boost back on when your phone restarts, if it was on.",
                s.autoStart,
            ) { v -> onChange { it.copy(autoStart = v) } }
            Hairline()
            ChevronRow(
                "Excluded apps *",
                "These apps skip DualBoost and use your normal connection.",
                value = if (s.excludedApps.isEmpty()) "None" else "${s.excludedApps.size}",
                onClick = onOpenApps,
            )
        }

        // ---- Phone setup
        SectionTitle("Phone setup")
        SettingsGroup {
            ChevronRow(
                "Battery: unrestricted",
                if (batteryUnrestricted) "Done. Android won't pause DualBoost."
                else "Recommended so Android never pauses the boost.",
                value = if (batteryUnrestricted) "On" else "Off",
                onClick = onBattery,
            )
            Hairline()
            ChevronRow(
                "Always-on VPN",
                "Optional: let Android keep DualBoost running at all times.",
                onClick = onAlwaysOn,
            )
            Hairline()
            ChevronRow(
                "Mobile data always active",
                "Recommended (Developer options): keeps mobile data ready next to Wi-Fi.",
                onClick = onDeveloper,
            )
        }

        // ---- Updates
        UpdatesSection(
            u = update,
            check = s.updateCheck,
            autoUpdate = s.autoUpdate,
            canInstall = canInstall,
            selfInstalled = selfInstalled,
            onCheckChange = { v -> onChange { it.copy(updateCheck = v) } },
            onAutoChange = { v -> onChange { it.copy(autoUpdate = v) } },
            onCheckNow = onCheckNow,
            onUpdate = onUpdate,
            onAllow = onAllowInstall,
        )

        // ---- About
        SectionTitle("How it works")
        DbCard(Modifier.fillMaxWidth()) {
            Text(
                "DualBoost runs a private VPN on your phone only. Nothing leaves your phone except your normal traffic, and no outside server is used. " +
                    "Each connection an app opens is sent over Wi-Fi or mobile data, so busy apps use both at once.\n\n" +
                    "Downloads, app updates, cloud backups and multi-connection speed tests reach the combined speed. " +
                    "A single stream (one video or one game) stays on one link, so it gets that link's speed.",
                color = c.muted, style = Db.bodySmall,
            )
        }

        Spacer(Modifier.height(Db.s6))
        Text(
            "DUALBOOST ${BuildConfig.VERSION_NAME}",
            color = c.muted, style = Db.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    val c = Db.colors
    Row(
        Modifier.fillMaxWidth().padding(top = Db.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.surface).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_back), "Back", tint = c.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, color = c.ink, style = Db.title)
        Spacer(Modifier.weight(1f))
        // Mirror of the back button keeps the title optically centred.
        Spacer(Modifier.size(48.dp))
    }
}
