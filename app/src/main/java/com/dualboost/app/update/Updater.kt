package com.dualboost.app.update

import com.dualboost.app.core.Diag
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dualboost.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer build published on GitHub Releases. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
    val notes: String,
)

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, WAITING_CONFIRM, ERROR }

data class UpdateUi(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val info: UpdateInfo? = null,
    /** 0..1 while downloading. */
    val progress: Float = 0f,
    val lastChecked: Long = 0,
    val message: String? = null,
)

/**
 * Self-updater backed by this repository's GitHub Releases.
 *
 * 1. Reads the latest release from the public GitHub API.
 * 2. Downloads the APK, verifies its SHA-256 (published by the build) and that it is a newer DualBoost.
 * 3. Installs it with PackageInstaller. Once DualBoost has installed itself once, Android 12+ lets
 *    later updates go through without a prompt; otherwise Android shows its normal "Update" dialog.
 *
 * Android itself refuses any APK not signed with the same key, so a tampered file can never install.
 */
object Updater {
    private const val TAG = "DualBoost/Update"
    private const val PREFS = "updater"
    private const val K_LAST = "last_check"

    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private val lock = Mutex()

    /** Set by the activity so install prompts can open directly instead of via a notification. */
    @Volatile var appInForeground = false

    fun init(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_LAST, 0)
        _ui.value = _ui.value.copy(lastChecked = last)
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Checks GitHub for a newer release. Returns it, or null when up to date / on error. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        lock.withLock {
            val busy = _ui.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)
            if (busy) return@withLock _ui.value.info
            _ui.value = _ui.value.copy(phase = UpdatePhase.CHECKING, message = null)
            try {
                val info = fetchLatest()
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(K_LAST, now).apply()
                if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                    _ui.value = UpdateUi(UpdatePhase.AVAILABLE, info, 0f, now)
                    info
                } else {
                    _ui.value = UpdateUi(UpdatePhase.UP_TO_DATE, null, 0f, now)
                    null
                }
            } catch (e: Exception) {
                Diag.w(TAG, "check failed", e)
                _ui.value = _ui.value.copy(phase = UpdatePhase.ERROR, message = "Couldn't reach GitHub. Check your connection.")
                null
            }
        }
    }

    /** Downloads, verifies and hands the APK to Android's installer. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (!canInstall(context)) {
                    _ui.value = _ui.value.copy(
                        phase = UpdatePhase.AVAILABLE, info = info,
                        message = "Allow DualBoost to install updates first.",
                    )
                    return@withLock false
                }
                _ui.value = _ui.value.copy(phase = UpdatePhase.DOWNLOADING, info = info, progress = 0f, message = null)
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val apk = File(dir, "DualBoost-${info.versionName}.apk")
                download(info.apkUrl, apk, info.apkSize)

                // Integrity: checksum published by the build workflow.
                info.sha256Url?.let { url ->
                    val expected = httpGet(url).trim().split(Regex("\\s+")).first().lowercase()
                    val actual = sha256(apk)
                    if (expected.length == 64 && expected != actual) throw IOException("Checksum mismatch")
                }
                // It must be DualBoost and newer than what is installed.
                val pm = context.packageManager
                val archive = pm.getPackageArchiveInfo(apk.path, 0) ?: throw IOException("Downloaded file is not a valid app")
                val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
                if (archive.packageName != context.packageName || archiveCode <= BuildConfig.VERSION_CODE) {
                    throw IOException("Downloaded file is not a newer DualBoost")
                }

                _ui.value = _ui.value.copy(phase = UpdatePhase.INSTALLING, progress = 1f)
                install(context, apk)
                true
            } catch (e: Exception) {
                Diag.w(TAG, "update failed", e)
                _ui.value = _ui.value.copy(phase = UpdatePhase.ERROR, message = "Update failed: ${e.message ?: "unknown error"}")
                false
            }
        }
    }

    internal fun onInstallerResult(phase: UpdatePhase, message: String?) {
        _ui.value = _ui.value.copy(phase = phase, message = message)
    }

    // ------------------------------------------------------------------ GitHub

    private fun fetchLatest(): UpdateInfo? {
        val json = JSONObject(httpGet("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
        val tag = json.optString("tag_name")
        // Tags look like v1.0.<versionCode>
        val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkSize = 0L
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk") -> { apkUrl = a.optString("browser_download_url"); apkSize = a.optLong("size") }
                name.endsWith(".apk.sha256") -> shaUrl = a.optString("browser_download_url")
            }
        }
        if (apkUrl.isNullOrEmpty()) return null
        val notes = json.optString("body").lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("What's new:") }
            ?.removePrefix("What's new:")?.trim()
            ?: ""
        return UpdateInfo(code, tag.removePrefix("v"), apkUrl, apkSize, shaUrl, notes)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DualBoost/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
        }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, out: File, expectedSize: Long) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _ui.value = _ui.value.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (expectedSize > 0 && done != expectedSize) throw IOException("Download incomplete")
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ install

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    /** True when the installed copy came from DualBoost itself, so updates can be silent. */
    fun isSelfInstalled(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= 30)
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        installer == context.packageName
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
