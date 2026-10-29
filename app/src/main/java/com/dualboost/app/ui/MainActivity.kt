package com.dualboost.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.BoostState
import com.dualboost.app.core.Phase
import com.dualboost.app.service.BoostVpnService
import com.dualboost.app.update.UpdatePhase
import com.dualboost.app.update.UpdateWorker
import com.dualboost.app.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Screen { HOME, SETTINGS, UPDATES, APPS, SPEED, DOWNLOADS, DIAGNOSTICS, REPORT, BOND }

/** A request coming from a notification, shortcut, widget or share. */
private data class NavRequest(val screen: Screen? = null, val toggle: Boolean = false, val url: String? = null)

class MainActivity : ComponentActivity() {

    /** Set when the user taps an "update available" notification. */
    private val updateTapped = MutableStateFlow(false)
    private val navRequest = MutableStateFlow<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = BoostSettings.get(this)
        if (BoostState.ui.value.phase == Phase.OFF) {
            BoostState.update {
                it.copy(mobileTodayBytes = settings.mobileUsedToday(), wifiTodayBytes = settings.wifiUsedToday())
            }
        }
        // Act on a shortcut/widget/share intent only once: not again after rotation or process restore,
        // and not when the app is reopened from Recents (which replays the original intent).
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
        setContent {
            DualBoostTheme { App(settings, updateTapped, navRequest) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
            intent.removeExtra(EXTRA_OPEN_UPDATE)
            updateTapped.value = true
        }
        val req = when (intent.action) {
            ACTION_TOGGLE -> NavRequest(toggle = true)
            ACTION_SPEEDTEST -> NavRequest(Screen.SPEED)
            ACTION_DOWNLOADS -> NavRequest(Screen.DOWNLOADS)
            ACTION_DIAGNOSTICS -> NavRequest(Screen.DIAGNOSTICS)
            Intent.ACTION_SEND -> {
                val url = com.dualboost.app.core.Downloads.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
                if (url != null) {
                    com.dualboost.app.service.DownloadService.start(this, url)
                    Toast.makeText(this, "Downloading with both links…", Toast.LENGTH_SHORT).show()
                    NavRequest(Screen.DOWNLOADS)
                } else {
                    Toast.makeText(this, "No download link found in what was shared.", Toast.LENGTH_LONG).show()
                    null
                }
            }
            else -> null
        }
        if (req != null) {
            navRequest.value = req
            intent.action = null // don't repeat on rotation
        }
    }

    override fun onStart() {
        super.onStart()
        Updater.appInForeground = true
    }

    override fun onStop() {
        Updater.appInForeground = false
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
        const val ACTION_TOGGLE = "com.dualboost.app.action.TOGGLE"
        const val ACTION_SPEEDTEST = "com.dualboost.app.action.SPEEDTEST"
        const val ACTION_DOWNLOADS = "com.dualboost.app.action.DOWNLOADS"
        const val ACTION_DIAGNOSTICS = "com.dualboost.app.action.DIAGNOSTICS"
    }
}

