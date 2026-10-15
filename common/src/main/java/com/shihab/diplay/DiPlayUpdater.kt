package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import com.shihab.diplay.host.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Checks for signed GitHub Release updates without blocking the UI.
 * Download and OS installation always require user confirmation.
 */
internal class DiPlayUpdater(private val activity: DiPlayActivity) {
    init { DiPlayUpdateClient.configure(activity) }
    private val prefs = activity.getSharedPreferences("diplay_updates", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "diplay-updater").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var awaitingInstallPermission: File? = null
    private var pendingRelease: DiPlayUpdateClient.Release? = null
    private var publicInstallCopy: File? = null
    private var downloadDialog: DiPlayUpdateDialogs.ProgressHandle? = null

    fun autoEnabled(): Boolean = prefs.getBoolean("auto_check", true)
    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_check", enabled).apply()
    }

    fun checkAutomatically() {
        if (!autoEnabled() || closed) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_success", 0L) in 0 until 24L * 60 * 60 * 1000) return
        // Network access may be absent when the car boots; retry on a later launch after an hour.
        if (now - prefs.getLong("last_attempt", 0L) in 0 until 60L * 60 * 1000) return
        prefs.edit().putLong("last_attempt", now).apply()
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (closed) return
        if (busy) {
            if (manual) toast(R.string.update_checking)
            return
        }
        busy = true
        if (manual) toast(R.string.update_checking)
        worker.execute {
            try {
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    ?: throw IOException("Installed version unavailable")
                val release = DiPlayUpdateClient.latest(installed)
                if (!manual) prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                onUi {
                    if (release == null) {
                        if (manual) toast(R.string.update_up_to_date)
                    } else if (manual || !CarPlayBackgroundSession.hasSession()) {
                        showRelease(release)
                    } else {
                        pendingRelease = release // Do not interrupt an active CarPlay session.
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("DiPlayUpdate", "Release check failed", error)
                if (manual) onUi { showFailure(error) }
            } finally {
                busy = false
            }
        }
    }

    private fun showRelease(release: DiPlayUpdateClient.Release) {
        val notes = release.notes.ifBlank { activity.getString(R.string.update_no_notes) }
        DiPlayUpdateDialogs.available(
            activity, release.tag, notes, activity.getString(R.string.update_parked_notice),
            activity.getString(R.string.update_download),
            activity.getString(android.R.string.cancel),
        ) { download(release) }
    }

    private fun download(release: DiPlayUpdateClient.Release) {
        if (closed || busy) return
        if (CarPlayBackgroundSession.hasSession()) {
            showNotice(R.string.update_disconnect_first)
            return
        }
        cancelled = false
        busy = true
        publicInstallCopy = null
        downloadDialog = DiPlayUpdateDialogs.progress(activity) { cancelled = true }
        worker.execute {
            try {
                var lastUiTime = 0L
                var lastSampleTime = android.os.SystemClock.elapsedRealtime()
                var lastSampleBytes = 0L
                var downloadSpeed = 0L
                val file = DiPlayUpdateClient.downloadWithDetails(
                    release, File(activity.cacheDir, "diplay-updates"),
                ) { percent, bytes ->
                    if (cancelled || closed) throw IOException("Download cancelled")
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUiTime >= 180 || percent == 100) {
                        val duration = now - lastSampleTime
                        if (duration > 0) {
                            downloadSpeed = (bytes - lastSampleBytes).coerceAtLeast(0L) * 1000L / duration
                        }
                        lastSampleBytes = bytes
                        lastSampleTime = now
                        lastUiTime = now
                        onUi { downloadDialog?.update(percent, bytes, release.declaredSize, downloadSpeed) }
                    }
                }
                if (cancelled || closed) return@execute
                onUi { downloadDialog?.verifying(activity) }
                verifyPackage(file, release)
                onUi { downloadDialog?.preparing(activity) }
                // Legacy vendor installers may be unable to read FileProvider content://
                // URIs. Keep a second verified copy in Downloads for file:// or manual use.
                val sharedCopy = try {
                    DiPlayApkInstaller.createPublicCopy(activity, file) {
                        verifyPackage(it, release)
                    }
                } catch (error: Exception) {
                    // Public storage is optional. Do not reject a signed download on an OEM
                    // whose Downloads volume is missing or throws an unexpected exception.
                    android.util.Log.w("DiPlayUpdate", "Public APK copy unavailable", error)
                    null
                }
                if (cancelled || closed) return@execute
                onUi {
                    publicInstallCopy = sharedCopy
                    downloadDialog?.dismiss()
                    downloadDialog = null
                    showInstallPrompt(file, release.tag)
                }
            } catch (error: Exception) {
                android.util.Log.w("DiPlayUpdate", "Update download or verification failed", error)
                if (!cancelled) onUi { showFailure(error) }
            } finally {
                busy = false
                onUi { downloadDialog?.dismiss(); downloadDialog = null }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: DiPlayUpdateClient.Release) {
        // GET_SIGNATURES remains supported on Android 4.4+ and works on old vendor APK parsers.
        val pm = activity.packageManager
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IOException("Downloaded file is not a readable APK")
        if (candidate.packageName != installed.packageName ||
            !DiPlayUpdateClient.newerVersion(release.tag, installed.versionName ?: "") ||
            candidate.versionName != release.tag.removePrefix("v") ||
            candidate.versionCode <= installed.versionCode
        ) throw IOException("APK package or version does not match the release")
        val installedCerts = installed.signatures?.map { it.toCharsString() }?.sorted()
        val candidateCerts = candidate.signatures?.map { it.toCharsString() }?.sorted()
        if (installedCerts.isNullOrEmpty() || candidateCerts != installedCerts) {
            throw IOException("APK signing certificate differs from the installed DiPlay app")
        }
    }

    private fun showInstallPrompt(file: File, version: String) {
        val copiedToDownloads = publicInstallCopy
        val message = buildString {
            append(activity.getString(R.string.update_install_notice))
            if (copiedToDownloads != null) {
                append("\n\n")
                append(activity.getString(R.string.update_manual_saved, copiedToDownloads.absolutePath))
            }
        }
        DiPlayUpdateDialogs.installReady(
            activity, version, message,
            activity.getString(R.string.update_install),
            activity.getString(android.R.string.cancel),
        ) { requestInstall(file) }
    }

    private fun requestInstall(file: File) {
        if (CarPlayBackgroundSession.hasSession()) {
            showNotice(R.string.update_disconnect_first)
            return
        }
        val result = DiPlayApkInstaller.launch(activity, file, publicInstallCopy)
        if (result.launched) {
            awaitingInstallPermission = null
        } else {
            showInstallerUnavailable(result.error)
        }
    }

    private fun showInstallerUnavailable(error: Exception?) {
        android.util.Log.w("DiPlayUpdate", "No system package installer accepted the verified APK", error)
        val copy = publicInstallCopy
        val message = if (copy?.isFile == true) {
            activity.getString(R.string.update_manual_install_help, copy.absolutePath)
        } else {
            activity.getString(R.string.update_manual_install_no_copy)
        }
        DiPlayUpdateDialogs.confirm(
            activity,
            activity.getString(R.string.update_install_unavailable),
            message,
            activity.getString(android.R.string.ok),
        ) {}
    }

    private fun showNotice(message: Int) {
        DiPlayUpdateDialogs.confirm(
            activity,
            activity.getString(R.string.update_failed),
            activity.getString(message),
            activity.getString(android.R.string.ok),
        ) {}
    }

    fun onResume() {
        if (pendingRelease != null && !CarPlayBackgroundSession.hasSession()) {
            val release = pendingRelease!!
            pendingRelease = null
            showRelease(release)
        }
        val file = awaitingInstallPermission ?: return
    }

    fun close() {
        closed = true
        cancelled = true
        awaitingInstallPermission = null
        pendingRelease = null
        downloadDialog?.dismiss()
        downloadDialog = null
        worker.shutdownNow()
    }

    private fun showFailure(error: Exception) {
        DiPlayUpdateDialogs.confirm(
            activity,
            activity.getString(R.string.update_failed),
            if (DiPlayUpdateClient.isObsoleteSslv3Failure(error))
                activity.getString(R.string.update_tls_unavailable)
            else error.message ?: error.javaClass.simpleName,
            activity.getString(android.R.string.ok),
        ) {}
    }

    private fun onUi(action: () -> Unit) {
        activity.runOnUiThread {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
