package com.dualboost.app.ui

import com.dualboost.app.core.SpeedUnit
import java.util.Locale

/** 0.0 – 99.9 with one decimal, larger values as whole numbers. */
fun formatMbps(v: Double): String {
    val x = if (v.isFinite() && v > 0) v else 0.0
    return if (x < 100) String.format(Locale.US, "%.1f", x) else String.format(Locale.US, "%.0f", x)
}

fun formatBytes(b: Long): String {
    val x = b.coerceAtLeast(0).toDouble()
    return when {
        x >= 1e9 -> String.format(Locale.US, "%.2f GB", x / 1e9)
        x >= 1e6 -> String.format(Locale.US, "%.0f MB", x / 1e6)
        x >= 1e3 -> String.format(Locale.US, "%.0f KB", x / 1e3)
        else -> "${x.toLong()} B"
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** A speed split into number and unit, so the UI can style them separately. */
data class Speed(val value: String, val unit: String) {
    override fun toString() = "$value $unit"
}

/** Formats a speed given in Mbps according to the user's chosen unit. */
fun formatSpeed(mbps: Double, unit: SpeedUnit): Speed {
    val x = if (mbps.isFinite() && mbps > 0) mbps else 0.0
    val kbps = unit == SpeedUnit.KBPS || (unit == SpeedUnit.AUTO && x > 0 && x < 1.0)
    if (!kbps) return Speed(formatMbps(x), "Mbps")
    val k = x * 1000
    val v = if (k < 10) String.format(Locale.US, "%.1f", k) else String.format(Locale.US, "%,d", Math.round(k))
    return Speed(v, "Kbps")
}
