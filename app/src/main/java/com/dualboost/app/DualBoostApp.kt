package com.dualboost.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.Diag
import com.dualboost.app.update.UpdateNotifications
import com.dualboost.app.update.UpdateWorker
import com.dualboost.app.update.Updater

class DualBoostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.dualboost.app.core.BondConfig.get(this) // creates the bond key on first run
        Diag.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows live speed while DualBoost is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        UpdateNotifications.createChannel(this)
        com.dualboost.app.service.DownloadService.createChannels(this)
        com.dualboost.app.core.Downloads.init(this)
        com.dualboost.app.service.BoostWidget.startSync(this)
        Updater.init(this)
        UpdateWorker.schedule(this, BoostSettings.get(this).current.updateCheck)
    }

    companion object {
        const val CHANNEL_ID = "boost"

        fun notify(context: Context, id: Int, n: Notification) {
            try {
                context.getSystemService(NotificationManager::class.java).notify(id, n)
            } catch (_: SecurityException) {
                // Notification permission not granted: the service keeps running regardless.
            }
        }
    }
}
