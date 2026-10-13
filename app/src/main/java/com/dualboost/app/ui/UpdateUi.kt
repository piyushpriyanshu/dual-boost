package com.dualboost.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.BuildConfig
import com.dualboost.app.core.UpdateCheck
import com.dualboost.app.update.UpdatePhase
import com.dualboost.app.update.UpdateUi

private fun UpdateUi.statusText(canInstall: Boolean): String = when (phase) {
    UpdatePhase.IDLE -> "Not checked yet"
    UpdatePhase.CHECKING -> "Checking GitHub…"
    UpdatePhase.UP_TO_DATE -> "You're on the latest version"
    UpdatePhase.AVAILABLE -> if (canInstall) "Version ${info?.versionName} is available" else "Version ${info?.versionName} is available. Allow installs to update."
    UpdatePhase.DOWNLOADING -> "Downloading ${info?.versionName}… ${(progress * 100).toInt()}%"
    UpdatePhase.INSTALLING -> "Installing ${info?.versionName}… DualBoost will restart"
    UpdatePhase.WAITING_CONFIRM -> "Tap Update in Android's dialog to finish"
    UpdatePhase.ERROR -> message ?: "Something went wrong"
}

private fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

/** Compact banner on the home screen while an update is available or in progress. */
@Composable
fun UpdateBanner(u: UpdateUi, canInstall: Boolean, onUpdate: () -> Unit, onAllow: () -> Unit) {
    val c = Db.colors
    val busy = u.phase == UpdatePhase.DOWNLOADING || u.phase == UpdatePhase.INSTALLING
    DbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c.accent)
            Spacer(Modifier.width(Db.s3))
            Column(Modifier.weight(1f)) {
                Text("Update ${u.info?.versionName ?: ""}".trim(), color = c.ink, style = Db.body, fontWeight = FontWeight.SemiBold)
                val notes = u.info?.notes.orEmpty()
                Text(
                    if (notes.isNotBlank() && u.phase == UpdatePhase.AVAILABLE) notes
                    else u.statusText(canInstall),
                    color = c.muted, style = Db.bodySmall, maxLines = 2,
                )
            }
            if (!busy && u.phase != UpdatePhase.WAITING_CONFIRM) {
                Spacer(Modifier.width(Db.s3))
                SmallPill(if (canInstall) "Update" else "Allow", onClick = if (canInstall) onUpdate else onAllow)
            }
        }
        if (busy) {
            Spacer(Modifier.height(Db.s3))
            ProgressLine(if (u.phase == UpdatePhase.INSTALLING) 1f else u.progress, c.accent)
        }
    }
}

/** The "Updates" section of Settings. */
@Composable
fun UpdatesSection(
    u: UpdateUi,
    check: UpdateCheck,
    autoUpdate: Boolean,
    canInstall: Boolean,
    selfInstalled: Boolean,
    onCheckChange: (UpdateCheck) -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onUpdate: () -> Unit,
    onAllow: () -> Unit,
) {
    val c = Db.colors
    SectionTitle("Updates")
    // Status
    DbCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Installed ${BuildConfig.VERSION_NAME}")
                Spacer(Modifier.height(Db.s1))
                Text(u.statusText(canInstall), color = c.ink, style = Db.body, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("Last checked ${ago(u.lastChecked)}", color = c.muted, style = Db.bodySmall)
            }
            Spacer(Modifier.width(Db.s3))
            val busy = u.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)
            when {
                busy -> {}
                u.phase == UpdatePhase.AVAILABLE && !canInstall -> SmallPill("Allow", onClick = onAllow)
                u.phase == UpdatePhase.AVAILABLE || (u.phase == UpdatePhase.ERROR && u.info != null) -> SmallPill("Update", onClick = onUpdate)
                else -> SmallPill("Check now", filled = false, onClick = onCheckNow)
            }
        }
        if (u.phase == UpdatePhase.DOWNLOADING || u.phase == UpdatePhase.INSTALLING) {
            Spacer(Modifier.height(Db.s3))
            ProgressLine(if (u.phase == UpdatePhase.INSTALLING) 1f else u.progress, c.accent)
        }
    }

    Spacer(Modifier.height(Db.s3))
    // When to check
    SettingsGroup {
        Text(
            "CHECK FOR UPDATES", color = c.muted, style = Db.label,
            modifier = Modifier.padding(start = Db.s5, top = Db.s4, bottom = Db.s1),
        )
        UpdateCheck.entries.forEachIndexed { i, opt ->
            if (i > 0) Hairline()
            RadioRow(
                title = opt.label,
                subtitle = when (opt) {
                    UpdateCheck.ON_OPEN -> "Only checks when you open DualBoost"
                    UpdateCheck.MANUAL -> "Only when you tap Check now"
                    else -> "In the background, and when you open the app"
                },
                selected = opt == check,
            ) { onCheckChange(opt) }
        }
    }

    Spacer(Modifier.height(Db.s3))
    SettingsGroup {
        SwitchRow(
            "Install updates automatically",
            if (selfInstalled) "Downloads and installs new versions without asking."
            else "Downloads and installs new versions. Android asks to confirm the first time; after that it's automatic.",
            autoUpdate,
        ) { onAutoChange(it) }
        if (!canInstall) {
            Hairline()
            ChevronRow(
                "Allow installing updates",
                "Needed once so DualBoost can update itself.",
                value = "Off",
                onClick = onAllow,
            )
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = Db.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp).padding(horizontal = Db.s5, vertical = Db.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = Db.body, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.muted, style = Db.bodySmall)
        }
        Spacer(Modifier.width(Db.s4))
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(2.dp, if (selected) c.accent else c.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(c.accent))
        }
    }
}

@Composable
fun SmallPill(text: String, filled: Boolean = true, onClick: () -> Unit) {
    val c = Db.colors
    Box(
        Modifier.clip(CircleShape)
            .background(if (filled) c.accent else c.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) c.onAccent else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
