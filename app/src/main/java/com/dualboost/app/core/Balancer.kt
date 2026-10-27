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
 * Combine guard: if the last speed test proved that using both at once is slower than one link
 * alone (radio interference, or both sharing one connection), COMBINE sends traffic over that
 * faster link only and keeps the other as instant backup — so the boost can never make you slower.
 *
 * The returned list is an ordered preference: if the first link fails, the next is tried.
 */
class Balancer(
    private val links: LinkMonitor,
    private val settings: () -> SettingsSnapshot,
    private val mobileCapReached: () -> Boolean,
    /** The single link to use when combining was measured to be slower (null = combine). */
    private val onlyLink: () -> LinkType? = { null },
) {
    private class Sticky(val type: LinkType, @Volatile var until: Long)
    private val sticky = ConcurrentHashMap<InetAddress, Sticky>()

    private class AppPin(val type: LinkType, @Volatile var until: Long)
    /** Low-ping apps stay on one link so game/call sessions never switch mid-way. */
    private val appPins = ConcurrentHashMap<String, AppPin>()

    /**
     * Ordered link preference for a new connection.
     * [app] is the package that opened it (null if unknown).
     */
    fun order(dst: InetAddress?, port: Int, udp: Boolean, app: String? = null): List<Link> {
        val now = System.currentTimeMillis()
        val s = settings()
        val up = links.all.filter { it.isUp }
        val wifi = links.wifi
        val mobile = links.mobile

        // ---- per-app rules (strict ones apply even with a single link)
        val rule = s.ruleFor(app)
        when (rule) {
            AppRule.WIFI_ONLY -> return up.filter { it === wifi }
            AppRule.MOBILE -> {
                // Mobile first, Wi-Fi only if mobile is unavailable or today's limit is reached.
                return if (mobile.isUp && !mobileCapReached()) listOf(mobile) + up.filter { it === wifi }
                else up.filter { it === wifi }
            }
            else -> {}
        }
        if (up.size <= 1) return up

        // ---- low ping: explicit/preset apps, plus real-time UDP (games, calls) when the mode is on
        val realtimeUdp = s.lowPingMode && udp && port != 53 && port != 443
        if (s.mode != BoostMode.BACKUP && (rule == AppRule.LOW_PING || realtimeUdp)) {
            val first = lowPing(app, now)
            if (first != null) return listOf(first) + up.filter { it !== first }
        }

        // Prefer links with confirmed internet (Android-validated and passing our probe),
        // then validated ones, then anything that is up.
        val good = up.filter { it.isGood }
        val validated = up.filter { it.validated }
        val pool = when {
            good.isNotEmpty() -> good
            validated.isNotEmpty() -> validated
            else -> up
        }

        // Mobile data budget reached → mobile is last resort only.
        val capHit = mobileCapReached()

        val primary: Link = when {
            pool.size == 1 -> pool[0]
            s.mode == BoostMode.BACKUP || capHit ->
                if (!wifi.inPenalty(now)) wifi else mobile
            udp && port == 53 -> fastest(wifi, mobile, now) // DNS: lowest latency
            guardLink(pool, now) != null -> guardLink(pool, now)!!
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

    /** The combine-guard link, if the guard is on and that link is usable right now. */
    private fun guardLink(pool: List<Link>, now: Long): Link? {
        val t = onlyLink() ?: return null // checked per connection: follows Wi-Fi changes at once
        return pool.firstOrNull { it.type == t && !it.inPenalty(now) }
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

    /**
     * Lowest-ping healthy link. For a known app the choice is kept for 10 minutes (refreshed by use)
     * unless that link stops being healthy, so a game or call never hops links mid-session.
     */
    private fun lowPing(app: String?, now: Long): Link? {
        val wifi = links.wifi
        val mobile = links.mobile
        val capHit = mobileCapReached()
        fun ok(l: Link) = l.isGood && !l.inPenalty(now) && (l !== mobile || !capHit)
        if (app != null) {
            appPins[app]?.let { pin ->
                val l = if (pin.type == LinkType.WIFI) wifi else mobile
                if (pin.until > now && ok(l)) { pin.until = now + APP_PIN_MS; return l }
            }
        }
        val candidates = listOf(wifi, mobile).filter { ok(it) }
        if (candidates.isEmpty()) return null
        val best = candidates.minByOrNull { l ->
            // Unknown ping sorts last; Wi-Fi wins ties within 15%.
            val r = if (l.rttMs > 0) l.rttMs.toDouble() else 9_999.0
            if (l === wifi) r / 1.15 else r
        } ?: return null
        if (app != null) appPins[app] = AppPin(best.type, now + APP_PIN_MS)
        return best
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

    fun clear() { sticky.clear(); appPins.clear() }

    companion object {
        private const val STICKY_MS = 10 * 60_000L
        private const val MOBILE_BIAS = 0.9
        private const val EXPLORE_EVERY = 8
        private const val APP_PIN_MS = 10 * 60_000L
    }
}
