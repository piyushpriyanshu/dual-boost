package com.dualboost.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

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
fun AppsScreen(excluded: Set<String>, onToggle: (String, Boolean) -> Unit, onBack: () -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // Keep the original order stable while toggling (excluded apps first, decided once on open).
    val pinned = remember { excluded.toSet() }

    Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(Modifier.padding(horizontal = Db.gutter)) {
            TopBar("Excluded apps", onBack)
            Spacer(Modifier.height(Db.s4))
            Text(
                "Excluded apps skip DualBoost and use your phone's normal connection. Useful for banking apps or anything that dislikes VPNs.",
                color = c.muted, style = Db.bodySmall,
            )
            Spacer(Modifier.height(Db.s4))
            // Search
            val shape = RoundedCornerShape(50)
            Box(
                Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
                    .padding(horizontal = Db.s5, vertical = 14.dp)
            ) {
                if (query.isEmpty()) Text("Search apps", color = c.muted, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Db.s3))
        }

        val list = apps
        if (list == null) {
            CenterBox(Modifier.fillMaxSize()) { Text("Loading apps…", color = c.muted, style = Db.bodySmall) }
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
                AppRow(app, app.pkg in excluded) { onToggle(app.pkg, it) }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Db.radiusSmall)).clickable { onChange(!checked) }
            .padding(horizontal = Db.s2, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, null, Modifier.size(40.dp)) }
        }
        Spacer(Modifier.width(Db.s4))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = c.ink, style = Db.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.pkg, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Db.s3))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.faint,
            ),
        )
    }
}
