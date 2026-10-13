package com.dualboost.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase { OFF, STARTING, ON, STOPPING }

enum class LinkStatus { OFF, NO_INTERNET, READY }

data class LinkUi(
    val status: LinkStatus = LinkStatus.OFF,
    /** Megabits per second. */
    val downMbps: Double = 0.0,
    val upMbps: Double = 0.0,
    val connections: Int = 0,
    val sessionBytes: Long = 0,
)

data class BoostUi(
    val phase: Phase = Phase.OFF,
    val wifi: LinkUi = LinkUi(),
    val mobile: LinkUi = LinkUi(),
    /** Last 60 seconds of download speed, oldest first (Mbps). */
    val wifiHistory: List<Float> = emptyList(),
    val mobileHistory: List<Float> = emptyList(),
    val peakMbps: Double = 0.0,
    val mobileTodayBytes: Long = 0,
    val wifiTodayBytes: Long = 0,
    val mobileCapReached: Boolean = false,
    val startedAt: Long = 0,
    val error: String? = null,
    /** DNS servers actually in use (for display). */
    val dnsInUse: List<String> = emptyList(),
) {
    val totalDown: Double get() = wifi.downMbps + mobile.downMbps
    val totalUp: Double get() = wifi.upMbps + mobile.upMbps
    val running: Boolean get() = phase == Phase.ON
}

/** Process-wide live state shared by the service, UI, notification and Quick Settings tile. */
object BoostState {
    private val _ui = MutableStateFlow(BoostUi())
    val ui: StateFlow<BoostUi> = _ui.asStateFlow()

    fun set(value: BoostUi) { _ui.value = value }
    fun update(block: (BoostUi) -> BoostUi) {
        while (true) {
            val prev = _ui.value
            if (_ui.compareAndSet(prev, block(prev))) return
        }
    }
}
