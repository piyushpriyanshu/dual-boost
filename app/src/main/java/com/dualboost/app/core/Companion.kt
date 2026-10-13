package com.dualboost.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.InetAddresses
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Helpers for working alongside the AdGuard app and for the DNS choice. */
object Companion {

    /** Known AdGuard app packages (Play Store "content blocker" build and the full APK build). */
    val ADGUARD_PACKAGES = listOf("com.adguard.android", "com.adguard.android.contentblocker")

    fun adguardInstalled(context: Context): String? = ADGUARD_PACKAGES.firstOrNull {
        try { context.packageManager.getPackageInfo(it, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
    }

    fun openAdguard(context: Context): Boolean {
        val pkg = adguardInstalled(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** True if something (AdGuard) is accepting connections on 127.0.0.1:[port]. */
    fun proxyReachable(port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

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
