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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dualboost.app.BuildConfig
import com.dualboost.app.R
import com.dualboost.app.core.UpdateCheck
import com.dualboost.app.update.UpdatePhase
import com.dualboost.app.update.UpdateUi

fun UpdateUi.statusText(canInstall: Boolean): String = when (phase) {
    UpdatePhase.IDLE -> "Not checked yet"
    UpdatePhase.CHECKING -> "Checking for updates…"
    UpdatePhase.UP_TO_DATE -> "You're on the latest version"
    UpdatePhase.AVAILABLE -> if (canInstall) "Version ${info?.versionName} is available"
    else "Version ${info?.versionName} is available. Allow installs to update."
    UpdatePhase.DOWNLOADING -> "Downloading ${info?.versionName}… ${(progress * 100).toInt()}%"
    UpdatePhase.INSTALLING -> "Installing ${info?.versionName}. DualBoost will restart."
    UpdatePhase.WAITING_CONFIRM -> "Tap Update in Android's dialog to finish"
    UpdatePhase.ERROR -> message ?: "Something went wrong"
}

fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

private val UpdatePhase.busy get() = this == UpdatePhase.CHECKING || this == UpdatePhase.DOWNLOADING || this == UpdatePhase.INSTALLING

/** Home-screen notice while an update is available or in progress. */
@Composable
fun UpdateNotice(u: UpdateUi, canInstall: Boolean, onUpdate: () -> Unit, onAllow: () -> Unit) {
    val c = Db.colors
    val notes = u.info?.notes.orEmpty()
    val showAction = !u.phase.busy && u.phase != UpdatePhase.WAITING_CONFIRM
    NoticeCard(
        title = "Update ${u.info?.versionName ?: ""}".trim(),
        text = if (notes.isNotBlank() && u.phase == UpdatePhase.AVAILABLE) notes else u.statusText(canInstall),
        color = c.primary,
        icon = R.drawable.ic_download,
        action = if (!showAction) null else if (canInstall) "Update" else "Allow",
        onAction = if (canInstall) onUpdate else onAllow,
        trailing = if (u.phase == UpdatePhase.DOWNLOADING || u.phase == UpdatePhase.INSTALLING) {
            {
                Spacer(Modifier.height(14.dp))
                ProgressLine(if (u.phase == UpdatePhase.INSTALLING) 1f else u.progress, c.primary)
            }
        } else null,
    )
}

/** Full "Check for updates" screen: how often + status. */
@Composable
fun UpdatesScreen(
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
    onBack: () -> Unit,
) {
    val c = Db.colors
    Column(
        Modifier.fillMaxSize().background(c.bg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Db.gutter),
    ) {
        TopBar("Check for updates", onBack)

        SectionHeader("Status")
        SettingsGroup {
            SettingRow(
                title = u.statusText(canInstall),
                subtitle = "Installed ${BuildConfig.VERSION_NAME} · checked ${ago(u.lastChecked)}",
                icon = R.drawable.ic_update,
                iconColor = c.mobile,
            ) {
                when {
                    u.phase.busy || u.phase == UpdatePhase.WAITING_CONFIRM -> {}
                    (u.phase == UpdatePhase.AVAILABLE || (u.phase == UpdatePhase.ERROR && u.info != null)) && !canInstall ->
                        SmallPill("Allow", onClick = onAllow)
                    u.phase == UpdatePhase.AVAILABLE || (u.phase == UpdatePhase.ERROR && u.info != null) ->
                        SmallPill("Update", onClick = onUpdate)
                    else -> SmallPill("Check now", filled = false, onClick = onCheckNow)
                }
            }
            if (u.phase == UpdatePhase.DOWNLOADING || u.phase == UpdatePhase.INSTALLING) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                    ProgressLine(if (u.phase == UpdatePhase.INSTALLING) 1f else u.progress, c.primary)
                }
            }
        }

        SectionHeader("How often")
        SettingsGroup {
            UpdateCheck.entries.forEachIndexed { i, opt ->
                if (i > 0) RowDivider(inset = false)
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

        SectionHeader("Installing")
        SettingsGroup {
            SwitchRow(
                "Install automatically",
                if (selfInstalled) "New versions install without asking"
                else "Android asks once to confirm. After that, updates are automatic.",
                R.drawable.ic_download, c.purple, autoUpdate, onAutoChange,
            )
            if (!canInstall) {
                RowDivider()
                ChevronRow(
                    "Allow installing updates",
                    "Needed once so DualBoost can update itself",
                    R.drawable.ic_shield, c.good, value = "Off", onClick = onAllow,
                )
            }
        }
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

fun UpdateCheck.shortLabel(): String = when (this) {
    UpdateCheck.ON_OPEN -> "When opened"
    UpdateCheck.EVERY_1H -> "Every hour"
    UpdateCheck.EVERY_3H -> "Every 3 hours"
    UpdateCheck.EVERY_6H -> "Every 6 hours"
    UpdateCheck.MANUAL -> "Manual"
}
