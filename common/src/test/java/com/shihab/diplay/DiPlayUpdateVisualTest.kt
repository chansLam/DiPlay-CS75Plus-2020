package com.shihab.diplay

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.shihab.diplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Protect the styled update, background-download and post-download install surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class DiPlayUpdateVisualTest {
    @Test fun releaseNotesAndActionsUseBrandedDialog() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var started = false
        val dialog = DiPlayUpdateDialogs.available(
            activity, "v0.2.44", "- 优化车机显示\n- 修复连接问题",
            "请停车后安装", "立即更新", "稍后") { started = true }
        try {
            val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .map { it.text.toString() }.toList()
            assertTrue(labels.contains(activity.getString(R.string.update_available_heading)))
            assertTrue(labels.any { it.contains("v0.2.44") })
            assertTrue(labels.any { it.contains("优化车机显示") })
            descendants(dialog.window!!.decorView).filterIsInstance<Button>()
                .first { it.text.toString() == "立即更新" }.performClick()
            assertTrue(started)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
        }
    }

    @Test fun verifiedInstallerPromptIsInAppAndStillRequiresConsent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var launch = false
        val dialog = DiPlayUpdateDialogs.installReady(
            activity, "v0.2.44", "APK 已校验，可以打开系统安装程序。",
            "打开安装程序", "稍后") { launch = true }
        try {
            val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .map { it.text.toString() }.toList()
            assertTrue(labels.contains(activity.getString(R.string.update_ready_heading)))
            assertTrue(labels.any { it.contains("v0.2.44") })
            assertFalse(launch)
            descendants(dialog.window!!.decorView).filterIsInstance<Button>()
                .first { it.text.toString() == "打开安装程序" }.performClick()
            assertTrue(launch)
        } finally {
            dialog.dismiss()
        }
    }

    @Test fun downloadBackgroundButtonDoesNotCancelAndPhasesAdvance() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var cancelled = false
        var backgrounded = false
        val progress = DiPlayUpdateDialogs.progress(
            activity, background = { backgrounded = true }) { cancelled = true }
        try {
            progress.update(41, 12_800_000L, 31_400_000L, 8_400_000L)
            val nodes = descendants(progress.dialog.window!!.decorView).toList()
            assertEquals(41, nodes.filterIsInstance<ProgressBar>().single().progress)
            assertTrue(nodes.filterIsInstance<TextView>().any { it.text == "41%" })
            assertTrue(nodes.filterIsInstance<TextView>().any { it.text.contains("MB/s") })
            progress.verifying(activity)
            assertTrue(nodes.filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.update_verifying)
            })
            progress.preparing(activity)
            assertTrue(nodes.filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.update_preparing)
            })
            nodes.filterIsInstance<Button>()
                .first { it.text.toString() == activity.getString(R.string.update_continue_background) }
                .performClick()
            assertTrue(backgrounded)
            assertFalse(cancelled)
            assertFalse(progress.dialog.isShowing)
        } finally {
            progress.dismiss()
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            yieldAll(descendants(root.getChildAt(i)))
        }
    }
}
