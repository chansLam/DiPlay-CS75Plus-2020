package com.shihab.diplay.settings

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarAppCatalog
import com.shihab.diplay.CarPlayAppPanels
import com.shihab.diplay.host.FloatingBall
import com.shihab.diplay.host.R

object VehicleSettingsSection {

    fun createFloatingBallRow(
        context: Context,
        checked: Boolean = AirPlayPersistence.loadFloatingBallEnabled(context),
        theme: SettingsTheme = SettingsTheme.CARD,
        onChanged: (Boolean) -> Unit = { enabled ->
            AirPlayPersistence.saveFloatingBallEnabled(context, enabled)
            if (!enabled) FloatingBall.hide()
        },
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.settings_floating_ball),
        description = context.getString(R.string.settings_floating_ball_description),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.settings_floating_ball),
        onChanged = onChanged,
    )

    /** "Title · Value" label for the app-list row (Search indexes the title). */
    fun appListButtonTitle(context: Context): String =
        "${context.getString(R.string.settings_app_list)} · ${context.getString(R.string.app_launcher_edit)}"

    /**
     * Full-screen manage overlay on [activity]. Enumerates apps if the catalog is empty.
     * [onClosed] runs after Done or Cancel (and after the overlay is removed).
     */
    fun showAppsManagePopup(activity: Activity, onClosed: () -> Unit = {}) {
        val apps = runCatching {
            CarAppCatalog.enumerate(activity.applicationContext, forceRefresh = true)
        }.getOrElse { CarAppCatalog.peek().orEmpty() }
        val root = activity.window.decorView as ViewGroup
        lateinit var overlay: View
        val dismiss = {
            root.removeView(overlay)
            onClosed()
        }
        overlay = CarPlayAppPanels.buildAppsManagePopup(
            context = activity,
            apps = apps,
            onDone = dismiss,
            onCancel = dismiss,
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
