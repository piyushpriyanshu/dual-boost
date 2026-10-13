package com.dualboost.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.dualboost.app.core.BoostSettings

/** Restores the boost after a reboot (if enabled) or after the app is updated while running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = BoostSettings.get(context)
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> settings.current.autoStart && settings.wantRunning
            Intent.ACTION_MY_PACKAGE_REPLACED -> settings.wantRunning
            else -> false
        }
        if (shouldStart && VpnService.prepare(context) == null) {
            runCatching { BoostVpnService.start(context) }
        }
    }
}
