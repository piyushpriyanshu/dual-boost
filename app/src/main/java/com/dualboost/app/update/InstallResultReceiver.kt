package com.dualboost.app.update

import com.dualboost.app.core.Diag
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Receives the result of an update install session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updater.onInstallerResult(UpdatePhase.ERROR, "Android didn't show the install dialog.")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.onInstallerResult(UpdatePhase.WAITING_CONFIRM, "Confirm the update in Android's dialog.")
                val shown = Updater.appInForeground && runCatching { context.startActivity(confirm) }.isSuccess
                if (!shown) {
                    val pi = PendingIntent.getActivity(
                        context, 42, confirm,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    UpdateNotifications.showReady(context, Updater.ui.value.info?.versionName, pi)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateNotifications.cancel(context)
                Updater.onInstallerResult(UpdatePhase.UP_TO_DATE, null)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.onInstallerResult(UpdatePhase.AVAILABLE, "Update cancelled. Tap Update to try again.")
            }
            else -> {
                Diag.w("DualBoost/Update", "install failed: $status $msg")
                Updater.onInstallerResult(UpdatePhase.ERROR, "Install failed: ${msg ?: "status $status"}")
            }
        }
    }
}
