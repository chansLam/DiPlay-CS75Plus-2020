// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Sticky immersive fullscreen: hide status + navigation bars (KitKat-friendly). */
object ImmersiveBars {

    fun apply(activity: Activity) {
        val window = activity.window
        @Suppress("DEPRECATION")
        var flags = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        if (Build.VERSION.SDK_INT >= 19) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        @Suppress("DEPRECATION")
        if (window.decorView.systemUiVisibility == flags &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
        ) {
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}
