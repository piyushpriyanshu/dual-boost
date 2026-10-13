package com.dualboost.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dualboost.app.core.BoostState
import com.dualboost.app.core.Phase
import com.dualboost.app.ui.MainActivity
import com.dualboost.app.ui.formatSpeed
import com.dualboost.app.core.BoostSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap to turn the boost on or off. */
class BoostTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(Dispatchers.Main.immediate)
        scope = s
        job = s.launch { BoostState.ui.collectLatest { render() } }
    }

    override fun onStopListening() {
        job?.cancel(); job = null; scope = null
        super.onStopListening()
    }

    private fun render() {
        val tile = qsTile ?: return
        val ui = BoostState.ui.value
        tile.state = when (ui.phase) {
            Phase.ON, Phase.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (ui.phase) {
                Phase.ON -> formatSpeed(ui.totalDown, BoostSettings.get(this).current.speedUnit).toString()
                Phase.STARTING -> "Starting…"
                else -> "Off"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val ui = BoostState.ui.value
        if (ui.phase == Phase.ON || ui.phase == Phase.STARTING) {
            BoostVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            // First time: the VPN permission dialog must be shown from the app.
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        BoostVpnService.start(this)
    }
}
