package com.dualboost.app.core

import android.net.InetAddresses
import java.net.InetAddress

/** Helpers for the DNS choice. */
object AddOns {

    /** Parses user-entered DNS IPs. Only numeric addresses are accepted (no lookups). */
    fun parseDns(text: String): List<InetAddress> =
        text.split(',', ' ', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && InetAddresses.isNumericAddress(it) }
            .map { InetAddresses.parseNumericAddress(it) }
            .distinct()
            .take(4)

    /** Resolved DNS servers for the current settings (falls back to Standard if Custom is invalid). */
    fun dnsServers(s: SettingsSnapshot, withV6: Boolean): List<String> {
        if (s.dnsMode == DnsMode.CUSTOM) {
            val custom = parseDns(s.customDns)
                .filter { withV6 || it is java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
            if (custom.isNotEmpty()) return custom
            return dnsServers(s.copy(dnsMode = DnsMode.STANDARD), withV6)
        }
        return s.dnsMode.v4 + (if (withV6) s.dnsMode.v6 else emptyList())
    }
}
