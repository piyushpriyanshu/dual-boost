package com.dualboost.app.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Diagnostic event log used for problem reports.
 *
 * Keeps the most recent events in memory and in a small file (so they survive restarts),
 * and records any crash with its stack trace. Contains no browsing data: only app events
 * such as "Wi-Fi lost", "probe failed", "boost started", errors and timings.
 */
object Diag {
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val KEEP_FILE_BYTES = 128 * 1024

    private val lines = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null
    @Volatile private var crashFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        logFile = File(dir, "events.log")
        crashFile = File(dir, "last-crash.txt")
        // Load the tail of the previous session so a report can include what happened before a restart.
        runCatching {
            logFile?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES / 2)?.let {
                synchronized(lines) { lines.addAll(it) }
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile?.writeText("${stamp.format(Date())} crash in thread ${t.name}\n${stack(e)}")
                e("Crash", "uncaught exception in ${t.name}: $e")
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("I", tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); add("W", tag, msg + (t?.let { ": $it" } ?: "")) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); add("E", tag, msg + (t?.let { ": $it" } ?: "")) }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${stamp.format(Date())} $level ${tag.removePrefix("DualBoost/")}: $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val f = logFile ?: return
        runCatching {
            writer.execute {
                runCatching {
                    f.appendText(line + "\n")
                    if (f.length() > MAX_FILE_BYTES) {
                        val text = f.readText()
                        f.writeText(text.takeLast(KEEP_FILE_BYTES).substringAfter('\n'))
                    }
                }
            }
        }
    }

    /** Most recent [n] events, oldest first. */
    fun recent(n: Int = MAX_LINES): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun lastCrash(): String? = runCatching { crashFile?.takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { logFile?.delete(); crashFile?.delete() }
    }

    fun stack(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
