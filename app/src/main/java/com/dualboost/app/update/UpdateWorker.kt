package com.dualboost.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.UpdateCheck
import java.util.concurrent.TimeUnit

/** Background check every 6 hours. Installs automatically when allowed, otherwise notifies. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!BoostSettings.get(ctx).current.updateCheck.background) return Result.success()
        Updater.init(ctx)
        val info = Updater.check(ctx) ?: return Result.success()
        val auto = BoostSettings.get(ctx).current.autoUpdate
        if (auto && Updater.canInstall(ctx)) {
            // Silent when DualBoost installed itself before (Android 12+); otherwise a "tap to install" notification.
            if (!Updater.downloadAndInstall(ctx, info)) UpdateNotifications.showAvailable(ctx, info.versionName)
        } else {
            UpdateNotifications.showAvailable(ctx, info.versionName)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "dualboost-update-check"

        /**
         * Applies the user's choice: a periodic background check every 1/3/6 hours,
         * or no background work for "When I open the app" and "Manual only".
         * [changed] = the user just picked a new interval, so the timer restarts.
         */
        fun schedule(context: Context, check: UpdateCheck, changed: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            if (!check.background) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val flexMinutes = (check.hours * 60 / 4).coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                check.hours, TimeUnit.HOURS, flexMinutes, TimeUnit.MINUTES,
            )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("every-${check.hours}h")
                .build()
            wm.enqueueUniquePeriodicWork(
                NAME,
                if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
