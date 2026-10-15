package com.shihab.diplay

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDexApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiPlayApplication : MultiDexApplication() {

    // DIAGNOSTIC BUILD: writes an early boot marker log to /sdcard so that a crash
    // happening BEFORE the crash handler is installed (Application class load /
    // MultiDex.install in attachBaseContext) can still be located. SafeStackTrace is
    // deliberately NOT used here: this class must not pull in secondary-dex types
    // before multidex has finished loading, which could itself be the crash source.
    override fun attachBaseContext(base: Context) {
        bootLog("attach-start")
        try {
            super.attachBaseContext(base)
            bootLog("attach-after-super-ok")
        } catch (t: Throwable) {
            bootLog("attach-EXCEPTION: " + t.javaClass.name + ": " + t.message)
            try {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                bootLog(sw.toString())
            } catch (ignored: Throwable) {
            }
            throw t
        }
    }

    override fun onCreate() {
        bootLog("oncreate-start")
        super.onCreate()
        bootLog("oncreate-after-super")
        setupCrashHandler()
        bootLog("handler-registered")
        // Apply the root-level "hide system navigation bar globally" on cold start when enabled.
        // Background thread + runCatching so a missing / failing su can never crash startup.
        if (RootNavbar.hiddenRequested(this)) {
            bootLog("rootnavbar-requested-true")
            Thread(
                {
                    runCatching { RootNavbar.applyHidden(true) }
                    bootLog("rootnavbar-done")
                },
                "root-navbar",
            ).start()
        } else {
            bootLog("rootnavbar-requested-false")
        }
        bootLog("oncreate-done")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(this, thread, throwable)
            } catch (ignored: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Writes a marker line to /sdcard (and app-private dir) without touching secondary-dex APIs. */
    private fun bootLog(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + msg + "\n"
        try {
            val f = File("/sdcard/diplay-boot.log")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText(line)
        } catch (ignored: Throwable) {
        }
        try {
            File(applicationContext.filesDir, "diplay-boot.log").appendText(line)
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val TAG = "DiPlayApplication"

        fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))
            val stackTrace = stringWriter.toString()

            val logEntry = buildString {
                append("\n================ FATAL EXCEPTION ================\n")
                append("Time: ").append(timestamp).append("\n")
                append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n")
                append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
                append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
                append("Exception: ").append(stackTrace).append("\n")
                append("=================================================\n")
            }

            Log.e(TAG, logEntry)

            // 1. Always write to private app directory (preserved)
            runCatching {
                val logDir = File(context.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "diplay_crash.log")
                logFile.appendText(logEntry)
            }

            // 2. Also write to app-specific external files dir if available (accessible via MTP/file manager)
            runCatching {
                val externalDir = context.getExternalFilesDir("logs")
                if (externalDir != null) {
                    if (!externalDir.exists()) externalDir.mkdirs()
                    File(externalDir, "diplay_crash.log").appendText(logEntry)
                }
            }

            // 3. On legacy Android (API <= 28), also attempt public locations for U-disk / file manager copy.
            if (Build.VERSION.SDK_INT <= 28) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                    if (downloads != null) {
                        if (!downloads.exists()) downloads.mkdirs()
                        if (downloads.canWrite()) {
                            File(downloads, "diplay-crash.log").appendText(logEntry)
                        }
                    }
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    val sdcardDir = android.os.Environment.getExternalStorageDirectory()
                    if (sdcardDir != null && sdcardDir.canWrite()) {
                        File(sdcardDir, "diplay-crash.log").appendText(logEntry)
                        File(sdcardDir, "diplay-crash.log").appendText(logEntry)
                    }
                }
            }
        }
    }
}
