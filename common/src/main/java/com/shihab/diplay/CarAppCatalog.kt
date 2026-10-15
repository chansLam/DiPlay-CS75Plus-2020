// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache

/**
 * Launchable head-unit apps for the CarPlay OEM app list (excludes DiPlay itself).
 *
 * Tuned for KitKat / 2 GB head units (e.g. Freescale i.MX6Q): enumeration keeps labels and
 * intents only; icons are loaded lazily and downsampled into a small LRU cache.
 */
object CarAppCatalog {

    private const val CACHE_TTL_MS = 120_000L
    private const val ICON_CACHE_ENTRIES = 28

    data class App(
        val label: String,
        val launch: Intent,
        val packageName: String,
        /** Fully-qualified activity name when known; used for ResolveInfo-quality icons. */
        val activityName: String? = null,
    )

    @Volatile
    private var cache: List<App>? = null
    @Volatile
    private var cachedAtElapsed = 0L

    private val iconCache = object : LruCache<String, Drawable>(ICON_CACHE_ENTRIES) {}

    fun peek(): List<App>? = cache

    /** True when the package is a preinstalled / updated system app. */
    fun isSystemApp(context: Context, packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    } catch (_: Exception) {
        false
    }

    fun isStale(): Boolean {
        val at = cachedAtElapsed
        return cache == null || SystemClock.elapsedRealtime() - at >= CACHE_TTL_MS
    }

    fun invalidate() {
        cache = null
        cachedAtElapsed = 0L
        synchronized(iconCache) { iconCache.evictAll() }
    }

    fun enumerate(context: Context, forceRefresh: Boolean = false): List<App> {
        if (!forceRefresh) {
            val hit = cache
            if (hit != null && !isStale()) return hit
        }
        val list = enumerateFresh(context.applicationContext)
        cache = list
        cachedAtElapsed = SystemClock.elapsedRealtime()
        return list
    }

    /** Downsampled icon for [app]; safe to call from the UI thread (cache hit) or a worker. */
    fun icon(context: Context, app: App, sizePx: Int): Drawable? {
        val key = "${app.packageName}/${app.activityName.orEmpty()}@$sizePx"
        synchronized(iconCache) {
            iconCache.get(key)?.let { return it }
        }
        val scaled = loadScaledIcon(context.applicationContext, app, sizePx.coerceAtLeast(24)) ?: return null
        synchronized(iconCache) { iconCache.put(key, scaled) }
        return scaled
    }

    private fun enumerateFresh(context: Context): List<App> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, App>()

        fun add(packageName: String, label: String, launch: Intent, activityName: String?) {
            if (packageName == context.packageName) return
            if (byPackage.containsKey(packageName)) return
            byPackage[packageName] = App(
                label = label,
                launch = launch,
                packageName = packageName,
                activityName = activityName,
            )
        }

        fun addResolve(info: ResolveInfo) {
            val activity = info.activityInfo ?: return
            val packageName = activity.packageName ?: return
            if (activity.name == CarAppLauncherActivity::class.java.name) return
            val launch = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, activity.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val label = try {
                info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } ?: packageName
            add(packageName, label, launch, activity.name)
        }

        // KitKat has no package-visibility filter: LAUNCHER (+ LEANBACK) is enough and avoids a
        // full getInstalledApplications() scan that loads ApplicationInfo for every package.
        queryActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .forEach(::addResolve)
        queryActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
            .forEach(::addResolve)

        return byPackage.values.sortedBy { it.label.lowercase() }
    }

    private fun loadScaledIcon(context: Context, app: App, sizePx: Int): Drawable? {
        val pm = context.packageManager
        val raw = try {
            val component = app.launch.component
            if (component != null) pm.getActivityIcon(component) else pm.getApplicationIcon(app.packageName)
        } catch (_: Exception) {
            try {
                pm.getApplicationIcon(app.packageName)
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val bitmap = drawableToBitmap(raw, sizePx) ?: return raw
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap? {
        return try {
            if (drawable is BitmapDrawable) {
                val src = drawable.bitmap ?: return null
                if (src.width == sizePx && src.height == sizePx) src
                else Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
            } else {
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                bmp
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> = try {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    } catch (_: Exception) {
        emptyList()
    }
}
