package com.dualboost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualboost.app.R
import com.dualboost.app.core.BondConfig
import com.dualboost.app.core.BondStatus
import com.dualboost.app.core.BondUi
import com.dualboost.app.core.Phase

/** Bond server: what it is, its live state, the switch, and the guided free setup. */
@Composable
fun BondScreen(phase: Phase, bond: BondUi, onBack: () -> Unit, onSpeedTest: () -> Unit) {
    val c = Db.colors
    val context = LocalContext.current
    val cfg by BondConfig.state.collectAsStateWithLifecycle()
    var address by rememberSaveable(cfg.host, cfg.port) {
        mutableStateOf(if (cfg.host.isEmpty()) "" else if (cfg.port == 443) cfg.host else "${cfg.host}:${cfg.port}")
    }
    var showSteps by rememberSaveable { mutableStateOf(!cfg.configured) }
    val running = phase == Phase.ON

    fun copyScript() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("DualBoost setup script", BondConfig.setupScript(BondConfig.get(context))))
        Toast.makeText(context, "Setup script copied", Toast.LENGTH_SHORT).show()
    }

    fun saveFile() {
        val ok = BondConfig.saveScriptFile(context, BondConfig.get(context))
        Toast.makeText(
            context,
            if (ok) "Saved to Downloads as dualboost-setup.sh" else "Couldn't save the file. Use Copy setup script instead.",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun save() {
        val parsed = BondConfig.parseAddress(address)
        if (parsed == null) {
            Toast.makeText(context, "Enter the server's public IP address, e.g. 140.238.1.23", Toast.LENGTH_LONG).show()
            return
        }
        BondConfig.update(context) { it.copy(host = parsed.first, port = parsed.second, enabled = true) }
        Toast.makeText(context, if (running) "Saved. Connecting…" else "Saved. It connects when the boost starts.", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Db.gutter),
    ) {
        TopBar("Bond server", onBack)
        Spacer(Modifier.height(Db.s3))

        // ---- Status
        val (pill, dot) = when {
            !cfg.configured -> "Not set up" to c.muted
            !cfg.enabled -> "Off" to c.muted
            !running -> "Ready · starts with the boost" to c.wifi
            bond.status == BondStatus.CONNECTED -> "Connected" to c.good
            bond.status == BondStatus.UNREACHABLE -> "Server not answering" to c.mobile
            else -> "Connecting…" to c.warn
        }
        DbCard(Modifier.fillMaxWidth(), padding = Db.s5) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile(R.drawable.ic_server, c.purple, size = 56.dp, corner = 18.dp)
                Spacer(Modifier.height(Db.s3))
                StatusPill(pill, dot)
                Spacer(Modifier.height(Db.s3))
                Text("One connection, both links", color = c.ink, style = Db.h1, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your own free server joins Wi-Fi and mobile data into one connection, like Speedify. " +
                        "Even a single video, download or upload uses both links, and keeps going if one drops.",
                    color = c.muted, style = Db.small, textAlign = TextAlign.Center,
                )
                if (running && bond.status == BondStatus.CONNECTED) {
                    Spacer(Modifier.height(Db.s4))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BondFact("Ping", if (bond.pingMs >= 0) "${bond.pingMs} ms" else "—")
                        BondFact("Wi-Fi", if (bond.wifiUp) "Joined" else "—", if (bond.wifiUp) c.wifi else c.muted)
                        BondFact("Mobile", if (bond.mobileUp) "Joined" else "—", if (bond.mobileUp) c.mobile else c.muted)
                    }
                }
            }
        }

        // ---- Problem help
        if (cfg.configured && cfg.enabled && running && bond.status == BondStatus.UNREACHABLE) {
            Spacer(Modifier.height(Db.s3))
            val mins = ((System.currentTimeMillis() - bond.downSince) / 60_000).coerceAtLeast(0)
            NoticeCard(
                "Using normal combining for now",
                (if (mins >= 1) "No answer for $mins min. " else "") +
                    "Check in Oracle Cloud that the server is Running and port ${cfg.port} is open. " +
                    "If Oracle removed it, create a new one with the same setup script and enter its new IP." +
                    (bond.error?.let { "\n\nLast error: $it" } ?: ""),
                c.mobile, R.drawable.ic_info,
            )
        }

        // ---- Switch
        if (cfg.configured) {
            SectionHeader("Use the bond")
            SettingsGroup {
                SwitchRow(
                    "Bond all traffic", "Games, calls and apps with their own rule stay direct for the lowest ping",
                    R.drawable.ic_combine, c.purple, cfg.enabled,
                ) { v -> BondConfig.update(context) { it.copy(enabled = v) } }
                RowDivider()
                ChevronRow(
                    "Test bonded speed", "Speed test adds a single-connection bonded result",
                    R.drawable.ic_speed, c.wifi, onClick = onSpeedTest,
                )
            }
        }

        // ---- Server address
        SectionHeader(if (cfg.configured) "Your server" else "Server address")
        DbCard(Modifier.fillMaxWidth(), padding = 18.dp) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (address.isEmpty()) Text("Public IP address, e.g. 140.238.1.23", color = c.muted, fontSize = 15.sp)
                BasicTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp), cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Db.s3))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { SecondaryButton("Copy setup script") { copyScript() } }
                Spacer(Modifier.width(Db.s3))
                Box(Modifier.weight(1f)) {
                    MainButton("Save", R.drawable.ic_server, c.wifi, Modifier.fillMaxWidth().height(52.dp)) { save() }
                }
            }
            Spacer(Modifier.height(Db.s3))
            Text(
                "Your private key ends in ${BondConfig.keyHint(cfg)}. It is inside the setup script, so only your server can join.",
                color = c.muted, style = Db.caption,
            )
        }

        // ---- Guided setup
        SectionHeader("Set up a free server (about 10 minutes)")
        DbCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Row(
                Modifier.fillMaxWidth().clickable { showSteps = !showSteps }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(R.drawable.ic_globe, c.good, size = 36.dp, corner = 11.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Oracle Cloud Always Free", color = c.ink, style = Db.rowTitle)
                    Text("₹0 · no PC needed · done in your phone's browser", color = c.muted, style = Db.small)
                }
                Text(if (showSteps) "Hide" else "Show", color = c.primary, style = Db.small, fontWeight = FontWeight.SemiBold)
            }
            if (showSteps) {
                RowDivider(inset = false)
                Column(Modifier.padding(18.dp)) {
                    SETUP_STEPS.forEachIndexed { i, (title, text) ->
                        Step(i + 1, title, text)
                        if (i < SETUP_STEPS.lastIndex) Spacer(Modifier.height(Db.s4))
                    }
                    Spacer(Modifier.height(Db.s4))
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            SecondaryButton("Open Oracle Cloud") {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloud.oracle.com/compute/instances")))
                                }
                            }
                        }
                        Spacer(Modifier.width(Db.s3))
                        Box(Modifier.weight(1f)) { SecondaryButton("Copy script") { copyScript() } }
                    }
                    Spacer(Modifier.height(Db.s3))
                    SecondaryButton("Save script file (for upload)") { saveFile() }
                }
            }
        }

        if (cfg.configured) {
            Spacer(Modifier.height(Db.s3))
            SettingsGroup {
                ChevronRow(
                    "Remove server", "Forget this server (the key is kept for a new one)",
                    R.drawable.ic_restart, c.mobile,
                ) {
                    BondConfig.update(context) { it.copy(host = "", enabled = false) }
                    address = ""
                    showSteps = true
                }
            }
        }

        Spacer(Modifier.height(Db.s4))
        Text(
            "All bonded traffic is encrypted between your phone and your server. Websites see your server's address. " +
                "If an app doesn't like that (some banking apps), set it to Bypass or Wi-Fi only in App rules.",
            color = c.muted, style = Db.caption, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Db.s2),
        )
        Spacer(Modifier.height(Db.s8))
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

