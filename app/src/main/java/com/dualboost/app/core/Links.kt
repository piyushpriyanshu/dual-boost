package com.dualboost.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class LinkType(val label: String) { WIFI("Wi-Fi"), MOBILE("Mobile data") }

/** One relayed connection (TCP stream or UDP session). */
class Flow(val link: Link, val udp: Boolean) {
    val startedAt: Long = System.currentTimeMillis()
    val bytes = AtomicLong()
    /** Bytes seen at the previous one-second tick. */
    @Volatile var lastTickBytes = 0L
    /** Bytes moved during the last tick window. */
    @Volatile var recent = 0L
    /** Closes this flow's outgoing socket(s); used to drop flows instantly when their link goes away. */
    @Volatile var closer: (() -> Unit)? = null

    /**
     * A flow counts as "heavy" while it is new (TCP only) or actively moving data.
     * New UDP sessions (mostly DNS lookups) don't count until they carry real traffic,
     * so name lookups don't skew the balance.
     */
    fun isHeavy(now: Long): Boolean =
        (!udp && now - startedAt < NEW_FLOW_MS) || recent >= HEAVY_BYTES_PER_SEC

    companion object {
        const val NEW_FLOW_MS = 3_000L
        const val HEAVY_BYTES_PER_SEC = 24 * 1024L
    }
}

/** Live state of one physical link (Wi-Fi or mobile). */
class Link(val type: LinkType) {
    @Volatile var network: Network? = null
    @Volatile var validated = false
    @Volatile var hasIpv6 = false

    val rxBytes = AtomicLong()
    val txBytes = AtomicLong()
    val flows: MutableSet<Flow> = ConcurrentHashMap.newKeySet()

    /** Smoothed download / upload rates in bytes per second (updated every second). */
    @Volatile var rxRate = 0.0
    @Volatile var txRate = 0.0

    /** Learned download capacity in bytes per second. Rises as soon as the link proves more. */
    @Volatile var capacity = INITIAL_CAPACITY

    /** Consecutive connect failures, used to back off from a broken link. */
    @Volatile var failStreak = 0
    @Volatile var lastFailAt = 0L

    /**
     * Result of DualBoost's own internet probe. Android's "validated" flag can take minutes to
     * notice a dead connection (e.g. Wi-Fi connected but the router lost internet); the probe
     * notices within seconds.
     */
    @Volatile var healthy = true
    @Volatile var probeFailures = 0
    /** Last measured connect time to the internet in ms (-1 = unknown). */
    @Volatile var rttMs = -1

    val isUp: Boolean get() = network != null

    /** Up, confirmed by Android, and passing DualBoost's own probe. */
    val isGood: Boolean get() = network != null && validated && healthy

    fun heavyFlows(now: Long): Int = flows.count { it.isHeavy(now) }

    fun recordFailure() {
        failStreak++
        lastFailAt = System.currentTimeMillis()
    }

    fun recordSuccess() {
        failStreak = 0
    }

    /** True while the link is cooling down after repeated failures. */
    fun inPenalty(now: Long): Boolean = failStreak >= 3 && now - lastFailAt < 10_000L

    fun resetCounters() {
        rxBytes.set(0); txBytes.set(0)
        rxRate = 0.0; txRate = 0.0
        capacity = INITIAL_CAPACITY
        failStreak = 0
        healthy = true
        probeFailures = 0
        rttMs = -1
    }

    companion object {
        /** 8 Mbps starting guess for both links, so new connections alternate at first. */
        const val INITIAL_CAPACITY = 1_000_000.0
        const val MIN_CAPACITY = 8_000.0
    }
}

/**
 * Keeps both Wi-Fi and mobile data alive at the same time and tracks their state.
 * Uses ConnectivityManager.requestNetwork, so Android keeps mobile data up even while Wi-Fi is connected.
 */
class LinkMonitor(context: Context, private val onChange: () -> Unit) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    val wifi = Link(LinkType.WIFI)
    val mobile = Link(LinkType.MOBILE)
    val all = listOf(wifi, mobile)

    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    fun start() {
        stop()
        wifi.resetCounters(); mobile.resetCounters()
        request(wifi, NetworkCapabilities.TRANSPORT_WIFI)
        request(mobile, NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun request(link: Link, transport: Int) {
        val req = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                link.network = network
                link.failStreak = 0
                link.healthy = true
                link.probeFailures = 0
                cm.getNetworkCapabilities(network)?.let { applyCaps(link, it) }
                cm.getLinkProperties(network)?.let { applyLp(link, it) }
                Log.i(TAG, "${link.type} available: $network")
                onChange()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (link.network != network) link.network = network
                val before = link.validated
                applyCaps(link, caps)
                if (before != link.validated) onChange()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (link.network == network) applyLp(link, lp)
            }

            override fun onLost(network: Network) {
                if (link.network == network) {
                    link.network = null
                    link.validated = false
                    link.hasIpv6 = false
                    Log.i(TAG, "${link.type} lost")
                    onChange()
                }
            }
        }
        try {
            cm.requestNetwork(req, cb)
            synchronized(callbacks) { callbacks += cb }
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork failed for ${link.type}", e)
        }
    }

    private fun applyCaps(link: Link, caps: NetworkCapabilities) {
        link.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun applyLp(link: Link, lp: LinkProperties) {
        link.hasIpv6 = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
    }

    fun stop() {
        val list = synchronized(callbacks) { callbacks.toList().also { callbacks.clear() } }
        for (cb in list) runCatching { cm.unregisterNetworkCallback(cb) }
        wifi.network = null; mobile.network = null
        wifi.validated = false; mobile.validated = false
    }

    /** Networks currently usable, preferred first. */
    fun activeNetworks(): List<Network> = all.mapNotNull { it.network }

    companion object { private const val TAG = "DualBoost/Links" }
}
