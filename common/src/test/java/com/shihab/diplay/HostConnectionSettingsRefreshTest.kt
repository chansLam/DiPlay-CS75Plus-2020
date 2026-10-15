package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

/**
 * The connection settings screen lives in [DiPlayActivity] and comes back to the projection screen
 * with FLAG_ACTIVITY_REORDER_TO_FRONT, so the projection screen is resumed, not recreated.
 * Settings saved while it was in the background must reach the next handshake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [HostConnectionSettingsRefreshTest.Bootstrap::class])
class HostConnectionSettingsRefreshTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun projectionScreenUsesConnectionSettingsSavedWhileItWasInTheBackground() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Old car")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "old-password")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        assertEquals(WirelessHotspotMode.MANUAL, runtimeConfig(host.get()).wirelessHotspotMode)

        // The user switches to same-LAN while the projection screen lives on.
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        AirPlayPersistence.saveManualHotspotSsid(app, "New car")

        host.pause().resume()

        val config = runtimeConfig(host.get())
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, config.wirelessHotspotMode)
        assertEquals("Pocket", config.existingWifiSsid)
        assertEquals("New car", config.manualHotspotSsid)
    }

    private fun runtimeConfig(host: CarPlayHostActivity): CarPlayRuntimeConfig {
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(host))
        return CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
    }

    /** The projection screen must start without the private MFi identity used by real cars. */
    @Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    internal class Bootstrap {
        @Implementation fun ensure(context: Context) = Unit

        @Implementation fun deviceId(identity: AirPlayIdentity): String = "02:00:00:00:00:01"
    }
}
