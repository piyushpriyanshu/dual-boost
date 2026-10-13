package com.dualboost.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** When DualBoost looks for a new version on GitHub. */
enum class UpdateCheck(val label: String, val hours: Long) {
    ON_OPEN("When I open the app", 0),
    EVERY_1H("Every 1 hour", 1),
    EVERY_3H("Every 3 hours", 3),
    EVERY_6H("Every 6 hours", 6),
    MANUAL("Manual only", 0);

    /** Checks in the background on a timer. */
    val background: Boolean get() = hours > 0
    /** Checks each time the app is opened. */
    val onOpen: Boolean get() = this != MANUAL
}

/** How traffic is shared between the two links. */
enum class BoostMode { COMBINE, BACKUP }

/** Immutable snapshot of user settings. Read on every new connection, so changes apply live. */
data class SettingsSnapshot(
    val mode: BoostMode = BoostMode.COMBINE,
    /** Daily mobile data budget in MB. 0 = no limit. */
    val mobileCapMb: Int = 0,
    /** Report the boosted connection to apps as unmetered (like Wi-Fi). */
    val treatAsWifi: Boolean = true,
    /** Keep all connections to the same server on one link (helps strict banking sites). */
    val stickySites: Boolean = false,
    /** Carry IPv6 when both links support it. */
    val ipv6: Boolean = true,
    /** Start automatically after the phone reboots. */
    val autoStart: Boolean = false,
    /** Apps that bypass DualBoost entirely. */
    val excludedApps: Set<String> = emptySet(),
    /** When to look for updates. */
    val updateCheck: UpdateCheck = UpdateCheck.EVERY_6H,
    /** Download and install updates without asking first. */
    val autoUpdate: Boolean = true,
)

/** Persistent settings + mobile usage counter. Single instance per process. */
class BoostSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("dualboost", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()
    val current: SettingsSnapshot get() = _state.value

    private fun load() = SettingsSnapshot(
        mode = runCatching { BoostMode.valueOf(prefs.getString(K_MODE, null) ?: "COMBINE") }
            .getOrDefault(BoostMode.COMBINE),
        mobileCapMb = prefs.getInt(K_CAP, 0),
        treatAsWifi = prefs.getBoolean(K_WIFI, true),
        stickySites = prefs.getBoolean(K_STICKY, false),
        ipv6 = prefs.getBoolean(K_V6, true),
        autoStart = prefs.getBoolean(K_AUTO, false),
        excludedApps = prefs.getStringSet(K_EXCL, emptySet())?.toSet() ?: emptySet(),
        updateCheck = runCatching { UpdateCheck.valueOf(prefs.getString(K_UPD, null) ?: "EVERY_6H") }
            .getOrDefault(UpdateCheck.EVERY_6H),
        autoUpdate = prefs.getBoolean(K_AUTOUPD, true),
    )

    fun update(block: (SettingsSnapshot) -> SettingsSnapshot) {
        val next = block(_state.value)
        prefs.edit()
            .putString(K_MODE, next.mode.name)
            .putInt(K_CAP, next.mobileCapMb)
            .putBoolean(K_WIFI, next.treatAsWifi)
            .putBoolean(K_STICKY, next.stickySites)
            .putBoolean(K_V6, next.ipv6)
            .putBoolean(K_AUTO, next.autoStart)
            .putStringSet(K_EXCL, HashSet(next.excludedApps))
            .putString(K_UPD, next.updateCheck.name)
            .putBoolean(K_AUTOUPD, next.autoUpdate)
            .apply()
        _state.value = next
    }

    // ---- Mobile data used today (survives restarts, resets at local midnight) ----

    @Synchronized
    fun mobileUsedToday(): Long {
        rollDay()
        return prefs.getLong(K_USED, 0L)
    }

    @Synchronized
    fun addMobileUsage(bytes: Long) {
        if (bytes <= 0) return
        rollDay()
        prefs.edit().putLong(K_USED, prefs.getLong(K_USED, 0L) + bytes).apply()
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(K_DAY, null) != today) {
            prefs.edit().putString(K_DAY, today).putLong(K_USED, 0L).apply()
        }
    }

    /** Whether the user wants the boost running (used to restore after reboot / update). */
    var wantRunning: Boolean
        get() = prefs.getBoolean(K_WANT, false)
        set(v) { prefs.edit().putBoolean(K_WANT, v).apply() }

    companion object {
        private const val K_MODE = "mode"
        private const val K_CAP = "mobile_cap_mb"
        private const val K_WIFI = "treat_as_wifi"
        private const val K_STICKY = "sticky_sites"
        private const val K_V6 = "ipv6"
        private const val K_AUTO = "auto_start"
        private const val K_EXCL = "excluded_apps"
        private const val K_USED = "mobile_used"
        private const val K_DAY = "mobile_day"
        private const val K_WANT = "want_running"
        private const val K_UPD = "update_check"
        private const val K_AUTOUPD = "auto_update"

        @Volatile private var instance: BoostSettings? = null

        fun get(context: Context): BoostSettings =
            instance ?: synchronized(this) {
                instance ?: BoostSettings(context).also { instance = it }
            }
    }
}
