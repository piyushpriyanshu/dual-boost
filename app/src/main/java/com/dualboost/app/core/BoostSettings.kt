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

/**
 * How one app's connections are routed. Bypass is handled separately (excludedApps):
 * those apps don't enter DualBoost at all.
 */
enum class AppRule(val label: String, val detail: String) {
    BOTH("Both links", "Default. Uses Wi-Fi and mobile data together"),
    LOW_PING("Low ping", "Games and calls: stays on whichever link has the lowest ping"),
    WIFI_ONLY("Wi-Fi only", "Never uses mobile data"),
    MOBILE("Mobile data", "Uses mobile data, Wi-Fi only if mobile is unavailable"),
}

/** Popular games and call apps that get Low ping automatically while Low-ping mode is on. */
val LOW_PING_PRESETS = setOf(
    "com.pubg.imobile", "com.tencent.ig", "com.dts.freefireth", "com.dts.freefiremax",
    "com.activision.callofduty.shooter", "com.garena.game.codm", "com.mobile.legends",
    "com.riotgames.league.wildrift", "com.supercell.clashofclans", "com.supercell.clashroyale",
    "com.supercell.brawlstars", "com.miHoYo.GenshinImpact", "com.valorant.mobile",
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.discord",
    "us.zoom.videomeetings", "com.google.android.apps.meetings", "com.google.android.apps.tachyon",
    "com.microsoft.teams", "com.skype.raider", "com.Slack",
)

/** How speeds are shown. AUTO switches to Kbps below 1 Mbps. */
enum class SpeedUnit(val label: String) { MBPS("Mbps"), KBPS("Kbps"), AUTO("Auto") }

/** Which DNS servers the boost uses. AdGuard options block ads and trackers at the DNS level. */
enum class DnsMode(val label: String, val v4: List<String>, val v6: List<String>) {
    STANDARD("Standard", listOf("1.1.1.1", "8.8.8.8"), listOf("2606:4700:4700::1111", "2001:4860:4860::8888")),
    ADGUARD("AdGuard", listOf("94.140.14.14", "94.140.15.15"), listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")),
    ADGUARD_FAMILY("AdGuard Family", listOf("94.140.14.15", "94.140.15.16"), listOf("2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff")),
    CUSTOM("Custom", emptyList(), emptyList()),
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
    /** Per-app routing rules (apps not listed use [AppRule.BOTH]). */
    val appRules: Map<String, AppRule> = emptyMap(),
    /** Games and calls stay on the lowest-ping link (presets + apps set to Low ping). */
    val lowPingMode: Boolean = true,
    /** When to look for updates. */
    val updateCheck: UpdateCheck = UpdateCheck.EVERY_6H,
    /** Download and install updates without asking first. */
    val autoUpdate: Boolean = true,
    /** DNS servers used by the boost. */
    val dnsMode: DnsMode = DnsMode.STANDARD,
    /** Custom DNS server IPs, separated by commas or spaces (used with [DnsMode.CUSTOM]). */
    val customDns: String = "",
    /** Unit used to display speeds everywhere (app, notification, Quick Settings tile). */
    val speedUnit: SpeedUnit = SpeedUnit.MBPS,
    /** The download booster may use mobile data (always within the daily limit). */
    val downloadsUseMobile: Boolean = true,
)

/** The rule that actually applies to [pkg] (explicit rule, else Low-ping preset, else Both). */
fun SettingsSnapshot.ruleFor(pkg: String?): AppRule {
    if (pkg == null) return AppRule.BOTH
    appRules[pkg]?.let { return if (it == AppRule.LOW_PING && !lowPingMode) AppRule.BOTH else it }
    return if (lowPingMode && pkg in LOW_PING_PRESETS) AppRule.LOW_PING else AppRule.BOTH
}

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
        appRules = (prefs.getStringSet(K_RULES, emptySet()) ?: emptySet()).mapNotNull { e ->
            val pkg = e.substringBeforeLast('=', "")
            val rule = runCatching { AppRule.valueOf(e.substringAfterLast('=')) }.getOrNull()
            if (pkg.isNotEmpty() && rule != null) pkg to rule else null
        }.toMap(),
        lowPingMode = prefs.getBoolean(K_LOWPING, true),
        updateCheck = runCatching { UpdateCheck.valueOf(prefs.getString(K_UPD, null) ?: "EVERY_6H") }
            .getOrDefault(UpdateCheck.EVERY_6H),
        autoUpdate = prefs.getBoolean(K_AUTOUPD, true),
        dnsMode = runCatching { DnsMode.valueOf(prefs.getString(K_DNS, null) ?: "STANDARD") }
            .getOrDefault(DnsMode.STANDARD),
        customDns = prefs.getString(K_DNS_CUSTOM, "") ?: "",
        speedUnit = runCatching { SpeedUnit.valueOf(prefs.getString(K_UNIT, null) ?: "MBPS") }
            .getOrDefault(SpeedUnit.MBPS),
        downloadsUseMobile = prefs.getBoolean(K_DL_MOBILE, true),
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
            .putStringSet(K_RULES, next.appRules.map { "${it.key}=${it.value.name}" }.toHashSet())
            .putBoolean(K_LOWPING, next.lowPingMode)
            .putString(K_UPD, next.updateCheck.name)
            .putBoolean(K_AUTOUPD, next.autoUpdate)
            .putString(K_DNS, next.dnsMode.name)
            .putString(K_DNS_CUSTOM, next.customDns)
            .putString(K_UNIT, next.speedUnit.name)
            .putBoolean(K_DL_MOBILE, next.downloadsUseMobile)

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

    @Synchronized
    fun wifiUsedToday(): Long {
        rollDay()
        return prefs.getLong(K_WIFI_USED, 0L)
    }

    @Synchronized
    fun addWifiUsage(bytes: Long) {
        if (bytes <= 0) return
        rollDay()
        prefs.edit().putLong(K_WIFI_USED, prefs.getLong(K_WIFI_USED, 0L) + bytes).apply()
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(K_DAY, null) != today) {
            prefs.edit().putString(K_DAY, today).putLong(K_USED, 0L).putLong(K_WIFI_USED, 0L).apply()
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
        private const val K_DNS = "dns_mode"
        private const val K_DNS_CUSTOM = "dns_custom"
        private const val K_UNIT = "speed_unit"
        private const val K_RULES = "app_rules"
        private const val K_LOWPING = "low_ping_mode"
        private const val K_DL_MOBILE = "dl_use_mobile"
        private const val K_WIFI_USED = "wifi_used"

        @Volatile private var instance: BoostSettings? = null

        fun get(context: Context): BoostSettings =
            instance ?: synchronized(this) {
                instance ?: BoostSettings(context).also { instance = it }
            }
    }
}
