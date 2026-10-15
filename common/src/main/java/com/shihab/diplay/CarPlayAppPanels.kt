// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.shihab.diplay.host.R
import kotlin.math.roundToInt

/**
 * App picker over CarPlay. Dense opaque panel (no blur) for KitKat / i.MX6 performance.
 */
object CarPlayAppPanels {

    /** Near-opaque charcoal — cheap overdraw on 2 GB Freescale units. */
    private const val GLASS = 0xF20C1013.toInt()
    private const val SCRIM = 0xCC000000.toInt()

    fun buildAppsPopup(
        context: Context,
        apps: List<CarAppCatalog.App>,
        onLaunch: (CarAppCatalog.App) -> Unit,
        onClose: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val kitKat = Build.VERSION.SDK_INT < 21
        val columns = if (kitKat) 4 else 5
        val iconDp = if (kitKat) 48 else 64
        val iconPx = dp(iconDp)
        val pad = if (kitKat) 16 else 28
        val corner = if (kitKat) 16 else 28

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { onClose() }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(GLASS)
                cornerRadius = dp(corner).toFloat()
            }
            setPadding(dp(pad), dp(pad - 4), dp(pad), dp(pad))
            isClickable = true
            setOnClickListener { }
        }

        val header = FrameLayout(context)
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_title)
                setTextColor(Color.WHITE)
                textSize = if (kitKat) 22f else 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
        header.addView(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = dp(3).toFloat()
                    strokeCap = Paint.Cap.ROUND
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val inset = width * 0.32f
                    val c = width / 2f
                    canvas.drawLine(c - inset, c - inset, c + inset, c + inset, paint)
                    canvas.drawLine(c - inset, c + inset, c + inset, c - inset, paint)
                }
            }.apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x44FFFFFF)
                }
                setOnClickListener { onClose() }
                contentDescription = context.getString(R.string.app_launcher_close)
            },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.END),
        )
        panel.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)),
        )
        panel.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)),
        )

        if (apps.isEmpty()) {
            panel.addView(
                TextView(context).apply {
                    text = context.getString(R.string.app_launcher_no_apps)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            val grid = GridView(context).apply {
                numColumns = columns
                horizontalSpacing = dp(8)
                verticalSpacing = dp(12)
                isVerticalScrollBarEnabled = true
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                cacheColorHint = Color.TRANSPARENT
                // Fewer drawing layers while flinging on slow GPUs.
                if (kitKat) {
                    @Suppress("DEPRECATION")
                    isScrollingCacheEnabled = false
                }
                setSelector(android.R.color.transparent)
                adapter = object : BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(position: Int) = apps[position]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val app = apps[position]
                        val tile = convertView ?: oemStyleTile(context, iconDp) { dp(it) }
                        tile.setOnClickListener { onLaunch(app) }
                        // Long-press offers to uninstall the app (system uninstall screen).
                        tile.setOnLongClickListener {
                            confirmUninstall(context, app)
                            true
                        }
                        (tile.getTag(R.id.launcher_tag_label) as? TextView)?.text = app.label
                        val iconView = tile.getTag(R.id.launcher_tag_icon) as? ImageView
                        iconView?.setImageDrawable(CarAppCatalog.icon(context, app, iconPx))
                        return tile
                    }
                }
            }
            panel.addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        val metrics = context.resources.displayMetrics
        val widthFrac = if (kitKat) 0.88f else 0.92f
        val heightFrac = if (kitKat) 0.82f else 0.88f
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                (metrics.widthPixels * widthFrac).toInt(),
                (metrics.heightPixels * heightFrac).toInt(),
                Gravity.CENTER,
            ),
        )
        return overlay
    }

    /**
     * Order shown apps by [LauncherPrefs.shownOrder]. When the user has never edited the
     * list (order == null) every app is shown; once configured, only the saved packages are
     * shown, in their saved order.
     */
    fun shownApps(context: Context, apps: List<CarAppCatalog.App>): List<CarAppCatalog.App> {
        val order = LauncherPrefs.shownOrder(context) ?: return apps
        val byPackage = apps.associateBy { it.packageName }
        return order.mapNotNull { byPackage[it] }
    }

    /** "Edit" screen for the launcher: check which apps to show + drag to reorder. */
    fun buildAppsManagePopup(
        context: Context,
        apps: List<CarAppCatalog.App>,
        onDone: () -> Unit,
        onCancel: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density).roundToInt() }
        val kitKat = Build.VERSION.SDK_INT < 21
        val pad = if (kitKat) 16 else 28
        val corner = if (kitKat) 16 else 28
        val iconPx = dp(40)

        // Working copies: `ordered` keeps display order, `checked` the packages to show.
        val ordered = apps.toMutableList()
        val checked = LinkedHashSet<String>()
        val saved = LauncherPrefs.shownOrder(context)
        if (saved != null) {
            val byPackage = apps.associateBy { it.packageName }
            ordered.clear()
            ordered.addAll(saved.mapNotNull { byPackage[it] })
            ordered.addAll(apps.filter { it.packageName !in saved })
            checked.addAll(saved)
        } else {
            checked.addAll(apps.map { it.packageName })
        }

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { onCancel() }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(GLASS)
                cornerRadius = dp(corner).toFloat()
            }
            setPadding(dp(pad), dp(pad - 4), dp(pad), dp(pad))
            isClickable = true
            setOnClickListener { }
        }

        val header = FrameLayout(context)
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_edit_title)
                setTextColor(Color.WHITE)
                textSize = if (kitKat) 22f else 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_done)
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(0x44FFFFFF)
                }
                setOnClickListener {
                    LauncherPrefs.saveOrder(
                        context,
                        ordered.filter { it.packageName in checked }.map { it.packageName },
                    )
                    onDone()
                }
            },
            FrameLayout.LayoutParams(dp(64), dp(40), Gravity.CENTER_VERTICAL or Gravity.END),
        )
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_launcher_manage_hint)
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.START
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) },
        )

        if (apps.isEmpty()) {
            panel.addView(
                TextView(context).apply {
                    text = context.getString(R.string.app_launcher_no_apps)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            val systemPackages = ordered
                .filter { CarAppCatalog.isSystemApp(context, it.packageName) }
                .map { it.packageName }
                .toSet()
            val userPackages = ordered.map { it.packageName }.filter { it !in systemPackages }.toSet()

            lateinit var list: ListView
            lateinit var adapter: BaseAdapter
            lateinit var selectAllBox: CheckBox
            lateinit var systemBox: CheckBox
            lateinit var userBox: CheckBox
            var syncingBulk = false
            var dragFrom = -1

            fun syncBulkBoxes() {
                syncingBulk = true
                selectAllBox.isChecked = ordered.isNotEmpty() && ordered.all { it.packageName in checked }
                systemBox.isChecked = systemPackages.isNotEmpty() && systemPackages.all { it in checked }
                userBox.isChecked = userPackages.isNotEmpty() && userPackages.all { it in checked }
                selectAllBox.isEnabled = ordered.isNotEmpty()
                systemBox.isEnabled = systemPackages.isNotEmpty()
                userBox.isEnabled = userPackages.isNotEmpty()
                syncingBulk = false
            }

            fun filterCheckBox(labelRes: Int): CheckBox = CheckBox(context).apply {
                text = context.getString(labelRes)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            }

            val bulkRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                selectAllBox = filterCheckBox(R.string.app_launcher_select_all)
                systemBox = filterCheckBox(R.string.app_launcher_system_apps)
                userBox = filterCheckBox(R.string.app_launcher_user_apps)
                addView(selectAllBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(systemBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(userBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            panel.addView(
                bulkRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(4) },
            )

            selectAllBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(ordered.map { it.packageName }) else checked.clear()
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }
            systemBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(systemPackages) else checked.removeAll(systemPackages)
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }
            userBox.setOnCheckedChangeListener { _, isChecked ->
                if (syncingBulk) return@setOnCheckedChangeListener
                if (isChecked) checked.addAll(userPackages) else checked.removeAll(userPackages)
                adapter.notifyDataSetChanged()
                syncBulkBoxes()
            }

            adapter = object : BaseAdapter() {
                override fun getCount() = ordered.size
                override fun getItem(position: Int) = ordered[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val app = ordered[position]
                    val row = convertView ?: buildManageRow(context, dp)
                    val handle = row.getTag(R.id.manage_tag_handle) as TextView
                    val check = row.getTag(R.id.manage_tag_check) as CheckBox
                    val icon = row.getTag(R.id.manage_tag_icon) as ImageView
                    val label = row.getTag(R.id.manage_tag_label) as TextView
                    row.alpha = if (position == dragFrom) 0.45f else 1f
                    // KitKat-safe drag: startDrag from the handle + ListView OnDragListener.
                    handle.setOnTouchListener { _, event ->
                        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                        dragFrom = position
                        @Suppress("DEPRECATION")
                        row.startDrag(
                            ClipData.newPlainText("app", app.packageName),
                            View.DragShadowBuilder(row),
                            position,
                            0,
                        )
                        adapter.notifyDataSetChanged()
                        true
                    }
                    check.setOnCheckedChangeListener(null)
                    check.isChecked = checked.contains(app.packageName)
                    check.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) checked.add(app.packageName) else checked.remove(app.packageName)
                        syncBulkBoxes()
                    }
                    icon.setImageDrawable(CarAppCatalog.icon(context, app, iconPx))
                    label.text = app.label
                    return row
                }
            }
            syncBulkBoxes()
            list = ListView(context).apply {
                divider = null
                cacheColorHint = Color.TRANSPARENT
                isVerticalScrollBarEnabled = true
                @Suppress("DEPRECATION")
                if (kitKat) isScrollingCacheEnabled = false
                this.adapter = adapter
                setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_LOCATION -> {
                            val target = pointToPosition(event.x.toInt(), event.y.toInt())
                            if (dragFrom >= 0 &&
                                target != ListView.INVALID_POSITION &&
                                target != dragFrom &&
                                target in ordered.indices
                            ) {
                                val item = ordered.removeAt(dragFrom)
                                ordered.add(target, item)
                                dragFrom = target
                                adapter.notifyDataSetChanged()
                            }
                            val edge = dp(48)
                            when {
                                event.y < edge -> smoothScrollBy(-dp(16), 50)
                                height - event.y < edge -> smoothScrollBy(dp(16), 50)
                            }
                            true
                        }
                        DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                            dragFrom = -1
                            adapter.notifyDataSetChanged()
                            true
                        }
                        DragEvent.ACTION_DRAG_STARTED -> true
                        else -> true
                    }
                }
            }
            panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val metrics = context.resources.displayMetrics
        val widthFrac = if (kitKat) 0.88f else 0.92f
        val heightFrac = if (kitKat) 0.82f else 0.88f
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                (metrics.widthPixels * widthFrac).toInt(),
                (metrics.heightPixels * heightFrac).toInt(),
                Gravity.CENTER,
            ),
        )
        return overlay
    }

    /** Manage row: [drag handle][checkbox][icon][label]; controls cached on the row via tags. */
    private fun buildManageRow(context: Context, dp: (Int) -> Int): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))

        val handle = TextView(context).apply {
            text = "☰"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.app_launcher_drag_handle)
        }
        row.addView(handle, LinearLayout.LayoutParams(dp(44), dp(48)))
        val check = CheckBox(context).apply { isClickable = true }
        row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) },
        )

        // Tags MUST be on the row — getView looks them up from convertView/row.
        row.setTag(R.id.manage_tag_handle, handle)
        row.setTag(R.id.manage_tag_check, check)
        row.setTag(R.id.manage_tag_icon, icon)
        row.setTag(R.id.manage_tag_label, label)
        return row
    }

    /** Long-press on an app tile: confirm, then hand off to the system uninstall screen. */
    private fun confirmUninstall(context: Context, app: CarAppCatalog.App) {
        runCatching {
            AlertDialog.Builder(context)
                .setTitle("卸载 ${app.label}？")
                .setMessage("将卸载应用“${app.label}”，卸载后其数据也会被删除。")
                .setPositiveButton("卸载") { _, _ ->
                    runCatching {
                        val delete = Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:${app.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(delete)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun oemStyleTile(
        context: Context,
        iconDp: Int,
        dp: (Int) -> Int,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
        setBackgroundColor(Color.TRANSPARENT)
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val frame = dp(iconDp + 8)
        val iconFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0x33FFFFFF)
            }
            addView(iconView, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        }
        addView(iconFrame, LinearLayout.LayoutParams(frame, frame))
        val labelView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setTag(R.id.launcher_tag_icon, iconView)
        setTag(R.id.launcher_tag_label, labelView)
    }
}
