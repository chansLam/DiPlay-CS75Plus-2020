// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context
import android.util.Log

/**
 * Root-level "hide the system navigation bar globally". Sets the property the Android system
 * reads to decide whether a navigation bar exists (qemu.hw.mainkeys=1 means "no nav bar"),
 * then restarts SystemUI so the change applies immediately and stays hidden across every app
 * until the property is reset (or the device reboots). Requires a rooted head unit with a
 * working "su". OEM launcher docks (e.g. Changan / 氢桌面 left dock) may not honour the flag.
 *
 * Robustness notes for the CS75 (Freescale / KitKat):
 *  - The "su" binary lives at different paths depending on the root method, so several
 *    candidates are probed instead of assuming a bare "su".
 *  - The SystemUI package / process differs across OEM builds (com.android.systemui,
 *    Changan / Hydrogen custom shells), so a few candidates are force-stopped and then
 *    restarted so the nav-bar property is re-read.
 */
object RootNavbar {
    const val TAG = "DiPlay-RootNavbar"

    private val SU_PATHS = arrayOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        // Extra candidates for one-click root tools (KingRoot / 360root / 刷机自带 su):
        "/su/xbin/su",
        "/system/su",
        "/system/bin/.su",
        "/system/xbin/.su",
        "/system/xbin/kingroot",
        "/system/bin/app_process",
    )

    fun hiddenRequested(context: Context): Boolean = AirPlayPersistence.loadRootHideNavbar(context)

    /** True when a usable "su" responds with uid 0 (root). */
    fun available(): Boolean = runCatching {
        val su = findSu() ?: return@runCatching false
        val process = Runtime.getRuntime().exec(arrayOf(su, "-c", "id"))
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        process.waitFor()
        out.contains("uid=0") || out.contains("root")
    }.getOrDefault(false)

    /** Hides (or restores) the navigation bar at the system level. Runs on a background thread. */
    fun applyHidden(hidden: Boolean): Boolean = runCatching {
        val su = findSu()
        if (su == null) {
            Log.w(TAG, "applyHidden skipped: no usable su binary found")
            return@runCatching false
        }
        val prop = if (hidden) "1" else "0"
        // 1) setprop 让 framework 按新值重建导航栏；
        // 2) 杀掉并重启各候选 SystemUI / 桌面，使 prop 立即生效；
        // 3) policy_control 兜底强制全屏隐藏（部分定制 UI 不看 mainkeys）。
        val script = buildString {
            append("setprop qemu.hw.mainkeys $prop; ")
            append("pkill -f systemui; ")
            append("pkill -f S311WT_SystemUI; ")
            append("am force-stop com.android.systemui; ")
            append("am force-stop com.changan.systemui; ")
            append("am force-stop com.changan.carlife; ")
            append("am force-stop com.hydrogen.desktop; ")
            append("am force-stop com.changanaudio.desktop; ")
            append("am force-stop S311WT_SystemUI; ")
            append("sleep 1; ")
            append("am startservice -n com.android.systemui/.SystemUIService; ")
            if (hidden) append("settings put global policy_control immersive.navigation=*; ")
            else append("settings delete global policy_control; ")
            append("true")
        }
        val process = Runtime.getRuntime().exec(arrayOf(su, "-c", script))
        process.waitFor()
        Log.i(TAG, "navbar hidden=$hidden applied via su=$su")
        true
    }.getOrDefault(false)

    /**
     * Enables root-hidden navigation bar: triggers the su authorisation and applies the hide.
     * Returns false when su was denied or no working root exists, so the caller can fall back
     * to the disabled state.
     */
    fun enable(): Boolean = runCatching {
        if (!applyHidden(true)) return@runCatching false
        available()
    }.getOrDefault(false)

    private fun findSu(): String? {
        for (path in SU_PATHS) {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf(path, "-c", "id"))
                p.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return path
        }
        return null
    }
}
