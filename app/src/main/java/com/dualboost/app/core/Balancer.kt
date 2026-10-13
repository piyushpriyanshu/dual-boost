package com.dualboost.app.core

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which link each new connection uses.
 *
 * COMBINE: every new connection goes to the link where it is expected to get the most speed:
 *   expected = learnedCapacity / (activeHeavyConnections + 1)
 * This spreads busy apps across both links in proportion to their real speed
 * (e.g. 50 Mbps Wi-Fi gets ~5 of every 8 parallel connections, 30 Mbps mobile gets ~3),
 * while idle background connections are ignored.
 *
 * BACKUP: Wi-Fi only, mobile data takes over instantly if Wi-Fi fails.
 *
 * The returned list is an ordered preference: if the first link fails, the next is tried.
 */
class Balancer(
    private val links: LinkMonitor,
    private val settings: () -> SettingsSnapshot,
    private val mobileCapReached: () -> Boolean,
) {
    private class Sticky(val type: LinkType, @Volatile var until: Long)
    private val sticky = ConcurrentHashMap<InetAddress, Sticky>()

    fun order(dst: InetAddress?, port: Int, udp: Boolean): List<Link> {
        val now = System.currentTimeMillis()
        val s = settings()
        val up = links.all.filter { it.isUp }
        if (up.size <= 1) return up

        // Prefer links with confirmed internet (Android-validated and passing our probe),
        // then validated ones, then anything that is up.
        val good = up.filter { it.isGood }
        val validated = up.filter { it.validated }
        val pool = when {
            good.isNotEmpty() -> good
            validated.isNotEmpty() -> validated
            else -> up
        }
        val wifi = links.wifi
        val mobile = links.mobile

        // Mobile data budget reached → mobile is last resort only.
        val capHit = mobileCapReached()

        val primary: Link = when {
            pool.size == 1 -> pool[0]
            s.mode == BoostMode.BACKUP || capHit ->
                if (!wifi.inPenalty(now)) wifi else mobile
            udp && port == 53 -> fastest(wifi, mobile, now) // DNS: lowest latency
            else -> {
                val pinned = if (s.stickySites && dst != null) sticky[dst] else null
                if (pinned != null && pinned.until > now) {
                    pinned.until = now + STICKY_MS
                    if (pinned.type == LinkType.WIFI) wifi else mobile
                } else {
                    pickBest(now, wifi, mobile).also {
                        if (s.stickySites && dst != null) sticky[dst] = Sticky(it.type, now + STICKY_MS)
                    }
                }
            }
        }
        if (sticky.size > 4096) sticky.entries.removeIf { it.value.until < now }

        val rest = up.filter { it !== primary }
            .sortedBy { if (it.isGood) 0 else if (it.validated) 1 else 2 }
        return listOf(primary) + rest
    }

    private val decisions = java.util.concurrent.atomic.AtomicInteger()

    private fun pickBest(now: Long, wifi: Link, mobile: Link): Link {
        if (wifi.inPenalty(now) && !mobile.inPenalty(now)) return mobile
        if (mobile.inPenalty(now) && !wifi.inPenalty(now)) return wifi
        val ew = expected(wifi, now)
        // Small bias toward Wi-Fi: it is free and usually lower latency.
        val em = expected(mobile, now) * MOBILE_BIAS
        val best = if (ew >= em) wifi else mobile
        // Exploration: every 8th new connection goes to the other link, so a link whose
        // estimate is low (or out of date) is re-measured and can win traffic back.
        return if (decisions.incrementAndGet() % EXPLORE_EVERY == 0) (if (best === wifi) mobile else wifi) else best
    }

    /** Lower measured latency wins; Wi-Fi when unknown or close. */
    private fun fastest(wifi: Link, mobile: Link, now: Long): Link {
        if (wifi.inPenalty(now) && !mobile.inPenalty(now)) return mobile
        if (mobile.inPenalty(now)) return wifi
        val w = wifi.rttMs; val m = mobile.rttMs
        return if (w > 0 && m > 0 && m * 1.3 < w) mobile else wifi
    }

    private fun expected(link: Link, now: Long): Double =
        link.capacity / (link.heavyFlows(now) + 1)

    fun clear() = sticky.clear()

    companion object {
        private const val STICKY_MS = 10 * 60_000L
        private const val MOBILE_BIAS = 0.9
        private const val EXPLORE_EVERY = 8
    }
}
