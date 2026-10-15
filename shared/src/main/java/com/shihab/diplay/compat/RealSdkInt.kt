package com.shihab.diplay.compat

import android.os.Build

/**
 * Returns the real API level, working around head units that fake
 * [Build.VERSION.SDK_INT] (e.g. report 27 while [Build.VERSION.RELEASE] says 4.4.x).
 *
 * Product target is KitKat (`minSdk` / `targetSdk` 19). Prefer KitKat code paths; use this
 * when a spoofed SDK_INT would otherwise take a newer-API branch.
 */
internal fun realSdkInt(): Int {
    val reported = Build.VERSION.SDK_INT
    val release = Build.VERSION.RELEASE ?: return reported
    // Map 4.4.x release to API 19 regardless of what SDK_INT claims.
    if (release.startsWith("4.4") && reported > Build.VERSION_CODES.KITKAT) {
        return Build.VERSION_CODES.KITKAT
    }
    return reported
}
