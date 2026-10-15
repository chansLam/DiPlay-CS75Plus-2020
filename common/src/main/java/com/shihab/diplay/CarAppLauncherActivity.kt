// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay
import com.shihab.diplay.host.ImmersiveBars

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import com.shihab.diplay.host.R

/**
 * Standalone entry from DiPlay home. While CarPlay is active, the OEM button uses the
 * in-host popup in [CarPlayHostActivity] instead.
 */
class CarAppLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ImmersiveBars.apply(this)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.CENTER)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        title = getString(R.string.app_launcher_title)
        showApps(CarAppCatalog.peek().orEmpty())
        // Refresh off the UI thread so cold start does not ANR on KitKat package scans.
        Thread({
            val apps = runCatching { CarAppCatalog.enumerate(applicationContext, forceRefresh = true) }
                .getOrElse { emptyList() }
            runOnUiThread {
                if (!isFinishing) showApps(apps)
            }
        }, "car-app-catalog").start()
    }

    private fun showApps(apps: List<CarAppCatalog.App>) {
        setContentView(
            CarPlayAppPanels.buildAppsPopup(
                context = this,
                apps = CarPlayAppPanels.shownApps(this, apps),
                onLaunch = ::launchApp,
                onClose = { finish() },
            ),
        )
        ImmersiveBars.apply(this)
    }

    private fun launchApp(app: CarAppCatalog.App) {
        try {
            app.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(app.launch)
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch ${app.label}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        ImmersiveBars.apply(this)
        // Refresh so the list stays current after an uninstall (or an app being installed).
        Thread({
            val apps = runCatching { CarAppCatalog.enumerate(applicationContext, forceRefresh = true) }
                .getOrElse { emptyList() }
            runOnUiThread {
                if (!isFinishing) showApps(apps)
            }
        }, "car-app-catalog-refresh").start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveBars.apply(this)
    }

    companion object {
        private const val TAG = "CarAppLauncher"
    }
}
