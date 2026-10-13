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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Screen { HOME, SETTINGS, APPS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = BoostSettings.get(this)
        if (BoostState.ui.value.phase == Phase.OFF) {
            BoostState.update { it.copy(mobileTodayBytes = settings.mobileUsedToday()) }
        }
        setContent {
            DualBoostTheme { App(settings) }
        }
    }
}

@Composable
private fun App(settings: BoostSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by BoostState.ui.collectAsStateWithLifecycle()
    val s by settings.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val idle = rememberIdleLinks()

    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        batteryOk = isBatteryUnrestricted(context)
        onPauseOrDispose { }
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

    BackHandler(screen != Screen.HOME) {
        screen = if (screen == Screen.APPS) Screen.SETTINGS else Screen.HOME
    }

    Box(Modifier.fillMaxSize().background(Db.colors.bg)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(320)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(320)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(200)))
            },
            label = "screens",
        ) { target ->
            when (target) {
                Screen.HOME -> HomeScreen(
                    ui = ui,
                    idle = idle,
                    mobileCapMb = s.mobileCapMb,
                    onToggle = { if (ui.phase == Phase.ON) BoostVpnService.stop(context) else start() },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenDeveloperOptions = { openDeveloperOptions(context) },
                    onDismissError = { BoostState.update { it.copy(error = null) } },
                )
                Screen.SETTINGS -> SettingsScreen(
                    s = s,
                    running = ui.phase == Phase.ON,
                    batteryUnrestricted = batteryOk,
                    onChange = { settings.update(it) },
                    onBack = { screen = Screen.HOME },
                    onOpenApps = { screen = Screen.APPS },
                    onRestart = {
                        scope.launch {
                            BoostVpnService.stop(context)
                            withTimeoutOrNull(8_000) { BoostState.ui.first { it.phase == Phase.OFF } }
                            BoostVpnService.start(context)
                            screen = Screen.HOME
                        }
                    },
                    onAlwaysOn = { open(context, Intent(Settings.ACTION_VPN_SETTINGS)) },
                    onBattery = { openBattery(context, batteryOk) },
                    onDeveloper = { openDeveloperOptions(context) },
                )
                Screen.APPS -> AppsScreen(
                    excluded = s.excludedApps,
                    onToggle = { pkg, on ->
                        settings.update { cur ->
                            cur.copy(excludedApps = if (on) cur.excludedApps + pkg else cur.excludedApps - pkg)
                        }
                    },
                    onBack = { screen = Screen.SETTINGS },
                )
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
