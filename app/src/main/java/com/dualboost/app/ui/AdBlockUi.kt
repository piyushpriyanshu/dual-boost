package com.dualboost.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.AddOns
import com.dualboost.app.core.DnsMode
import com.dualboost.app.core.Phase
import com.dualboost.app.core.ProxyStatus
import com.dualboost.app.core.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun DnsMode.subtitle() = when (this) {
    DnsMode.STANDARD -> "Cloudflare and Google. No blocking."
    DnsMode.ADGUARD -> "Blocks ads and trackers in all apps"
    DnsMode.ADGUARD_FAMILY -> "Also blocks adult sites, enforces Safe Search"
    DnsMode.CUSTOM -> "Use your own DNS servers"
}

/** Live check of the AdGuard local proxy while the setting is visible. */
@Composable
private fun rememberProxyDetected(enabled: Boolean, port: Int): Boolean? {
    var detected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(enabled, port) {
        if (!enabled) { detected = null; return@LaunchedEffect }
        while (true) {
            detected = withContext(Dispatchers.IO) { AddOns.proxyReachable(port) }
            delay(2_000)
        }
    }
    return detected
}

/** Small inline text input with a Save action, used for custom DNS and the proxy port. */
@Composable
private fun InlineInput(
    initial: String,
    placeholder: String,
    numeric: Boolean,
    valid: (String) -> Boolean,
    onSave: (String) -> Unit,
) {
    val c = Db.colors
    var text by remember(initial) { mutableStateOf(initial) }
    val changed = text.trim() != initial.trim()
    val ok = valid(text.trim())
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) Text(placeholder, color = c.muted, fontSize = 14.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = if (numeric) it.filter(Char::isDigit).take(5) else it.take(120) },
                singleLine = true,
                textStyle = TextStyle(color = c.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(Db.s3))
        if (changed) SmallPill(if (ok) "Save" else "Invalid", filled = ok) { if (ok) onSave(text.trim()) }
    }
}

/** "Ad blocking" section of Settings: DNS choice + AdGuard app chaining. */
@Composable
fun AdBlockSection(
    s: SettingsSnapshot,
    ui: BoostUi,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
) {
    val c = Db.colors
    val context = LocalContext.current
    val adguardPkg = remember { AddOns.adguardInstalled(context) }
    val detected = rememberProxyDetected(s.adguardProxy, s.adguardPort)

    SectionHeader("Ad blocking · DNS")
    SettingsGroup {
        DnsMode.entries.forEachIndexed { i, mode ->
            if (i > 0) RowDivider(inset = false)
            RadioRow(mode.label, mode.subtitle(), selected = s.dnsMode == mode) {
                onChange { it.copy(dnsMode = mode) }
            }
            if (mode == DnsMode.CUSTOM && s.dnsMode == DnsMode.CUSTOM) {
                InlineInput(
                    initial = s.customDns,
                    placeholder = "e.g. 9.9.9.9, 149.112.112.112",
                    numeric = false,
                    valid = { AddOns.parseDns(it).isNotEmpty() },
                ) { v -> onChange { it.copy(customDns = v) } }
            }
        }
    }

    SectionHeader("Ad blocking · AdGuard app")
    SettingsGroup {
        val status = when {
            !s.adguardProxy -> "Use AdGuard's full filtering together with DualBoost"
            ui.phase == Phase.ON && ui.proxy == ProxyStatus.ACTIVE -> "Active: apps go through AdGuard, then both links"
            detected == true -> "AdGuard proxy found on port ${s.adguardPort}"
            detected == false -> "AdGuard proxy not found on port ${s.adguardPort}. See steps below."
            else -> "Checking for AdGuard…"
        }
        SwitchRow(
            "Filter through AdGuard", status,
            R.drawable.ic_shield, c.good, s.adguardProxy,
        ) { v -> onChange { it.copy(adguardProxy = v) } }
        if (s.adguardProxy) {
            RowDivider()
            SettingRow("Proxy port", "Must match the port shown in AdGuard", R.drawable.ic_globe, c.wifi)
            InlineInput(
                initial = s.adguardPort.toString(),
                placeholder = "8080",
                numeric = true,
                valid = { it.toIntOrNull() in 1..65535 },
            ) { v -> onChange { it.copy(adguardPort = v.toInt()) } }
        }
        RowDivider()
        if (adguardPkg != null) {
            ChevronRow("Open AdGuard", "Switch its routing mode to Manual proxy", R.drawable.ic_shield, c.good) {
                AddOns.openAdguard(context)
            }
        } else {
            SettingRow("AdGuard isn't installed", "Install it from adguard.com, or use AdGuard DNS above", R.drawable.ic_info, c.warn)
        }
    }

    Spacer(Modifier.height(Db.s3))
    DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        Text("Set up AdGuard to work with DualBoost", color = c.ink, style = Db.rowTitle)
        Spacer(Modifier.height(Db.s2))
        Text(
            "1. In AdGuard, open Settings → Filtering → Network → Routing mode, and choose Manual proxy.\n" +
                "2. Note the proxy port AdGuard shows (usually 8080) and enter it above.\n" +
                "3. Turn on \"Filter through AdGuard\" here. DualBoost stays the VPN; AdGuard filters, then DualBoost combines.\n\n" +
                "Apps that ignore Android's proxy setting skip AdGuard's filtering. AdGuard DNS above still blocks their ads. " +
                "If AdGuard stops, DualBoost pauses filtering automatically so your internet keeps working.",
            color = c.muted, style = Db.small,
        )
    }
}

/** Home-screen summary of ad blocking, shown only when something is enabled. */
@Composable
fun ProtectionCard(s: SettingsSnapshot, ui: BoostUi, onOpen: () -> Unit) {
    val c = Db.colors
    if (s.dnsMode == DnsMode.STANDARD && !s.adguardProxy) return
    val on = ui.phase == Phase.ON
    val parts = buildList {
        if (s.dnsMode != DnsMode.STANDARD) add(if (s.dnsMode == DnsMode.CUSTOM) "Custom DNS" else "${s.dnsMode.label} DNS")
        if (s.adguardProxy) add(
            when {
                !on -> "AdGuard filtering"
                ui.proxy == ProxyStatus.ACTIVE -> "AdGuard filtering on"
                else -> "AdGuard not detected"
            }
        )
    }
    val warn = on && s.adguardProxy && ui.proxy != ProxyStatus.ACTIVE
    Column {
        Spacer(Modifier.height(Db.s3))
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp, onClick = onOpen) {
            ChevronRow(
                "Ad blocking",
                parts.joinToString(" · "),
                R.drawable.ic_shield, if (warn) c.warn else c.good,
                value = if (!on) "Ready" else if (warn) "Paused" else "On",
                onClick = onOpen,
            )
        }
    }
}
