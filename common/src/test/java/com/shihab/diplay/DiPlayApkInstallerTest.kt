package com.shihab.diplay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class DiPlayApkInstallerTest {
    @Test fun installerSupportsContentAndLegacyFileIntents() {
        val content = Uri.parse("content://com.shihab.diplay.apk-updates/updates/verified.apk")
        val contentIntents = DiPlayApkInstaller.contentIntents(content)
        assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE),
            contentIntents.map { it.action })
        assertTrue(contentIntents.all {
            it.data?.scheme == "content" &&
                it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        })

        val legacyIntents = DiPlayApkInstaller.fileIntents(File("/sdcard/Download/verified.apk"))
        assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE),
            legacyIntents.map { it.action })
        assertTrue(legacyIntents.all { it.data?.scheme == "file" })
        assertEquals("application/vnd.android.package-archive", legacyIntents[0].type)
        assertTrue(DiPlayApkInstaller.supportsFileInstall(19, "4.4.4"))
        assertFalse(DiPlayApkInstaller.supportsFileInstall(28, "4.4.4"))
    }

    @Test fun verifiedPublicCopyUsesFreshFileAndRechecksBytes() {
        val context = RuntimeEnvironment.getApplication()
        val original = File(context.cacheDir, "DiPlay-v9.9.9.apk")
        val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        original.writeBytes(bytes)
        val destination = DiPlayApkInstaller.createPublicCopy(context, original) {
            assertArrayEquals(bytes, it.readBytes())
        }
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            assertNotNull(destination)
            assertEquals(bytes.size.toLong(), destination!!.length())
            assertArrayEquals(bytes, destination.readBytes())
            assertNotEquals(original.absolutePath, destination.absolutePath)
            destination.delete()
        }
        original.delete()
    }
}
