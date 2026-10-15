package com.shihab.diplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Exercise the SAF document-picker write path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiagnosticExportStoreTest {
    private lateinit var provider: DownloadsProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        provider = DownloadsProvider(File(context.cacheDir, "export-test.txt"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun selectedDocumentIsWrittenButNeverDeletedOnFailure() {
        provider.denyWrite = true
        assertThrows(SecurityException::class.java) {
            DiagnosticExportStore.write(resolver, provider.uri, "report")
        }
        assertFalse(provider.deleted)
    }

    private class DownloadsProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/downloads/41")
        var deleted = false
        var denyWrite = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = this.uri
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(this.uri, uri)
            assertEquals("wt", mode)
            if (denyWrite) throw SecurityException("Test provider refused write")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
