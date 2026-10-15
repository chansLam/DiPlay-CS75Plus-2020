package com.shihab.diplay

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Launches an OEM package installer without silently installing an APK.
 * Android 4.x package installers may not support FileProvider content URIs.
 * For those devices, make a separately verified copy in public Downloads.
 */
internal object DiPlayApkInstaller {
    private const val TAG = "DiPlayUpdate"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** A malformed SDK_INT on an old head unit must not select a legacy file URI on Android 7+. */
    internal fun supportsFileInstall(sdk: Int, release: String): Boolean =
        sdk in 19..23

    internal fun contentIntents(uri: Uri): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )

    internal fun fileIntents(file: File): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), APK_MIME)
        },
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = Uri.fromFile(file)
        },
    )

    /**
     * Copy the verified update to an old Android user's Downloads folder.
     * A unique filename avoids replacing or following an existing shared-storage file.
     * The destination digest must match the source, and the caller validates its APK signature.
     */
    fun createPublicCopy(context: Context, verifiedApk: File, verify: (File) -> Unit): File? {
        if (!supportsFileInstall(Build.VERSION.SDK_INT, Build.VERSION.RELEASE.orEmpty()) &&
            !Build.VERSION.RELEASE.orEmpty().startsWith("4.") &&
            !Build.VERSION.RELEASE.orEmpty().startsWith("5.")) return null
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.isDirectory && !downloads.mkdirs()) return null
        val name = verifiedApk.name.removeSuffix(".apk").take(90).ifBlank { "DiPlay-update" }
        var destination: File? = null
        try {
            val target = File.createTempFile("$name-", ".apk", downloads)
            destination = target
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val copyDigest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            verifiedApk.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sourceDigest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        count += read
                    }
                }
            }
            target.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copyDigest.update(buffer, 0, read)
                }
            }
            if (count != verifiedApk.length() || target.length() != count ||
                !sourceDigest.digest().contentEquals(copyDigest.digest())
            ) throw IOException("Shared APK copy differs from the verified download")
            verify(target)
            return target
        } catch (error: Exception) {
            Log.w(TAG, "Unable to make a verified public Downloads copy", error)
            destination?.delete()
            return null
        }
    }

    data class LaunchResult(val launched: Boolean, val error: Exception? = null)

    fun launch(context: Context, verifiedApk: File, publicCopy: File?): LaunchResult {
        val intents = mutableListOf<Intent>()
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.apk-updates", verifiedApk,
            )
            intents += contentIntents(uri).onEach {
                it.clipData = ClipData.newUri(context.contentResolver, "DiPlay update", uri)
            }
        } catch (error: Exception) {
            Log.w(TAG, "FileProvider update URI unavailable", error)
        }
        // Android 4.4's PackageInstaller often understands file:// but not content://.
        if (publicCopy?.isFile == true && supportsFileInstall(Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE.orEmpty())) {
            intents.addAll(0, fileIntents(publicCopy))
        }
        var failure: Exception? = null
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return LaunchResult(true)
            } catch (error: Exception) {
                Log.w(TAG, "System installer rejected ${intent.action} (${intent.data?.scheme})", error)
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        return LaunchResult(false, failure ?: IOException("No system package installer accepted the APK"))
    }
}
