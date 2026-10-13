package com.dualboost.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dualboost.app.DualBoostApp
import com.dualboost.app.R
import com.dualboost.app.ui.MainActivity

object UpdateNotifications {
    const val CHANNEL_ID = "updates"
    private const val ID = 21

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new DualBoost version is ready"
            }
        )
    }

    /** New version found but not installed automatically: tap opens the app to update. */
    fun showAvailable(context: Context, version: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(context, "DualBoost $version is available", "Tap to update", open)
    }

    /** Downloaded and verified: tap to confirm the install. */
    fun showReady(context: Context, version: String?, confirm: PendingIntent) {
        post(context, "DualBoost ${version ?: ""} is ready".replace("  ", " "), "Tap to install the update", confirm)
    }

    private fun post(context: Context, title: String, text: String, pi: PendingIntent) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        DualBoostApp.notify(context, ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
