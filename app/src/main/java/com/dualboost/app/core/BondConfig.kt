package com.dualboost.app.core

import android.content.Context
import android.util.Base64
import com.dualboost.app.bond.BondClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Bond server settings. The key never leaves the phone except inside the setup script. */
data class BondCfg(
    /** Bond traffic through the server while the boost runs. */
    val enabled: Boolean = false,
    /** Server address (public IP or name). Empty = not set up. */
    val host: String = "",
    val port: Int = 443,
    /** 32-byte key, base64url without padding (43 characters). */
    val key: String = "",
) {
    val configured: Boolean get() = host.isNotBlank() && key.length >= 43
}

enum class BondStatus { NOT_SET_UP, OFF, CONNECTING, CONNECTED, UNREACHABLE }

data class BondUi(
    val status: BondStatus = BondStatus.NOT_SET_UP,
    /** Lowest round-trip time to the server, ms (-1 unknown). */
    val pingMs: Int = -1,
    val wifiUp: Boolean = false,
    val mobileUp: Boolean = false,
    val error: String? = null,
    /** When the server stopped answering (0 = it is answering). */
    val downSince: Long = 0,
)

object BondConfig {
    private const val PREFS = "bond"
    private val _state = MutableStateFlow(BondCfg())
    val state: StateFlow<BondCfg> = _state.asStateFlow()
    @Volatile private var loaded = false

    /** The bond client of the running boost (null when the boost or the bond is off). */
    @Volatile var client: BondClient? = null

    fun get(context: Context): BondCfg {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                var key = p.getString("key", null)
                if (key.isNullOrEmpty()) {
                    key = newKey()
                    p.edit().putString("key", key).apply()
                }
                _state.value = BondCfg(p.getBoolean("enabled", false), p.getString("host", "") ?: "", p.getInt("port", 443), key)
                loaded = true
            }
        }
        return _state.value
    }

    fun update(context: Context, block: (BondCfg) -> BondCfg) {
        val next = block(get(context))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", next.enabled).putString("host", next.host.trim()).putInt("port", next.port)
            .putString("key", next.key).apply()
        _state.value = next.copy(host = next.host.trim())
    }

    fun keyBytes(cfg: BondCfg): ByteArray = Base64.decode(cfg.key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun newKey(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Accepts "1.2.3.4", "1.2.3.4:8443" or a host name. Returns (host, port) or null. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (t.isEmpty() || t.contains(' ')) return null
        val colon = t.lastIndexOf(':')
        if (colon > 0 && t.count { it == ':' } == 1) {
            val port = t.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return t.substring(0, colon) to port
        }
        return t to 443
    }

    /**
     * Cloud-init script for a fresh Ubuntu server: installs the DualBoost server from this app's
     * GitHub releases, keeps it updated daily, tunes the network and opens the port.
     */
    fun setupScript(cfg: BondCfg): String = """
#!/bin/bash
# DualBoost bond server setup. Paste this into "Initialization script" (cloud-init) when creating the server.
set -eu
PORT=${cfg.port}
BIN=/usr/local/bin/dualboost-server

# The key is written without being echoed to the console or logs.
umask 077
install -d -m 700 /etc/dualboost
printf '%s\n' '${cfg.key}' > /etc/dualboost/key
chmod 600 /etc/dualboost/key
umask 022
set -x

cat > /usr/local/bin/dualboost-update <<'UPD'
#!/bin/bash
# Installs or updates the server from the latest DualBoost release (checked against its SHA-256).
set -e
REPO=piyushpriyanshu/dual-boost
case "${'$'}(uname -m)" in aarch64|arm64) ARCH=arm64 ;; *) ARCH=amd64 ;; esac
BIN=/usr/local/bin/dualboost-server
T=${'$'}(mktemp -d)
trap 'rm -rf "${'$'}T"' EXIT
URL="https://github.com/${'$'}REPO/releases/latest/download/dualboost-server-linux-${'$'}ARCH"
curl -fsSL --retry 5 -o "${'$'}T/s" "${'$'}URL"
curl -fsSL --retry 5 -o "${'$'}T/s.sha256" "${'$'}URL.sha256"
echo "${'$'}(cut -d' ' -f1 "${'$'}T/s.sha256")  ${'$'}T/s" | sha256sum -c -
if ! cmp -s "${'$'}T/s" "${'$'}BIN"; then
  install -m 755 "${'$'}T/s" "${'$'}BIN"
  systemctl restart dualboost 2>/dev/null || true
fi
UPD
chmod 755 /usr/local/bin/dualboost-update

cat > /etc/systemd/system/dualboost.service <<SVC
[Unit]
Description=DualBoost bond server
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0
[Service]
# First start (or a missing binary): download it, retrying every 10 s until it works.
ExecStartPre=+/bin/sh -c 'test -x ${'$'}BIN || /usr/local/bin/dualboost-update'
ExecStart=${'$'}BIN -listen :${'$'}PORT
LoadCredential=key:/etc/dualboost/key
DynamicUser=yes
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=yes
LimitNOFILE=1048576
MemoryMax=600M
TasksMax=4096
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
SVC

cat > /etc/systemd/system/dualboost-update.service <<SVC
[Unit]
Description=Update the DualBoost bond server
[Service]
Type=oneshot
ExecStart=/usr/local/bin/dualboost-update
SVC
cat > /etc/systemd/system/dualboost-update.timer <<SVC
[Unit]
Description=Daily DualBoost server update (04:00 India time)
[Timer]
OnCalendar=*-*-* 22:30:00 UTC
RandomizedDelaySec=20m
Persistent=true
[Install]
WantedBy=timers.target
SVC

# Fast, stable TCP for high-speed links.
cat > /etc/sysctl.d/90-dualboost.conf <<SYS
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
net.core.rmem_max=67108864
net.core.wmem_max=67108864
net.ipv4.tcp_rmem=4096 131072 67108864
net.ipv4.tcp_wmem=4096 131072 67108864
net.ipv4.tcp_notsent_lowat=131072
net.ipv4.tcp_slow_start_after_idle=0
net.ipv4.tcp_mtu_probing=1
SYS
sysctl --system || true

# Open the port in the server's own firewall (Oracle images block everything but SSH).
if command -v firewall-cmd >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port=${'$'}PORT/tcp || true; firewall-cmd --reload || true
fi
if command -v iptables >/dev/null 2>&1; then
  iptables -C INPUT -p tcp --dport ${'$'}PORT -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport ${'$'}PORT -j ACCEPT
  if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save || true; fi
fi

systemctl daemon-reload
systemctl enable --now dualboost.service dualboost-update.timer
""".trimStart()

    /** Short text shown when a pasted setup script is checked, e.g. "Key ends …a1b2". */
    fun keyHint(cfg: BondCfg): String = "…" + cfg.key.takeLast(4)
}