@Composable
private fun App(settings: BoostSettings, updateTapped: MutableStateFlow<Boolean>, navRequest: MutableStateFlow<NavRequest?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by BoostState.ui.collectAsStateWithLifecycle()
    val s by settings.state.collectAsStateWithLifecycle()
    val update by Updater.ui.collectAsStateWithLifecycle()
    val tapped by updateTapped.collectAsStateWithLifecycle()
    // Back stack, saved as "HOME,SETTINGS,…" so it survives rotation.
    var stackStr by rememberSaveable { mutableStateOf("HOME") }
    val stack = stackStr.split(',').map { Screen.valueOf(it) }
    val screen = stack.last()
    fun go(to: Screen) { if (screen != to) stackStr = (stack + to).joinToString(",") { it.name } }
    fun back() { if (stack.size > 1) stackStr = stack.dropLast(1).joinToString(",") { it.name } }
    val nav by navRequest.collectAsStateWithLifecycle()
    val idle = rememberIdleLinks()

    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var canInstall by remember { mutableStateOf(Updater.canInstall(context)) }
    val selfInstalled = remember { Updater.isSelfInstalled(context) }

    fun installLatest() {
        scope.launch {
            val info = Updater.ui.value.info ?: Updater.check(context) ?: return@launch
            Updater.downloadAndInstall(context, info)
        }
    }

    // Every time the app comes to the front: refresh permissions and (if chosen) look for updates.
    LifecycleResumeEffect(Unit) {
        batteryOk = isBatteryUnrestricted(context)
        canInstall = Updater.canInstall(context)
        val cur = settings.current
        val stale = System.currentTimeMillis() - Updater.ui.value.lastChecked > 2 * 60_000
        val idleUpdater = Updater.ui.value.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING, UpdatePhase.WAITING_CONFIRM)
        if (cur.updateCheck.onOpen && stale && idleUpdater) {
            scope.launch {
                val info = Updater.check(context)
                if (info != null && cur.autoUpdate && Updater.canInstall(context)) Updater.downloadAndInstall(context, info)
            }
        }
        onPauseOrDispose { }
    }

    // Tapped an "update available" notification: that is the go-ahead.
    LaunchedEffect(tapped) {
        if (tapped) {
            updateTapped.value = false
            if (Updater.canInstall(context)) installLatest()
        }
    }

    fun allowInstalls() {
        open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) BoostVpnService.start(context)
        else Toast.makeText(context, "DualBoost needs the VPN permission to combine your connections.", Toast.LENGTH_LONG).show()
    }
    fun startWithVpnPermission() {
        val prep = VpnService.prepare(context)
        if (prep != null) {
            try { vpnLauncher.launch(prep) } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "This phone does not allow VPN apps.", Toast.LENGTH_LONG).show()
            }
        } else BoostVpnService.start(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startWithVpnPermission() // Works with or without the notification permission.
    }
    fun start() {
        BoostState.update { it.copy(error = null) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startWithVpnPermission()
    }

    BackHandler(stack.size > 1) { back() }

    // Shortcut / widget / share requests
    LaunchedEffect(nav) {
        val r = nav ?: return@LaunchedEffect
        navRequest.value = null
        if (r.toggle) {
            stackStr = "HOME"
            if (BoostState.ui.value.phase == Phase.ON) BoostVpnService.stop(context) else start()
        }
        r.screen?.let { stackStr = "HOME," + it.name }
    }

    val changeSettings: ((com.dualboost.app.core.SettingsSnapshot) -> com.dualboost.app.core.SettingsSnapshot) -> Unit = { block ->
        val before = settings.current.updateCheck
        settings.update(block)
        val after = settings.current.updateCheck
        if (after != before) {
            UpdateWorker.schedule(context, after, changed = true)
            if (after.background) scope.launch { Updater.check(context) }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSpeedUnit provides s.speedUnit) {
    Box(Modifier.fillMaxSize().background(Db.colors.bg)) {
        AnimatedContent(
            targetState = stackStr,
            transitionSpec = {
                val forward = targetState.count { it == ',' } >= initialState.count { it == ',' }
                (slideInHorizontally(tween(320)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(320)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(200)))
            },
            label = "screens",
        ) { targetStack ->
            when (Screen.valueOf(targetStack.substringAfterLast(','))) {
                Screen.HOME -> HomeScreen(
                    ui = ui,
                    idle = idle,
                    settings = s,
                    onToggle = { if (ui.phase == Phase.ON) BoostVpnService.stop(context) else start() },
                    onOpenSettings = { go(Screen.SETTINGS) },
                    onOpenSpeedTest = { go(Screen.SPEED) },
                    onOpenDownloads = { go(Screen.DOWNLOADS) },
                    onOpenDiagnostics = { go(Screen.DIAGNOSTICS) },
                    onOpenDeveloperOptions = { openDeveloperOptions(context) },
                    onDismissError = { BoostState.update { it.copy(error = null) } },
                    update = update,
                    canInstall = canInstall,
                    onUpdate = { installLatest() },
                    onAllowInstall = { allowInstalls() },
                )
                Screen.SETTINGS -> SettingsScreen(
                    s = s,
                    ui = ui,
                    batteryUnrestricted = batteryOk,
                    onChange = changeSettings,
                    onBack = { back() },
                    onOpenApps = { go(Screen.APPS) },
                    onOpenUpdates = { go(Screen.UPDATES) },
                    onOpenDiagnostics = { go(Screen.DIAGNOSTICS) },
                    onOpenReport = { go(Screen.REPORT) },
                    onOpenBond = { go(Screen.BOND) },
                    onAlwaysOn = { open(context, Intent(Settings.ACTION_VPN_SETTINGS)) },
                    onBattery = { openBattery(context, batteryOk) },
                    onDeveloper = { openDeveloperOptions(context) },
                    update = update,
                    canInstall = canInstall,
                    onUpdate = { installLatest() },
                    onAllowInstall = { allowInstalls() },
                )
                Screen.UPDATES -> UpdatesScreen(
                    u = update,
                    check = s.updateCheck,
                    autoUpdate = s.autoUpdate,
                    canInstall = canInstall,
                    selfInstalled = selfInstalled,
                    onCheckChange = { v -> changeSettings { it.copy(updateCheck = v) } },
                    onAutoChange = { v -> changeSettings { it.copy(autoUpdate = v) } },
                    onCheckNow = { scope.launch { Updater.check(context) } },
                    onUpdate = { installLatest() },
                    onAllow = { allowInstalls() },
                    onBack = { back() },
                )
                Screen.APPS -> AppRulesScreen(s = s, onChange = changeSettings, onBack = { back() })
                Screen.SPEED -> SpeedTestScreen(onBack = { back() })
                Screen.DOWNLOADS -> DownloadsScreen(
                    s = s, prefillUrl = null, onPrefillUsed = {}, onChange = changeSettings, onBack = { back() },
                )
                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                    onBack = { back() }, onSpeedTest = { go(Screen.SPEED) }, onReport = { go(Screen.REPORT) },
                )
                Screen.REPORT -> ReportScreen(onBack = { back() })
                Screen.BOND -> BondScreen(phase = ui.phase, bond = ui.bond, onBack = { back() }, onSpeedTest = { go(Screen.SPEED) })
            }
        }
    }
    }
}

