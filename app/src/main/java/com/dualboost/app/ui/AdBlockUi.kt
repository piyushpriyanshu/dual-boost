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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.AddOns
import com.dualboost.app.core.DnsMode
import com.dualboost.app.core.Phase
import com.dualboost.app.core.SettingsSnapshot

private fun DnsMode.subtitle() = when (this) {
    DnsMode.STANDARD -> "Cloudflare and Google. No blocking."
    DnsMode.ADGUARD -> "Blocks ads and trackers in all apps"
    DnsMode.ADGUARD_FAMILY -> "Also blocks adult sites, enforces Safe Search"
    DnsMode.CUSTOM -> "Use your own DNS servers"
}

/** Small inline text input with a Save action, used for custom DNS. */
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

/** "Ad blocking" section of Settings: DNS choice (AdGuard DNS blocks ads system-wide, no root needed). */
@Composable
fun AdBlockSection(
    s: SettingsSnapshot,
    ui: BoostUi,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
) {
    val c = Db.colors
    SectionHeader("Ad blocking")
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
    if (ui.phase == Phase.ON && ui.dnsInUse.isNotEmpty()) {
        Text(
            "In use: ${ui.dnsInUse.take(2).joinToString(", ")}",
            color = c.muted, style = Db.caption,
            modifier = Modifier.padding(start = Db.s1, top = Db.s2),
        )
    }
    Spacer(Modifier.height(Db.s3))
    DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        Text(
            "AdGuard DNS blocks ads and trackers in every app, needs no root and no extra app. " +
                "The AdGuard app itself can't run alongside DualBoost, because Android allows only one VPN at a time.",
            color = c.muted, style = Db.small,
        )
    }
}

/** Home-screen summary of ad blocking, shown only when an ad-blocking DNS is chosen. */
@Composable
fun ProtectionCard(s: SettingsSnapshot, ui: BoostUi, onOpen: () -> Unit) {
    val c = Db.colors
    if (s.dnsMode == DnsMode.STANDARD) return
    val on = ui.phase == Phase.ON
    Column {
        Spacer(Modifier.height(Db.s3))
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp, onClick = onOpen) {
            ChevronRow(
                "Ad blocking",
                "${s.dnsMode.label} DNS",
                R.drawable.ic_shield, c.good,
                value = if (on) "On" else "Ready",
                onClick = onOpen,
            )
        }
    }
}
