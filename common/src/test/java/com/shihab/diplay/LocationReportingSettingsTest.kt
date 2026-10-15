package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.settings.SettingsCategory
import com.shihab.diplay.settings.SettingsInformationArchitecture
import com.shihab.diplay.settings.SettingsSection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shihab.diplay.host.R
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LocationReportingSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @Before fun setUp() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun currentSettingsExposeLocationSwitchWithDefaultOn() {
        openSettings()

        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun grantedSettingSurvivesRecreationAndCanBeDisabled() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        openSettings()
        assertTrue(locationSwitch().isChecked)

        locationSwitch().performClick()
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        requireNotNull(controller).recreate()
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.NAVIGATION)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertFalse(locationSwitch().isChecked)

        locationSwitch().performClick()
        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun enablingWaitsForPrecisePermissionAndRejectsApproximateOnly() {
        AirPlayPersistence.saveLocationReportingEnabled(context, false)
        openSettings()
        locationSwitch().performClick()

        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            request.requestedPermissions)

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun precisePermissionResultEnablesReporting() {
        AirPlayPersistence.saveLocationReportingEnabled(context, false)
        openSettings()
        locationSwitch().performClick()
        val request = shadowOf(activity).lastRequestedPermission

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED))

        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun eitherToggleDirectionStopsTheOldSessionAndOpensANewHostWithSavedSetting() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        AirPlayPersistence.saveLocationReportingEnabled(context, true)
        openSettings()
        // Authentication assets are deliberately absent from unit tests. Model an already
        // provisioned, connected host without constructing transports or native decoders.
        ReflectionHelpers.setField(activity, "setupError", null)
        val stoppedWithSettings = mutableListOf<Boolean>()

        for (enabled in listOf(false, true)) {
            val stop: ((() -> Unit) -> Unit) = { completion ->
                stoppedWithSettings += AirPlayPersistence.loadLocationReportingEnabled(context)
                CarPlayBackgroundSession.clear()
                completion()
            }
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)

            locationSwitch().performClick()

            assertEquals(enabled, AirPlayPersistence.loadLocationReportingEnabled(context))
            assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        }
        assertEquals(listOf(false, true), stoppedWithSettings)
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        ShadowLooper.idleMainLooper(1300, TimeUnit.MILLISECONDS)
        openNavigationCategory()
    }

    private fun openNavigationCategory() {
        descendants(activity.window.decorView).first { candidate ->
            candidate.contentDescription == activity.getString(
                R.string.settings_open_category,
                activity.getString(R.string.settings_navigation),
            )
        }.performClick()
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.location)
        })
    }

    private fun locationSwitch(): Switch = descendants(activity.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.contentDescription == activity.getString(R.string.report_location_to_iphone) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
