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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class DiPlayUpdateDialogsTest {
    @Test fun updateConfirmationUsesCustomDialogAndOwnButtons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var clicked = false
        val dialog = DiPlayUpdateDialogs.confirm(
            activity, "发现新版本", "v0.2.43 已准备就绪",
            "立即更新", "稍后",
        ) { clicked = true }
        try {
            assertTrue(dialog.isShowing)
            val titleText = allViews(dialog.window!!.decorView).filterIsInstance<TextView>()
            assertTrue(titleText.any { it.text.toString().contains("发现新版本") })
            val update = allViews(dialog.window!!.decorView).filterIsInstance<Button>()
                .first { it.text.toString() == "立即更新" }
            update.performClick()
            assertTrue(clicked)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
        }
    }

    @Test fun progressDialogShowsPercentageAndVerifyingStage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val progress = DiPlayUpdateDialogs.progress(activity) {}
        try {
            progress.update(41)
            val descendants = allViews(progress.dialog.window!!.decorView).toList()
            assertTrue(descendants.filterIsInstance<TextView>().any { it.text == "41%" })
            assertEquals(41, descendants.filterIsInstance<ProgressBar>().single().progress)
            progress.verifying(activity)
            assertTrue(descendants.filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.update_verifying)
            })
        } finally {
            progress.dismiss()
        }
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            yieldAll(allViews(root.getChildAt(i)))
        }
    }
}
