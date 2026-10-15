// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay
import com.shihab.diplay.host.ImmersiveBars

import android.app.Activity
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

import jackpal.androidterm.ShellTermSession
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.util.TermSettings

/**
 * Hidden developer terminal (root). Opened from the About page by tapping the version text
 * five times quickly. Runs an interactive shell over a real PTY using the jackpal
 * Android-Terminal-Emulator stack (emulatorview + ShellTermSession + libjackpal-termexec2 /
 * libjackpal-androidterm5), instead of Runtime.exec which produced no output on the head unit.
 * Intended for a rooted head unit (Changan CS75 Plus, Freescale Android 4.4, API 19).
 */
class DevTerminalActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var session: ShellTermSession? = null
    private var emulatorView: EmulatorView? = null
    private var command: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "开发者终端 (root)"
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // System nav bar on this head unit sits on the LEFT edge and can't be hidden (the OEM
        // S311WT SystemUI ignores app-level immersive). Measure its width and inset the whole
        // page so the terminal output is never covered.
        val navWidth: Int = run {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("navigation_bar_width", "dimen", "android")
            val px = if (id > 0) sysRes.getDimensionPixelSize(id) else 0
            // Head-unit left-side nav bar is wider than the system default; fall back to a
            // generous inset (~1/9 of screen width) so the output is never covered.
            if (px > 0) px else (resources.displayMetrics.widthPixels / 9)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 12, 18))
            // Left inset keeps output clear of the left-side nav bar; small padding around the rest.
            setPadding(navWidth, dp(10), dp(10), dp(10))
        }

        // Title (top, small)
        root.addView(
            TextView(this).apply {
                text = "开发者终端 (root)"
                setTextColor(0xFF7FCD9A.toInt())
                textSize = 18f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) },
        )

        // Terminal output area (top, weight 1). Real PTY renderer from jackpal; placed above the
        // input row so output is never hidden behind the OEM nav bar. The EmulatorView MUST be
        // constructed with a real (non-null) session — passing null crashed in attachSession().
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        try {
            val settings = TermSettings(resources, getSharedPreferences("diplay_term", MODE_PRIVATE))
            val s = ShellTermSession(settings, null)
            s.initializeEmulator(80, 24)
            emulatorView = EmulatorView(this, s, metrics).apply {
                setDensity(metrics)
                setTextSize(18)
                setBackgroundColor(Color.BLACK)
            }
            session = s
            root.addView(emulatorView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { bottomMargin = dp(6) })
        } catch (e: Exception) {
            android.util.Log.e("DevTerm", "shell start failed", e)
            val where = e.stackTrace.firstOrNull()
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                ?: "(未知)"
            root.addView(
                TextView(this).apply {
                    text = "终端启动失败[${e.javaClass.simpleName}]: ${e.message ?: "(无详情)"}\n@ ${where}"
                    setTextColor(Color.RED)
                    textSize = 14f
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        // Command input row
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        command = EditText(this).apply {
            hint = "输入命令，如：echo ok"
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF.toInt())
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> exec(); true }
        }
        inputRow.addView(command, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(
            Button(this).apply { text = "执行"; setOnClickListener { exec() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) },
        )
        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6) })

        // Bottom button row
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(
            Button(this).apply { text = "返回"; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        applyImmersive()
    }

    private fun exec() {
        val s = session ?: return
        val cmd = command?.text?.toString() ?: return
        if (cmd.isEmpty()) return
        s.write(cmd)
        s.write('\r'.toInt())
        command?.text?.clear()
    }

    private fun applyImmersive() {
        ImmersiveBars.apply(this)
        main.postDelayed({ ImmersiveBars.apply(this) }, 250L)
        main.postDelayed({ ImmersiveBars.apply(this) }, 800L)
    }

    override fun onDestroy() {
        session?.finish()
        session = null
        super.onDestroy()
    }
}