/** Watches whether Wi-Fi and mobile data are available, for display while the boost is off. */
@Composable
private fun rememberIdleLinks(): IdleLinks {
    val context = LocalContext.current
    var wifi by remember { mutableStateOf(false) }
    var mobile by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun watch(transport: Int, set: (Boolean) -> Unit): ConnectivityManager.NetworkCallback {
            val nets = mutableSetOf<Network>()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { synchronized(nets) { nets += network; set(true) } }
                override fun onLost(network: Network) { synchronized(nets) { nets -= network; set(nets.isNotEmpty()) } }
            }
            val req = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching { cm.registerNetworkCallback(req, cb) }
            return cb
        }
        val a = watch(NetworkCapabilities.TRANSPORT_WIFI) { wifi = it }
        val b = watch(NetworkCapabilities.TRANSPORT_CELLULAR) { mobile = it }
        onDispose {
            runCatching { cm.unregisterNetworkCallback(a) }
            runCatching { cm.unregisterNetworkCallback(b) }
        }
    }
    return IdleLinks(wifi, mobile)
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun openBattery(context: Context, alreadyOk: Boolean) {
    if (!alreadyOk) {
        val req = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (open(context, req)) return
    }
    open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun openDeveloperOptions(context: Context) {
    val devOn = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    if (devOn && open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
        Toast.makeText(context, "Find \"Mobile data always active\" and turn it on.", Toast.LENGTH_LONG).show()
        return
    }
    open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    Toast.makeText(context, "Tap \"Build number\" 7 times to unlock Developer options.", Toast.LENGTH_LONG).show()
}

private fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) {
    false
}
