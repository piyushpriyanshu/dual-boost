package com.dualboost.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.dualboost.app.R
import com.dualboost.app.core.AppRule
import com.dualboost.app.core.LOW_PING_PRESETS
import com.dualboost.app.core.SettingsSnapshot
import com.dualboost.app.core.ruleFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

/** What a row can be set to: the four routing rules plus Bypass (= excluded from DualBoost). */
private sealed class Choice(val label: String, val detail: String) {
    class Rule(val rule: AppRule) : Choice(rule.label, rule.detail)
    object Bypass : Choice("Bypass DualBoost", "Uses your phone's normal connection (for banking apps etc.)")
}

private val CHOICES: List<Choice> = AppRule.entries.map { Choice.Rule(it) } + Choice.Bypass

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            AppEntry(pkg, label)
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppRulesScreen(
    s: SettingsSnapshot,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
    onBack: () -> Unit,
) {
    val c = Db.colors
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    // Apps with a rule are listed first; decided once on open so rows don't jump while editing.
    val pinned = remember { s.appRules.keys + s.excludedApps + LOW_PING_PRESETS }

    fun choiceOf(pkg: String): Choice =
        if (pkg in s.excludedApps) Choice.Bypass
        else CHOICES.filterIsInstance<Choice.Rule>().first { it.rule == s.ruleFor(pkg) }

    fun set(pkg: String, choice: Choice) = onChange { cur ->
        when (choice) {
            Choice.Bypass -> cur.copy(excludedApps = cur.excludedApps + pkg, appRules = cur.appRules - pkg)
            is Choice.Rule -> cur.copy(
                excludedApps = cur.excludedApps - pkg,
                appRules = if (choice.rule == AppRule.BOTH && pkg !in LOW_PING_PRESETS) cur.appRules - pkg
                else cur.appRules + (pkg to choice.rule),
            )
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.padding(horizontal = Db.gutter)) {
            TopBar("App rules", onBack)
            Spacer(Modifier.height(Db.s3))
            Text(
                "Choose how each app connects. Games and call apps use Low ping automatically while Low-ping mode is on.",
                color = c.muted, style = Db.small,
            )
            Spacer(Modifier.height(Db.s4))
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(shape).background(c.surface).padding(horizontal = Db.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = c.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Db.s3))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", color = c.muted, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontSize = 15.sp), cursorBrush = SolidColor(c.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(Db.s3))
        }

        val list = apps
        if (list == null) {
            CenterBox(Modifier.fillMaxSize()) { Text("Loading apps…", color = c.muted, style = Db.small) }
            return@Column
        }
        val shown = list
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .sortedBy { if (it.pkg in pinned) 0 else 1 }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Db.gutter, end = Db.gutter, top = Db.s2,
                bottom = Db.s8 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            items(shown, key = { it.pkg }) { app ->
                val choice = choiceOf(app.pkg)
                AppRow(
                    app = app,
                    choice = choice,
                    preset = app.pkg in LOW_PING_PRESETS,
                    open = expanded == app.pkg,
                    onToggle = { expanded = if (expanded == app.pkg) null else app.pkg },
                    onPick = { set(app.pkg, it); expanded = null },
                )
            }
        }
    }
}

@Composable
private fun choiceColor(choice: Choice): Color {
    val c = Db.colors
    return when (choice) {
        Choice.Bypass -> c.muted
        is Choice.Rule -> when (choice.rule) {
            AppRule.BOTH -> c.good
            AppRule.LOW_PING -> c.purple
            AppRule.WIFI_ONLY -> c.wifi
            AppRule.MOBILE -> c.mobile
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    choice: Choice,
    preset: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (Choice) -> Unit,
) {
    val c = Db.colors
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(18.dp))
            .background(if (open) c.surface else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(horizontal = Db.s2, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                icon?.let { Image(it, null, Modifier.size(40.dp)) }
            }
            Spacer(Modifier.width(Db.s4))
            Column(Modifier.weight(1f)) {
                Text(app.label, color = c.ink, style = Db.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (preset) "Game or call app" else app.pkg,
                    color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Db.s3))
            val col = choiceColor(choice)
            Row(
                Modifier.clip(CircleShape).background(col.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(col, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (choice is Choice.Bypass) "Bypass" else choice.label, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
                CHOICES.forEach { option ->
                    val selected = option.label == choice.label
                    RadioRow(option.label, option.detail, selected) { onPick(option) }
                }
            }
        }
    }
}
