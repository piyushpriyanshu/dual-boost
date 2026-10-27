package com.dualboost.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualboost.app.R
import com.dualboost.app.core.SpeedResult
import com.dualboost.app.core.SpeedTest
import com.dualboost.app.core.TestStage
import java.util.Locale

@Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    val unit = LocalSpeedUnit.current
    val st by SpeedTest.ui.collectAsStateWithLifecycle()
    val saved = remember(st.stage) { SpeedTest.lastResult(context) }
    val result: SpeedResult? = if (st.stage == TestStage.IDLE) saved else st.partial ?: saved

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Db.gutter),
        ) {
            TopBar("Speed test", onBack)
            Spacer(Modifier.height(Db.s3))

            // ---- Hero: live number while running, combined result when done
            DbCard(Modifier.fillMaxWidth(), padding = Db.s6) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (pill, dot) = when {
                        st.running -> st.stage.label to c.warn
                        st.stage == TestStage.ERROR -> "Test failed" to c.mobile
                        result != null -> "Last test · ${ago(result.at)}" to c.good
                        else -> "Not tested yet" to c.muted
                    }
                    StatusPill(pill, dot)
                    Spacer(Modifier.height(Db.s6))
                    val headline: Double? = when {
                        st.running && st.stage != TestStage.PREPARING && st.stage != TestStage.PING -> st.liveMbps
                        st.running -> null
                        else -> result?.combinedDown ?: result?.wifi?.downMbps ?: result?.mobile?.downMbps
                    }
                    if (headline != null) {
                        val sp = formatSpeed(headline, unit)
                        Text(sp.value, color = c.ink, style = Db.display.fitted(sp.value, 4, 0.66f), maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                st.running -> "${sp.unit} · ${st.stage.label.lowercase(Locale.US)}"
                                result?.combinedDown != null -> "${sp.unit} combined download"
                                else -> "${sp.unit} download"
                            },
                            color = c.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(if (st.running) "…" else "—", color = c.muted, style = Db.displayOff)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (st.running) st.stage.label else "Tests Wi-Fi, mobile, and both together",
                            color = c.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(Db.s5))
                    ProgressLine(if (st.running) st.progress else if (st.stage == TestStage.DONE) 1f else 0f, c.wifi)
                }
            }

            // ---- Results table
            Spacer(Modifier.height(Db.s3))
            DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                ResultRow(header = true, label = "", color = null, down = "Download", up = "Upload", ping = "Ping")
                RowDivider(inset = false)
                ResultRow(
                    label = "Wi-Fi", color = c.wifi,
                    down = result?.wifi?.downMbps?.let { formatSpeed(it, unit).toString() },
                    up = result?.wifi?.upMbps?.let { formatSpeed(it, unit).toString() },
                    ping = result?.wifi?.pingMs?.let { "$it ms" },
                    active = st.stage == TestStage.WIFI_DOWN || st.stage == TestStage.WIFI_UP,
                )
                RowDivider(inset = false)
                ResultRow(
                    label = "Mobile", color = c.mobile,
                    down = result?.mobile?.downMbps?.let { formatSpeed(it, unit).toString() },
                    up = result?.mobile?.upMbps?.let { formatSpeed(it, unit).toString() },
                    ping = result?.mobile?.pingMs?.let { "$it ms" },
                    active = st.stage == TestStage.MOBILE_DOWN || st.stage == TestStage.MOBILE_UP,
                )
                RowDivider(inset = false)
                ResultRow(
                    label = "Combined", color = null, bold = true,
                    down = result?.combinedDown?.let { formatSpeed(it, unit).toString() },
                    up = result?.combinedUp?.let { formatSpeed(it, unit).toString() },
                    ping = null,
                    active = st.stage == TestStage.BOTH_DOWN || st.stage == TestStage.BOTH_UP,
                )
            }

            // ---- Insight
            val best = result?.let { r -> listOfNotNull(r.wifi?.downMbps, r.mobile?.downMbps).maxOrNull() }
            val combined = result?.combinedDown
            val gain = if (best != null && best > 0.5 && combined != null) combined / best else null
            val msg = st.message
            val note: String = when {
                msg != null -> msg
                st.stage == TestStage.DONE && gain != null ->
                    "Using both links together is ${String.format(Locale.US, "%.1f", gain)}× your fastest single link. " +
                        "That's the most DualBoost can give busy apps and downloads."
                else -> "Each link is tested on its own, then both at once. " +
                    "A full test takes about 40 seconds and can use 50–150 MB of mobile data."
            }
            Spacer(Modifier.height(Db.s3))
            DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                Text(note, color = if (st.stage == TestStage.ERROR) c.mobile else c.muted, style = Db.small)
                if (result != null && result.mobileBytes > 0 && !st.running) {
                    Spacer(Modifier.height(Db.s2))
                    Text("Mobile data used by the last test: ${formatBytes(result.mobileBytes)}", color = c.muted, style = Db.caption)
                }
            }
            Spacer(Modifier.height(120.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.bg, 1f to c.bg))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = Db.gutter, end = Db.gutter, top = 28.dp, bottom = Db.s4),
        ) {
            MainButton(
                text = if (st.running) "Cancel" else if (result != null) "Run again" else "Run test",
                icon = if (st.running) R.drawable.ic_power else R.drawable.ic_speed,
                color = if (st.running) c.mobile else c.wifi,
                modifier = Modifier.fillMaxWidth(),
            ) { if (st.running) SpeedTest.cancel() else SpeedTest.start(context) }
        }
    }
}

@Composable
private fun ResultRow(
    label: String,
    color: Color?,
    down: String?,
    up: String?,
    ping: String?,
    header: Boolean = false,
    bold: Boolean = false,
    active: Boolean = false,
) {
    val c = Db.colors
    val valueColor = if (header) c.muted else c.ink
    val style = if (header) Db.caption else Db.small.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) c.surfaceHigh else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = if (header) 12.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Db.s2),
    ) {
        Row(Modifier.weight(1.05f), verticalAlignment = Alignment.CenterVertically) {
            if (color != null) { Dot(color, 8.dp); Spacer(Modifier.width(8.dp)) }
            Text(label, color = c.ink, style = style, maxLines = 1)
        }
        Cell(down, valueColor, style, 1.3f)
        Cell(up, valueColor, style, 1.3f)
        Cell(ping, valueColor, style, 0.8f)
    }
}

@Composable
private fun RowScope.Cell(text: String?, color: Color, style: androidx.compose.ui.text.TextStyle, weight: Float) {
    Text(
        text ?: "—", color = if (text == null) Db.colors.muted else color, style = style,
        maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(weight),
    )
}
