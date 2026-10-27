package com.dualboost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualboost.app.R
import com.dualboost.app.core.Report

@Composable
fun ReportScreen(onBack: () -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Db.gutter),
    ) {
        TopBar("Report a problem", onBack)

        SectionHeader("What went wrong?")
        Box(
            Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(Db.radius))
                .background(c.surface).padding(18.dp),
        ) {
            if (text.isEmpty()) Text(
                "For example: speed dropped to 1 Mbps after turning on the boost, on home Wi-Fi.",
                color = c.muted, fontSize = 15.sp,
            )
            BasicTextField(
                value = text, onValueChange = { text = it.take(1_500) },
                textStyle = TextStyle(color = c.ink, fontSize = 15.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            )
        }

        SectionHeader("Included automatically")
        DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
            Text(
                "• App version, phone model and Android version\n" +
                    "• Wi-Fi and mobile status, speeds and ping\n" +
                    "• Your DualBoost settings and last speed test\n" +
                    "• Recent app events and the last crash, if any\n\n" +
                    "No websites, browsing history or personal data are included.",
                color = c.muted, style = Db.small,
            )
        }

        Spacer(Modifier.height(Db.s5))
        MainButton(
            text = "Send on GitHub", icon = R.drawable.ic_bug, color = c.wifi,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val ok = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Report.issueUrl(context, text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!ok) Toast.makeText(context, "No browser found to open GitHub.", Toast.LENGTH_LONG).show()
        }
        Spacer(Modifier.height(Db.s3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Db.s3)) {
            Box(Modifier.weight(1f)) {
                SecondaryButton("Copy full log") {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("DualBoost report", Report.full(context, text)))
                    Toast.makeText(context, "Full log copied", Toast.LENGTH_SHORT).show()
                }
            }
            Box(Modifier.weight(1f)) {
                SecondaryButton("Share log") {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "DualBoost problem report")
                        .putExtra(Intent.EXTRA_TEXT, Report.full(context, text))
                    runCatching { context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        Spacer(Modifier.height(Db.s4))
        Text(
            "Send on GitHub opens a ready-filled issue in your browser. Nothing is posted until you tap Submit there. " +
                "If the full log is needed, tap Copy full log and paste it as a comment.",
            color = c.muted, style = Db.caption,
        )
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}
