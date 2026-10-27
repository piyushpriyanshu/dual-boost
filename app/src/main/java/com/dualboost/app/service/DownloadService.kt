package com.dualboost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dualboost.app.DualBoostApp
import com.dualboost.app.R
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.DlStatus
import com.dualboost.app.core.DownloadUi
import com.dualboost.app.core.Downloads
import com.dualboost.app.core.LinkMonitor
import com.dualboost.app.ui.MainActivity
import com.dualboost.app.ui.formatBytes
import com.dualboost.app.ui.formatSpeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runs boosted downloads in the foreground so they continue when the app is in the background. */
class DownloadService : Service() {

    private var links: LinkMonitor? = null
    private val running = AtomicInteger()
    private val lastNotify = ConcurrentHashMap<Long, Long>()
    /** Which notification id each download is currently shown under, so a move never leaves a stale one. */
    private val shownAs = ConcurrentHashMap<Long, Int>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        createChannels(this)
        when (intent?.action) {
            ACTION_CANCEL -> Downloads.cancel(intent.getLongExtra(EXTRA_ID, 0))
            ACTION_PAUSE -> Downloads.pause(intent.getLongExtra(EXTRA_ID, 0))
            ACTION_START, ACTION_RESUME -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val resumeId = intent.getLongExtra(EXTRA_ID, 0).takeIf { intent.action == ACTION_RESUME && it != 0L }
                // startForegroundService() requires startForeground() on every such start.
                goForeground()
                if (url != null || resumeId != null) launch(url, resumeId)
            }
        }
        maybeStop()
        return START_NOT_STICKY
    }

    /**
     * Ends the service only when nothing is running, on the main thread (same thread as onStartCommand),
     * and only if no newer start arrived meanwhile: stopSelfResult(lastStartId) refuses otherwise.
     */
    private fun maybeStop() {
        main.post {
            if (running.get() != 0) return@post
            synchronized(this) { links?.stop(); links = null }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
        }
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Preparing download…")
            .setOngoing(true).setSilent(true)
            .setContentIntent(openApp(this))
            .build()
        ServiceCompat.startForeground(
            this, SUMMARY_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    @Synchronized
    private fun linkMonitor(): LinkMonitor =
        links ?: LinkMonitor(this) {}.also { it.start(); links = it }

    private fun launch(url: String?, resumeId: Long?) {
        if (resumeId != null && Downloads.isRunning(resumeId)) return
        running.incrementAndGet()
        val lm = linkMonitor()
        Thread({
            try {
                val result = Downloads.run(applicationContext, url, resumeId, lm) { ui -> notifyProgress(ui) }
                if (result != null) notifyFinished(result)
            } finally {
                if (running.decrementAndGet() == 0) maybeStop()
            }
        }, "download").apply { isDaemon = true; start() }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits data-sync services to 6 h/day. Pause (progress is kept), never cancel.
        Downloads.items.value.filter { it.active }.forEach { Downloads.pause(it.id) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(this) { links?.stop(); links = null }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notifications

    private fun notifyProgress(ui: DownloadUi) {
        if (!ui.active) return
        val now = System.currentTimeMillis()
        if (now - (lastNotify[ui.id] ?: 0) < 900) return
        lastNotify[ui.id] = now
        val unit = BoostSettings.get(this).current.speedUnit
        val cancel = PendingIntent.getService(
            this, ui.id.toInt(), Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, ui.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (ui.status == DlStatus.PREPARING) "Connecting…"
        else if (ui.status == DlStatus.WAITING) "Waiting for internet… progress is saved"
        else buildString {
            append(formatBytes(ui.done))
            if (ui.size > 0) append(" of ${formatBytes(ui.size)}")
            append(" · ${formatSpeed(ui.totalMbps, unit)}")
            if (ui.split) append(" (Wi-Fi ${formatSpeed(ui.wifiMbps, unit).value} · Mobile ${formatSpeed(ui.mobileMbps, unit).value})")
            if (ui.etaSec >= 0) append(" · ${eta(ui.etaSec)} left")
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(ui.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, (ui.fraction * 100).toInt(), ui.size <= 0 || ui.status == DlStatus.PREPARING)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentIntent(openApp(this))
            .apply {
                if (ui.split) addAction(0, "Pause", PendingIntent.getService(
                    this@DownloadService, ui.id.toInt() + 7,
                    Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, ui.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }
            .addAction(0, "Cancel", cancel)
            .build()
        // The first active download shares the foreground notification; others get their own.
        val first = Downloads.items.value.firstOrNull { it.active }?.id == ui.id
        val nid = if (first) SUMMARY_ID else ui.id.toInt()
        val prev = shownAs.put(ui.id, nid)
        if (prev != null && prev != nid && prev != SUMMARY_ID) getSystemService(NotificationManager::class.java).cancel(prev)
        DualBoostApp.notify(this, nid, n)
    }

    private fun notifyFinished(ui: DownloadUi) {
        lastNotify.remove(ui.id)
        shownAs.remove(ui.id)
        getSystemService(NotificationManager::class.java).cancel(ui.id.toInt())
        if (ui.status == DlStatus.CANCELLED) return
        if (ui.status == DlStatus.PAUSED && ui.error == null) return // paused by the user
        val ok = ui.status == DlStatus.DONE
        val tap = if (ok && ui.uri != null) PendingIntent.getActivity(
            this, ui.id.toInt(),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ui.uri), ui.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        ) else openApp(this)
        val unit = BoostSettings.get(this).current.speedUnit
        val n = NotificationCompat.Builder(this, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(when {
                ok -> ui.name
                ui.status == DlStatus.PAUSED -> "Download paused: ${ui.name}"
                else -> "Download failed: ${ui.name}"
            })
            .setContentText(
                if (ok) "${formatBytes(ui.done)} · average ${formatSpeed(ui.avgMbps, unit)} · tap to open"
                else ui.error ?: "Tap to see details"
            )
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        DualBoostApp.notify(this, (ui.id + 1).toInt(), n)
    }

    companion object {
        const val ACTION_START = "com.dualboost.app.download.START"
        const val ACTION_CANCEL = "com.dualboost.app.download.CANCEL"
        const val ACTION_PAUSE = "com.dualboost.app.download.PAUSE"
        const val ACTION_RESUME = "com.dualboost.app.download.RESUME"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "downloads"
        private const val DONE_CHANNEL = "downloads_done"
        private const val SUMMARY_ID = 31

        fun start(context: Context, url: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_URL, url),
            )
        }

        fun resume(context: Context, id: Long, url: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, DownloadService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_ID, id).putExtra(EXTRA_URL, url),
            )
        }

        fun pause(context: Context, id: Long) {
            runCatching {
                context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, id))
            }
        }

        fun eta(sec: Long): String = when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60} min"
            else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        }

        fun cancel(context: Context, id: Long) {
            runCatching {
                context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
            }
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
