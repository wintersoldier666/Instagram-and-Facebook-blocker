package com.quell.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val LOG_FILE = "quell_crash.log"
    private const val MAX_LOG_BYTES = 512_000L // 512 KB cap — rotate old entries
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private lateinit var logFile: File

    fun install(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)

        // Rotate if too large
        if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
            val backup = File(context.filesDir, "quell_crash_old.log")
            logFile.renameTo(backup)
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val entry = buildString {
                    append("\n\n=== CRASH @ ${timestampFmt.format(Date())} ===\n")
                    append("Thread: ${thread.name} (id=${thread.id})\n")
                    append(sw.toString())
                    append("===\n")
                }
                logFile.appendText(entry)
                Log.e("QuellCrash", entry)
            } catch (_: Exception) { /* never let logging crash the crash handler */ }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Append a non-fatal message (service errors, etc.) */
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        if (!::logFile.isInitialized) return
        try {
            val sw = StringWriter()
            throwable?.printStackTrace(PrintWriter(sw))
            val entry = buildString {
                append("[${timestampFmt.format(Date())}] [$tag] $message\n")
                if (throwable != null) append(sw.toString())
            }
            logFile.appendText(entry)
            Log.e(tag, message, throwable)
        } catch (_: Exception) {}
    }

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILE)

    fun clearLog(context: Context) {
        File(context.filesDir, LOG_FILE).delete()
        File(context.filesDir, "quell_crash_old.log").delete()
    }
}
