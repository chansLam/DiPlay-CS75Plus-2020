package com.shihab.diplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * legacy #151: high-priority media-button receiver for GKUI/ECARX head units.
 *
 * On these units the steering-wheel keys arrive as an ordered ACTION_MEDIA_BUTTON
 * broadcast that the car's own mediacenter receiver (ecarx.xsf.mediacenter) consumes
 * before DiPlay's MediaSession ever sees it, so next/previous never reach the iPhone.
 * This receiver runs at a high ordered-broadcast priority and is only enabled at
 * runtime on ECARX/GKUI units (see [CarPlayMediaKeys]); everywhere else the manifest
 * keeps it disabled, so media-key routing is untouched.
 *
 * It only aborts the broadcast when CarPlay actually consumes the key — otherwise the
 * car's own player keeps the keys, exactly as before.
 */
class WheelMediaKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        if (CarPlayMediaKeys.handleWheelMediaKey(event.keyCode)) abortBroadcast()
    }
}
