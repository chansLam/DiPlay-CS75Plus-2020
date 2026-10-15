package com.shihab.diplay

import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.ExistingWifiManager
import com.shihab.diplay.network.WirelessHotspotBackend
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.orchestration.CarPlayTransport
import com.shihab.diplay.orchestration.MfiTarget
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CarHotspotFallbackTest.ApState::class,
    CarHotspotFallbackTest.ManualAttachment::class, CarHotspotFallbackTest.ExistingAttachment::class,
    CarHotspotAdbGrantTest.WritePermission::class])
class CarHotspotFallbackTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        CarHotspotSettings.setEnabled(app, true)
        CarHotspotAdbGrantTest.WritePermission.allowed = true
        ApState.enabled = null
        ManualAttachment.starts = 0
    }

    @Test fun unreadableApStateAllowsManualAttachmentWithOrWithoutAutoStartup() {
        assertNull(CarHotspotStatus.isEnabled(app))
        for (optIn in listOf(false, true)) {
            CarHotspotSettings.setEnabled(app, optIn)
            controller().use { assertEquals(WirelessHotspotBackend.MANUAL_HOTSPOT, start(it).backend) }
        }
        assertEquals(2, ManualAttachment.starts)
    }

    @Test fun enabledHotspotStillAttachesWithoutControlPermission() {
        ApState.enabled = true
        CarHotspotAdbGrantTest.WritePermission.allowed = false
        controller().use { assertEquals(WirelessHotspotBackend.MANUAL_HOTSPOT, start(it).backend) }
        assertEquals(1, ManualAttachment.starts)
    }

    @Test fun knownOffHotspotWithUnsupportedControlDoesNotFallBack() {
        ApState.enabled = false
        ConnectivityManager::class.java.getDeclaredField("mService").apply {
            isAccessible = true
        }.set(app.getSystemService(ConnectivityManager::class.java), null)
        assertStartupFailure("This firmware does not support automatic hotspot startup")
    }

    @Test fun missingControlPermissionIsStillReported() {
        ApState.enabled = false
        CarHotspotAdbGrantTest.WritePermission.allowed = false
        assertStartupFailure("Hotspot control permission is missing")
    }

    @Test fun cancelledStartupDoesNotFallBackEvenWhenApStateIsUnreadable() {
        assertStartupFailure("Hotspot startup was cancelled", generation = 1)
    }

    @Test fun existingWifiBypassesHotspotControlEvenWithAutoStartupSavedAndApOff() {
        ApState.enabled = false
        CarHotspotAdbGrantTest.WritePermission.allowed = false
        controller(WirelessHotspotMode.EXISTING_WIFI).use {
            assertEquals(WirelessHotspotBackend.EXISTING_WIFI, start(it).backend)
        }
        assertEquals(0, ManualAttachment.starts)
    }

    private fun assertStartupFailure(message: String, generation: Int = 0) {
        controller().use { controller ->
            val error = assertThrows(IOException::class.java) { start(controller, generation) }
            assertTrue(error.message, error.message!!.startsWith(message))
            assertEquals(0, ManualAttachment.starts)
        }
    }

    private fun start(controller: CarPlayController, generation: Int = 0): WirelessHotspotInfo {
        val method = controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return try { method.invoke(controller, generation) as WirelessHotspotInfo }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun controller(mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL): CarPlayController = CarPlayController(app,
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = mode, manualHotspotSsid = "Car hotspot",
            existingWifiSsid = "Pocket", existingWifiPassphrase = "pocket-password",
            manualHotspotPassphrase = "12345678",
            manualHotspotSecurity = ManualHotspotSecurity.WPA2,
            identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {}, {}).also { controller ->
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants!!.first { it.toString() == "WIRELESS" })
    }

    @Implements(WifiManager::class)
    class ApState {
        @Implementation fun getWifiApState(): Int = when (enabled) {
            true -> 13
            false -> 11
            null -> throw UnsupportedOperationException("Firmware hides AP state")
        }
        @Implementation fun isWifiApEnabled(): Boolean = enabled
            ?: throw UnsupportedOperationException("Firmware hides AP state")

        companion object { var enabled: Boolean? = null }
    }

    @Implements(ManualHotspotManager::class, isInAndroidSdk = false)
    class ManualAttachment {
        @Implementation fun start(timeoutMillis: Long): WirelessHotspotInfo {
            starts++
            return WirelessHotspotInfo("Car hotspot", "12345678", Iap2WirelessSecurity.WPA_WPA2, 0,
                null, null, "wlan1", InetAddress.getByName("192.0.2.1"), "auto", WirelessHotspotBackend.MANUAL_HOTSPOT)
        }

        companion object { var starts = 0 }
    }

    @Implements(ExistingWifiManager::class, isInAndroidSdk = false)
    class ExistingAttachment {
        @Implementation fun start(timeoutMillis: Long): WirelessHotspotInfo =
            WirelessHotspotInfo("Pocket", "pocket-password", Iap2WirelessSecurity.WPA_WPA2, 36,
                5180, null, "wlan0", InetAddress.getByName("192.0.2.10"), "5 GHz", WirelessHotspotBackend.EXISTING_WIFI)
    }
}
