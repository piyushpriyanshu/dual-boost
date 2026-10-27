package com.dualboost.app.core

import android.content.Context
import android.net.Uri
import android.os.Build
import com.dualboost.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a problem report from the diagnostic log, current state and settings.
 * Holds no personal data: no websites, IPs of sites visited, or account details.
 */
object Report {

    /** Everything, for "Copy full log". */
    fun full(context: Context, userText: String): String = build(context, userText, events = 400)

    /** Short enough to fit in a GitHub "new issue" link. */
    fun short(context: Context, userText: String): String {
        var events = 60
        while (true) {
            val body = build(context, userText, events)
            if (Uri.encode(body).length <= 7_000 || events <= 5) return body
            events -= 10
        }
    }

    fun issueUrl(context: Context, userText: String): Uri {
        val title = userText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(70)
            ?: "Problem report"
        return Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/issues/new").buildUpon()
            .appendQueryParameter("title", "[Report] $title")
            .appendQueryParameter("body", short(context, userText))
            .build()
    }

    private fun build(context: Context, userText: String, events: Int): String {
        val s = BoostSettings.get(context).current
        val ui = BoostState.ui.value
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("### What happened\n").append(userText.ifBlank { "(no description)" }).append("\n\n")
        sb.append("### Device\n```\n")
        sb.append("DualBoost ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Report time: $now\n```\n\n")
        sb.append("### State\n```\n")
        sb.append("Boost: ${ui.phase}${ui.error?.let { " · error: $it" } ?: ""}\n")
        fun link(name: String, l: LinkUi) =
            "$name: ${l.status} · down ${fmt(l.downMbps)} up ${fmt(l.upMbps)} Mbps · ${l.connections} conns · " +
                "ping ${if (l.rttMs > 0) "${l.rttMs} ms" else "?"} · capacity ${fmt(l.capacityMbps)} Mbps · healthy ${l.healthy}\n"
        sb.append(link("Wi-Fi", ui.wifi)).append(link("Mobile", ui.mobile))
        sb.append("Today: Wi-Fi ${mb(ui.wifiTodayBytes)} · Mobile ${mb(ui.mobileTodayBytes)}${if (ui.mobileCapReached) " (limit reached)" else ""}\n")
        sb.append("```\n\n")
        sb.append("### Settings\n```\n")
        sb.append("mode=${s.mode} cap=${s.mobileCapMb}MB treatAsWifi=${s.treatAsWifi} sticky=${s.stickySites} ipv6=${s.ipv6} ")
        sb.append("autoStart=${s.autoStart} dns=${s.dnsMode} excluded=${s.excludedApps.size} rules=${s.appRules.size} lowPing=${s.lowPingMode} dlMobile=${s.downloadsUseMobile} unit=${s.speedUnit} updates=${s.updateCheck}\n")
        sb.append("```\n\n")
        SpeedTest.lastResult(context)?.let {
            sb.append("### Last speed test\n```\n").append(SpeedTest.summary(it)).append("\n```\n\n")
        }
        Diag.lastCrash()?.let {
            sb.append("### Last crash\n```\n").append(it.take(if (events >= 400) 20_000 else 1_500)).append("\n```\n\n")
        }
        sb.append("### Recent events\n```\n")
        Diag.recent(events).forEach { sb.append(it).append('\n') }
        sb.append("```\n")
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun mb(b: Long) = String.format(Locale.US, "%.1f MB", b / 1e6)
}
