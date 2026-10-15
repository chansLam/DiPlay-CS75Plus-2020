package com.shihab.diplay.settings

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * Thin orchestration surface that settings section objects use so [com.shihab.diplay.DiPlayActivity]
 * keeps card wiring while the bulky control lists live beside each category.
 */
internal interface SettingsSectionUi {
    val context: Context
    val resources: Resources

    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String

    fun toggle(
        parent: LinearLayout,
        title: String,
        description: String,
        value: Boolean,
        save: (Boolean) -> Unit,
    )

    fun choice(
        parent: LinearLayout,
        title: String,
        options: List<String>,
        current: Int,
        reconnects: Boolean = true,
        save: (Int) -> Unit,
    )

    fun button(title: String, primary: Boolean, click: () -> Unit): Button
    fun label(value: String, size: Int, color: Int): View
    fun matchButton(top: Int, height: Int): LinearLayout.LayoutParams
    fun mutedColor(): Int
    fun dp(value: Int): Int

    fun markReconnectNeeded()
    fun reconnectIfRunning()
    fun render()
    fun hasSession(): Boolean
}
