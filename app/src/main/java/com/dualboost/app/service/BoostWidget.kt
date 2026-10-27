package com.dualboost.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.dualboost.app.R
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.BoostState
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.LinkStatus
import com.dualboost.app.core.Phase
import com.dualboost.app.ui.MainActivity
import com.dualboost.app.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget: live combined speed, link split, and a Start/Stop pill. */
class BoostWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, BoostState.ui.value, manager, ids)
    }

    companion object {
        private var lastPhase: Phase? = null
        private var lastAt = 0L
        @Volatile private var started = false

        /** Keeps widgets in step with the boost: on every phase change, and every 2 s while running. */
        fun startSync(context: Context) {
            if (started) return
            started = true
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                BoostState.ui.collect { ui ->
                    val now = System.currentTimeMillis()
                    val phaseChanged = ui.phase != lastPhase
                    if (phaseChanged || (ui.phase == Phase.ON && now - lastAt >= 2_000)) {
                        lastPhase = ui.phase; lastAt = now
                        runCatching { update(app, ui) }
                    }
                }
            }
        }

        fun update(context: Context, ui: BoostUi) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BoostWidget::class.java))
            if (ids.isNotEmpty()) render(context, ui, manager, ids)
        }

        private fun render(context: Context, ui: BoostUi, manager: AppWidgetManager, ids: IntArray) {
            val unit = BoostSettings.get(context).current.speedUnit
            val on = ui.phase == Phase.ON
            val busy = ui.phase == Phase.STARTING || ui.phase == Phase.STOPPING
            val v = RemoteViews(context.packageName, R.layout.widget_boost)

            val bothUp = ui.wifi.status == LinkStatus.READY && ui.mobile.status == LinkStatus.READY
            v.setTextViewText(R.id.widget_status, when (ui.phase) {
                Phase.ON -> if (bothUp) "Boosting" else "Running"
                Phase.STARTING -> "Starting…"
                Phase.STOPPING -> "Stopping…"
                Phase.OFF -> "Off"
            })
            val dotColor = ContextCompat.getColor(context, when {
                on && bothUp -> R.color.widget_good
                on || busy -> R.color.widget_mobile
                else -> R.color.widget_muted
            })
            v.setInt(R.id.widget_status_dot, "setColorFilter", dotColor)

            if (on) {
                val total = formatSpeed(ui.totalDown, unit)
                v.setTextViewText(R.id.widget_speed, total.value)
                v.setTextViewText(R.id.widget_unit, total.unit)
                v.setTextViewText(
                    R.id.widget_links,
                    "Wi-Fi ${formatSpeed(ui.wifi.downMbps, unit).value} · Mobile ${formatSpeed(ui.mobile.downMbps, unit).value}",
                )
            } else {
                v.setTextViewText(R.id.widget_speed, if (busy) "…" else "Off")
                v.setTextViewText(R.id.widget_unit, "")
                v.setTextViewText(R.id.widget_links, if (busy) "Connecting both links" else "Tap Start to combine")
            }

            // Button: blue "Start boost" when off, orange "Stop boost" when running (same as the app).
            val stopMode = on || ui.phase == Phase.STOPPING
            v.setTextViewText(R.id.widget_button, when (ui.phase) {
                Phase.OFF -> "Start boost"; Phase.STARTING -> "Starting…"
                Phase.ON -> "Stop boost"; Phase.STOPPING -> "Stopping…"
            })
            v.setInt(R.id.widget_button, "setBackgroundResource",
                if (stopMode) R.drawable.widget_pill_orange else R.drawable.widget_pill_blue)
            v.setOnClickPendingIntent(R.id.widget_button, toggleIntent(context, on))
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            manager.updateAppWidget(ids, v)
        }

        private fun toggleIntent(context: Context, running: Boolean): PendingIntent = when {
            running -> PendingIntent.getService(
                context, 11, Intent(context, BoostVpnService::class.java).setAction(BoostVpnService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // VPN permission already granted: start straight from the widget.
            VpnService.prepare(context) == null -> {
                val start = Intent(context, BoostVpnService::class.java).setAction(BoostVpnService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(
                    context, 12, start, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ) else PendingIntent.getService(context, 12, start, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            // First time: the app must ask for the VPN permission.
            else -> PendingIntent.getActivity(
                context, 13,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_TOGGLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