private val SETUP_STEPS = listOf(
    "Create the server" to "Open Oracle Cloud → Compute → Instances → Create instance. Name it DualBoost " +
        "(make the network first: see step 3).",
    "Image and shape" to "Image: Canonical Ubuntu 24.04. Shape: Ampere VM.Standard.A1.Flex with 1 OCPU and 1 GB memory " +
        "(marked Always Free). If it says out of capacity, try another availability domain or try again later.",
    "Network and key" to "First, in Networking → Virtual cloud networks → Start VCN Wizard → \"Create VCN with Internet " +
        "Connectivity\", make dualboost-vcn. In the instance form pick it and its public subnet, then turn on " +
        "\"Automatically assign public IPv4 address\". For SSH keys choose \"No SSH keys\".",
    "Add the setup script" to "Under Advanced options → Management → Initialization script: if you see " +
        "\"Paste cloud-init script\", tap Copy script below and paste it. Otherwise tap Save script file below and " +
        "choose dualboost-setup.sh from Downloads. Then tap Create.",
    "Open port 443" to "When the server is Running: Networking → its subnet → Default Security List → Add Ingress Rules. " +
        "Source CIDR 0.0.0.0/0, IP protocol TCP, destination port 443.",
    "Enter the address" to "Copy the server's Public IP address, paste it above and tap Save. " +
        "The first start installs everything and takes about 3 minutes, then the status shows Connected.",
)

@Composable
private fun Step(n: Int, title: String, text: String) {
    val c = Db.colors
    Row {
        Box(Modifier.size(26.dp).clip(CircleShape).background(c.purple.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text("$n", color = c.purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = Db.rowTitle)
            Spacer(Modifier.height(2.dp))
            Text(text, color = c.muted, style = Db.small)
        }
    }
}

@Composable
private fun BondFact(label: String, value: String, color: androidx.compose.ui.graphics.Color = Db.colors.ink) {
    val c = Db.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = Db.stat)
        Spacer(Modifier.height(2.dp))
        Text(label, color = c.muted, style = Db.caption)
    }
}
