// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context

/**
 * Persists which apps the car-app launcher should show, and in what order. When never
 * configured, [shownOrder] returns null and the launcher shows every app. Once the user
 * edits the list, only the saved (checked, ordered) packages are shown.
 */
object LauncherPrefs {
    private const val PREFS = "diplay_launcher"
    private const val KEY_ORDER = "shown_app_order" // package names joined with ';'

    /** Ordered packages to show, or null when the user has not customized the list. */
    fun shownOrder(context: Context): List<String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null) ?: return null
        return raw.split(";").filter { it.isNotBlank() }.distinct()
    }

    fun saveOrder(context: Context, order: List<String>) {
        val raw = order.distinct().joinToString(";")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, raw).apply()
    }
}
