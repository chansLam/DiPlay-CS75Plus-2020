package com.shihab.diplay

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.host.R
import java.util.Locale

/**
 * In-app update surfaces use the same navy/cyan design on Android 4.4 and newer.
 * The platform package installer itself is owned by Android and cannot be themed here.
 */
internal object DiPlayUpdateDialogs {
    private val CYAN = Color.rgb(14, 205, 250)
    private val BLUE = Color.rgb(0, 150, 244)
    private val WHITE = Color.rgb(224, 245, 255)
    private val MUTED = Color.rgb(164, 191, 220)
    private val FADED = Color.rgb(105, 133, 164)
    private val DIVIDER = Color.rgb(15, 82, 120)
    private val DARK = Color.rgb(1, 17, 37)

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(activity: Activity, colors: IntArray, radius: Int,
                        strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 1) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(activity, radius).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(activity, strokeWidth), strokeColor)
        }

    private fun solid(activity: Activity, color: Int, radius: Int, outline: Int = Color.TRANSPARENT) =
        rounded(activity, intArrayOf(color, color), radius, outline)

    private fun text(activity: Activity, value: String, size: Float, color: Int,
                     bold: Boolean = false) = TextView(activity).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun space(activity: Activity, height: Int) =
        View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(activity, height)) }

    private fun line(activity: Activity) = View(activity).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 1))
    }

    private fun button(activity: Activity, label: String, primary: Boolean,
                       onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
        setTextColor(if (primary) DARK else WHITE)
        background = if (primary)
            rounded(activity, intArrayOf(Color.rgb(14, 229, 244), Color.rgb(0, 155, 253)),
                17, Color.rgb(80, 233, 255), 1)
        else
            rounded(activity, intArrayOf(Color.rgb(6, 42, 66), Color.rgb(4, 31, 57)),
                17, BLUE, 1)
        setOnClickListener { onClick() }
    }

    private fun actions(activity: Activity, secondary: String?, primary: String,
                        onSecondary: () -> Unit, onPrimary: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            if (secondary != null) {
                addView(button(activity, secondary, false, onSecondary),
                    LinearLayout.LayoutParams(0, dp(activity, 53), 1f).apply {
                        rightMargin = dp(activity, 12)
                    })
            }
            addView(button(activity, primary, true, onPrimary),
                LinearLayout.LayoutParams(0, dp(activity, 53), 1f))
        }

    private fun panel(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 28), dp(activity, 28), dp(activity, 28), dp(activity, 24))
        background = rounded(activity,
            intArrayOf(Color.rgb(5, 38, 65), Color.rgb(1, 15, 34), Color.rgb(4, 36, 61)),
            20, Color.rgb(4, 189, 245), 1)
    }

    private fun heading(activity: Activity, root: LinearLayout, title: String,
                        subtitle: String, symbol: Int, description: String? = null) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emblem = IconView(activity, symbol).apply {
            background = rounded(activity, intArrayOf(Color.rgb(5, 66, 103), Color.rgb(3, 35, 62)),
                19, Color.rgb(0, 120, 188))
        }
        row.addView(emblem, LinearLayout.LayoutParams(dp(activity, 88), dp(activity, 88)).apply {
            rightMargin = dp(activity, 24)
        })
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(activity, title, 25f, WHITE, true))
        copy.addView(space(activity, 4))
        copy.addView(text(activity, subtitle, 15f, MUTED))
        if (!description.isNullOrBlank()) {
            copy.addView(space(activity, 6))
            copy.addView(text(activity, description, 13f, MUTED))
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(space(activity, 22))
        root.addView(line(activity))
        root.addView(space(activity, 18))
    }

    private fun contentLabel(activity: Activity, root: LinearLayout, message: String,
                             asNotes: Boolean) {
        if (asNotes) {
            root.addView(text(activity, activity.getString(R.string.update_notes_heading),
                17f, WHITE, true))
            root.addView(space(activity, 10))
            // Render Markdown-ish release notes as short cyan-dot rows without exposing raw markers.
            message.lines().filter { it.isNotBlank() }.forEach { source ->
                val item = source.trim().removePrefix("- ").removePrefix("* ")
                    .trimStart('#', ' ')
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(0, dp(activity, 4), 0, dp(activity, 4))
                }
                row.addView(text(activity, "•", 13f, CYAN),
                    LinearLayout.LayoutParams(dp(activity, 25), -2))
                row.addView(text(activity, item, 14f, MUTED),
                    LinearLayout.LayoutParams(0, -2, 1f))
                root.addView(row)
            }
        } else {
            root.addView(text(activity, message, 15f, MUTED))
        }
    }

    /**
     * Scrollable body for small or unusually short head units. A bounded outer ScrollView
     * keeps the buttons reachable, rather than letting the window run off screen.
     */
    private fun show(activity: Activity, root: LinearLayout, cancellable: Boolean = true): Dialog {
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            addView(root)
        }
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(cancellable)
            setContentView(scroll)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.attributes = window!!.attributes.apply { dimAmount = 0.78f }
        }
        dialog.show()
        val metrics = activity.resources.displayMetrics
        dialog.window?.setLayout(
            (metrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(activity, 590)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private fun footer(activity: Activity, root: LinearLayout, primary: String,
                       secondary: String?, onPrimary: () -> Unit): Dialog {
        root.addView(space(activity, 18))
        root.addView(line(activity))
        root.addView(space(activity, 20))
        var dialog: Dialog? = null
        root.addView(actions(activity, secondary, primary, { dialog?.dismiss() }) {
            dialog?.dismiss()
            onPrimary()
        })
        dialog = show(activity, root)
        return dialog
    }

    fun available(activity: Activity, version: String, notes: String, safetyNotice: String,
                  primaryLabel: String, secondaryLabel: String, onPrimary: () -> Unit): Dialog {
        val root = panel(activity)
        heading(activity, root, activity.getString(R.string.update_available_heading),
            activity.getString(R.string.update_available_subtitle, version), ICON_PACKAGE,
            activity.getString(R.string.update_available_description))
        contentLabel(activity, root, notes, true)
        root.addView(space(activity, 12))
        root.addView(text(activity, safetyNotice, 12f, FADED))
        return footer(activity, root, primaryLabel, secondaryLabel, onPrimary)
    }

    fun installReady(activity: Activity, version: String, message: String,
                     primaryLabel: String, secondaryLabel: String,
                     onPrimary: () -> Unit): Dialog {
        val root = panel(activity)
        heading(activity, root, activity.getString(R.string.update_ready_heading),
            activity.getString(R.string.update_ready_subtitle, version),
            ICON_READY)
        contentLabel(activity, root, message, false)
        return footer(activity, root, primaryLabel, secondaryLabel, onPrimary)
    }

    /** The same presentation is used for permission, error and legacy fallback notices. */
    fun confirm(activity: Activity, title: String, message: String,
                primaryLabel: String, secondaryLabel: String? = null,
                onPrimary: () -> Unit): Dialog {
        val root = panel(activity)
        heading(activity, root, title, activity.getString(R.string.update_dialog_subtitle),
            ICON_INFO)
        contentLabel(activity, root, message, false)
        return footer(activity, root, primaryLabel, secondaryLabel, onPrimary)
    }

    class ProgressHandle(
        val dialog: Dialog, private val bar: ProgressBar, private val percent: TextView,
        private val phase: TextView, private val detail: TextView,
        private val steps: List<TextView>, private val icon: IconView
    ) {
        private fun activate(index: Int) {
            steps.forEachIndexed { i, view ->
                view.setTextColor(if (i == index) WHITE else if (i < index) CYAN else FADED)
                // Step label uses an ASCII numeric prefix; state is shown by color only
                // (the OEM head-unit font lacks ✓ / ◉ / ○ glyphs).
                view.text = view.tag.toString()
            }
            icon.step = index
            icon.invalidate()
        }
        fun update(value: Int, downloaded: Long = -1, total: Long = -1, bytesPerSecond: Long = -1) {
            bar.isIndeterminate = false
            bar.progress = value.coerceIn(0, 100)
            percent.text = value.coerceIn(0, 100).toString() + "%"
            if (downloaded >= 0 && total > 0) {
                detail.text = String.format(Locale.US, "%.1f MB / %.1f MB · %.1f MB/s",
                    downloaded / 1048576.0, total / 1048576.0,
                    bytesPerSecond.coerceAtLeast(0) / 1048576.0)
            }
        }
        fun verifying(activity: Activity) {
            phase.text = activity.getString(R.string.update_verifying)
            detail.text = activity.getString(R.string.update_verifying)
            activate(1)
            bar.isIndeterminate = true
            percent.text = ""
        }
        fun preparing(activity: Activity) {
            phase.text = activity.getString(R.string.update_preparing)
            detail.text = activity.getString(R.string.update_preparing)
            activate(2)
            bar.isIndeterminate = true
        }
        fun dismiss() = dialog.dismiss()
    }

    fun progress(activity: Activity, background: () -> Unit = {},
                 cancel: () -> Unit): ProgressHandle {
        val root = panel(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val indicator = IconView(activity, ICON_DOWNLOAD)
        row.addView(indicator, LinearLayout.LayoutParams(dp(activity, 91), dp(activity, 91)).apply {
            rightMargin = dp(activity, 22)
        })
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(activity, activity.getString(R.string.update_downloading),
            23f, WHITE, true))
        titles.addView(space(activity, 6))
        titles.addView(text(activity, activity.getString(R.string.update_downloading_phase),
            15f, MUTED))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(space(activity, 22))

        val progressRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val track = solid(activity, Color.rgb(5, 43, 72), 12, Color.rgb(0, 109, 169))
            val fill = ClipDrawable(rounded(activity,
                intArrayOf(Color.rgb(0, 163, 255), Color.rgb(24, 229, 233)), 12),
                Gravity.LEFT, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        progressRow.addView(bar, LinearLayout.LayoutParams(0, dp(activity, 16), 1f))
        val percent = text(activity, "0%", 19f, WHITE, true).apply { gravity = Gravity.RIGHT }
        progressRow.addView(percent, LinearLayout.LayoutParams(dp(activity, 66), -2))
        root.addView(progressRow)
        root.addView(space(activity, 20))

        val stepLabels = listOf(
            activity.getString(R.string.update_progress_step_download),
            activity.getString(R.string.update_progress_step_verify),
            activity.getString(R.string.update_progress_step_prepare))
        val views = stepLabels.mapIndexed { index, title ->
            text(activity, (index + 1).toString() + ". " + title,
                15f, if (index == 0) WHITE else FADED).apply {
                tag = title
            }
        }
        val phase = text(activity, activity.getString(R.string.update_downloading_phase),
            14f, MUTED)
        val detail = text(activity, "", 13f, FADED)
        views.forEachIndexed { index, view ->
            root.addView(view)
            if (index == 0) {
                root.addView(space(activity, 4))
                root.addView(phase)
                root.addView(space(activity, 2))
                root.addView(detail)
            }
            if (index < 2) {
                val connector = View(activity).apply { setBackgroundColor(DIVIDER) }
                root.addView(connector, LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 19))
                    .apply { leftMargin = dp(activity, 7) })
            }
        }
        root.addView(space(activity, 19))
        root.addView(line(activity))
        root.addView(space(activity, 20))
        var dialog: Dialog? = null
        root.addView(actions(activity, activity.getString(R.string.update_continue_background),
            activity.getString(android.R.string.cancel),
            {
                dialog?.dismiss()
                background()
            }, {
                cancel()
                dialog?.dismiss()
            }))
        // Do not enable outside/back cancellation: it would hide a live cancellable download.
        dialog = show(activity, root, false)
        return ProgressHandle(dialog, bar, percent, phase, detail, views, indicator)
    }

    private const val ICON_PACKAGE = 0
    private const val ICON_DOWNLOAD = 1
    private const val ICON_READY = 2
    private const val ICON_INFO = 3

    /** Canvas-only vector graphics so no modern vector framework is needed on API 19. */
    internal class IconView(activity: Activity, private val symbol: Int) : View(activity) {
        var step: Int = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val u = width.coerceAtMost(height) / 100f
            val cx = width / 2f
            val cy = height / 2f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float) {
                paint.color = color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke * u
                canvas.drawLine(cx + x1 * u, cy + y1 * u,
                    cx + x2 * u, cy + y2 * u, paint)
            }
            fun shape(points: FloatArray, color: Int) {
                val path = Path()
                path.moveTo(cx + points[0] * u, cy + points[1] * u)
                var i = 2
                while (i < points.size) {
                    path.lineTo(cx + points[i] * u, cy + points[i + 1] * u)
                    i += 2
                }
                path.close()
                paint.color = color
                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            when (symbol) {
                ICON_PACKAGE -> {
                    shape(floatArrayOf(-24f, -18f, 0f, -31f, 24f, -18f, 0f, -5f),
                        Color.rgb(45, 219, 255))
                    shape(floatArrayOf(-25f, -12f, -3f, 0f, -3f, 28f, -25f, 16f),
                        Color.rgb(6, 163, 229))
                    shape(floatArrayOf(3f, 0f, 25f, -12f, 25f, 16f, 3f, 28f),
                        Color.rgb(33, 200, 249))
                    paint.style = Paint.Style.FILL
                    paint.color = CYAN
                    canvas.drawCircle(cx + 25f * u, cy + 25f * u, 16f * u, paint)
                    line(25f, 33f, 25f, 17f, DARK, 3.5f)
                    line(19f, 23f, 25f, 17f, DARK, 3.5f)
                    line(31f, 23f, 25f, 17f, DARK, 3.5f)
                }
                ICON_DOWNLOAD, ICON_READY -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 7f * u
                    paint.color = Color.rgb(7, 72, 116)
                    canvas.drawCircle(cx, cy, 41f * u, paint)
                    paint.color = CYAN
                    canvas.drawArc(RectF(cx - 41f * u, cy - 41f * u,
                            cx + 41f * u, cy + 41f * u),
                        -90f, if (symbol == ICON_READY) 360f else 250f, false, paint)
                    if (symbol == ICON_READY || step == 2) {
                        line(-18f, 0f, -5f, 13f, CYAN, 5f)
                        line(-5f, 13f, 21f, -16f, CYAN, 5f)
                    } else {
                        line(0f, -20f, 0f, 11f, CYAN, 6f)
                        line(-11f, 1f, 0f, 12f, CYAN, 6f)
                        line(11f, 1f, 0f, 12f, CYAN, 6f)
                        line(-16f, 17f, -16f, 25f, CYAN, 4f)
                        line(-16f, 25f, 16f, 25f, CYAN, 4f)
                        line(16f, 25f, 16f, 17f, CYAN, 4f)
                    }
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 4f * u
                    paint.color = CYAN
                    canvas.drawCircle(cx, cy, 30f * u, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy - 14f * u, 4f * u, paint)
                    canvas.drawRoundRect(RectF(cx - 3f * u, cy - 5f * u,
                            cx + 3f * u, cy + 20f * u), 3f * u, 3f * u, paint)
                }
            }
        }
    }
}
