package com.dualboost.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualboost.app.R
import com.dualboost.app.core.DlStatus
import com.dualboost.app.core.DownloadUi
import com.dualboost.app.core.Downloads
import com.dualboost.app.core.SettingsSnapshot
import com.dualboost.app.service.DownloadService

@Composable
fun DownloadsScreen(
    s: SettingsSnapshot,
    prefillUrl: String?,
    onPrefillUsed: () -> Unit,
    onChange: ((SettingsSnapshot) -> SettingsSnapshot) -> Unit,
    onBack: () -> Unit,
) {
    val c = Db.colors
    val context = LocalContext.current
    val items by Downloads.items.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) { url = prefillUrl; onPrefillUsed() }
    }

    fun start() {
        val link = Downloads.extractUrl(url.trim())
        if (link == null) {
            Toast.makeText(context, "That doesn't look like a download link.", Toast.LENGTH_SHORT).show()
            return
        }
        DownloadService.start(context, link)
        url = ""
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Db.gutter),
    ) {
        TopBar("Downloads", onBack)

        // ---- New download
        SectionHeader("Download faster with both links")
        DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (url.isEmpty()) Text("Paste a download link", color = c.muted, fontSize = 15.sp)
                BasicTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp), cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { start() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Db.s3))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton("Paste") {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        val link = Downloads.extractUrl(text)
                        if (link != null) url = link
                        else Toast.makeText(context, "No link on the clipboard.", Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.width(Db.s3))
                Box(Modifier.weight(1f)) {
                    MainButton("Download", R.drawable.ic_download, c.wifi, Modifier.fillMaxWidth().height(52.dp)) { start() }
                }
            }
            Spacer(Modifier.height(Db.s3))
            Text(
                "Tip: in Chrome or any app, long-press a download link → Share → DualBoost.",
                color = c.muted, style = Db.caption,
            )
        }
        Spacer(Modifier.height(Db.s3))
        SettingsGroup {
            SwitchRow(
                "Use mobile data for downloads",
                if (s.mobileCapMb > 0) "Stops at your daily mobile limit" else "Turn off to download over Wi-Fi only",
                R.drawable.ic_mobile, c.mobile, s.downloadsUseMobile,
            ) { v -> onChange { it.copy(downloadsUseMobile = v) } }
        }

        // ---- Active
        val active = items.filter { it.active }
        if (active.isNotEmpty()) {
            SectionHeader("In progress")
            active.forEach { d ->
                ActiveCard(d) { DownloadService.cancel(context, d.id) }
                Spacer(Modifier.height(Db.s3))
            }
        }

        // ---- Recent
        val recent = items.filter { !it.active }
        if (recent.isNotEmpty()) {
            SectionHeader("Recent")
            SettingsGroup {
                recent.forEachIndexed { i, d ->
                    if (i > 0) RowDivider(inset = false)
                    RecentRow(d) {
                        if (d.status == DlStatus.DONE && d.uri != null) {
                            val ok = runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.uri), d.mime ?: "*/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.isSuccess
                            if (!ok) Toast.makeText(context, "No app can open this file. It's in Downloads/DualBoost.", Toast.LENGTH_LONG).show()
                        } else if (d.status != DlStatus.DONE) {
                            url = d.url // retry: put the link back in the box
                        }
                    }
                }
            }
            Spacer(Modifier.height(Db.s2))
            Box(Modifier.fillMaxWidth()) {
                SecondaryButton("Clear list") { Downloads.clearHistory(context) }
            }
        }
        if (active.isEmpty() && recent.isEmpty()) {
            Spacer(Modifier.height(Db.s3))
            DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                Text(
                    "The file is split into pieces fetched over Wi-Fi and mobile data at the same time, so even a single download " +
                        "gets the combined speed. Files are saved to Downloads/DualBoost. Works whether the boost is on or off.",
                    color = c.muted, style = Db.small,
                )
            }
        }
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/** Progress bar showing how much came over Wi-Fi (blue) and mobile (orange). */
@Composable
private fun SplitProgress(d: DownloadUi) {
    val c = Db.colors
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(c.faint, cornerRadius = r)
        if (d.size <= 0) return@Canvas
        val wf = (d.wifiBytes.toFloat() / d.size).coerceIn(0f, 1f)
        val mf = (d.mobileBytes.toFloat() / d.size).coerceIn(0f, 1f - wf)
        val total = (wf + mf) * size.width
        if (total <= 0f) return@Canvas
        // Draw the combined bar with round ends, then the mobile share as the right-hand part.
        drawRoundRect(c.mobile, size = Size(total, size.height), cornerRadius = r)
        if (wf > 0f) drawRoundRect(c.wifi, size = Size(wf * size.width, size.height), cornerRadius = r)
        if (wf > 0f && mf > 0f) {
            // Square off the join so the two colours meet cleanly.
            val x = wf * size.width
            drawRect(c.wifi, topLeft = Offset((x - size.height / 2).coerceAtLeast(0f), 0f), size = Size(size.height / 2, size.height))
        }
    }
}

@Composable
private fun ActiveCard(d: DownloadUi, onCancel: () -> Unit) {
    val c = Db.colors
    val unit = LocalSpeedUnit.current
    DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_download, c.wifi, size = 36.dp, corner = 11.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, color = c.ink, style = Db.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        d.status == DlStatus.PREPARING -> "Connecting…"
                        d.size > 0 -> "${formatBytes(d.done)} of ${formatBytes(d.size)} · ${(d.fraction * 100).toInt()}%"
                        else -> formatBytes(d.done)
                    },
                    color = c.muted, style = Db.small,
                )
            }
            Spacer(Modifier.width(Db.s3))
            SmallPill("Cancel", filled = false, onClick = onCancel)
        }
        Spacer(Modifier.height(14.dp))
        SplitProgress(d)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatSpeed(d.totalMbps, unit).toString(), color = c.ink, style = Db.small, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (d.split) {
                LegendItem(c.wifi, formatSpeed(d.wifiMbps, unit).toString())
                Spacer(Modifier.width(Db.s3))
                LegendItem(c.mobile, formatSpeed(d.mobileMbps, unit).toString())
            } else if (d.status == DlStatus.DOWNLOADING) {
                Text("This server allows one connection only", color = c.muted, style = Db.caption)
            }
        }
    }
}

@Composable
private fun RecentRow(d: DownloadUi, onClick: () -> Unit) {
    val c = Db.colors
    val unit = LocalSpeedUnit.current
    val (sub, col) = when (d.status) {
        DlStatus.DONE -> "${formatBytes(d.done)} · average ${formatSpeed(d.avgMbps, unit)}" to c.good
        DlStatus.CANCELLED -> "Cancelled · tap to retry" to c.muted
        else -> "${d.error ?: "Failed"} · tap to retry" to c.mobile
    }
    SettingRow(d.name, sub, icon = R.drawable.ic_download, iconColor = col, onClick = onClick) {
        if (d.status == DlStatus.DONE && d.done > 0) {
            val wPct = (d.wifiBytes * 100 / d.done).toInt()
            Text("Wi-Fi $wPct%", color = c.muted, style = Db.caption)
        }
    }
}
