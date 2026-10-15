package com.shihab.diplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shihab.diplay.adb.LocalAdb
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun freshInstallAndExistingHotspotConfigurationRemainOptedOut() {
        assertFalse(CarHotspotSettings.enabled(context))
        configureHotspot()
        assertFalse(eligible())
    }

    @Test fun qualcommBydWithoutNavigationServicesCanReachHotspotSetup() {
        ShadowBuild.setManufacturer("QUALCOMM")
        ShadowBuild.setBrand("qti")
        installPackage("com.byd.carsettings", system = true)
        assertTrue(CarHotspotSetup.isBydHeadUnit(context))
        assertTrue(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.NOT_APPROVED))
        assertFalse(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.UNREACHABLE))
    }

    @Test fun userInstalledLookalikeDoesNotIdentifyABydHeadUnit() {
        installPackage("com.byd.carsettings", system = false)
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
    }

    @Test fun genericHeadUnitRemainsHiddenEvenWithAdbReady() {
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
        assertFalse(CarHotspotSettings.visible(CarHotspotSetup.isBydHeadUnit(context), LocalAdb.Access.READY))
    }

    @Test fun navigationPackageAloneDoesNotIdentifyABydHeadUnit() {
        installPackage("com.byd.amapservice", system = true)
        assertFalse(CarHotspotSetup.isBydHeadUnit(context))
    }

    @Test fun onlyBydWithSupportedAdbSeesTheSettingIncludingBeforeApproval() {
        for (access in LocalAdb.Access.entries) {
            assertFalse(CarHotspotSettings.visible(false, access))
        }
        assertTrue(CarHotspotSettings.visible(true, LocalAdb.Access.NOT_APPROVED))
        assertTrue(CarHotspotSettings.visible(true, LocalAdb.Access.READY))
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNREACHABLE))
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNSUPPORTED))
    }

    @Test fun losingAdbHidesTheSettingWithoutClearingOrDisablingTheSavedChoice() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(CarHotspotSettings.visible(true, LocalAdb.Access.UNREACHABLE))
        assertTrue(CarHotspotSettings.enabled(context))
        assertTrue(eligible())
    }

    @Test fun startupNeedsValidCredentialsAndNoExistingSession() {
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(eligible())
        configureHotspot()
        assertTrue(eligible())
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = true))
        AirPlayPersistence.saveManualHotspotPassphrase(context, "short")
        assertFalse(eligible())
    }

    @Test fun wiredModeNeverPreparesAHotspotEvenWhenTheChoiceIsSaved() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, false, WirelessHotspotMode.MANUAL))
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun existingWifiIsNotAutomaticallyManaged() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun localOnlyCoercesToManualWhileWifiDirectIsKept() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun disablingTheChoiceCancelsFutureStartupWithoutChangingConnectionSettings() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(eligible())
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(eligible())
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    private fun eligible() = CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false)

    private fun installPackage(name: String, system: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
    }

    private fun configureHotspot() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }
}
