package com.dualboost.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class DualBoostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows live speed while DualBoost is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
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
