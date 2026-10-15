package com.shihab.diplay

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real GitHub HTTPS probe on KitKat, exercising the update client's production network path. */
class DiPlayLegacyUpdateTlsTest {
    @Test fun kitkatCanCheckGitHubReleasesWithoutSslv3Fallback() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Log.i("DiPlayTlsTest", "Android API=${Build.VERSION.SDK_INT}")
        val type = Class.forName("com.shihab.diplay.DiPlayUpdateClient")
        val client = type.getField("INSTANCE").get(null)
        type.getMethod("configure", android.content.Context::class.java).invoke(client, app)
        val release = try {
            type.getMethod("latest", String::class.java).invoke(client, "0.2.43")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }
        assertNotNull("HTTPS metadata fetch returned no newer release", release)
        val tag = release!!.javaClass.getMethod("getTag").invoke(release) as String
        Log.i("DiPlayTlsTest", "Found verified release tag $tag")
        assertTrue(tag.startsWith("v0.2."))
    }
}
