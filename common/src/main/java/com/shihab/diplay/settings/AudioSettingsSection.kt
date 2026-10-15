package com.shihab.diplay.settings

import android.widget.LinearLayout
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.host.R
import com.shihab.diplay.media.MediaAudioBuffer

/** Audio category card bodies for DiPlay settings. */
internal object AudioSettingsSection {

    fun populateRouting(
        card: LinearLayout,
        ui: SettingsSectionUi,
        mediaChannelControl: (LinearLayout) -> Unit,
        navigationChannelControl: (LinearLayout) -> Unit,
    ) {
        mediaChannelControl(card)
        navigationChannelControl(card)
        val bufferPresets = MediaAudioBuffer.presets
        ui.choice(
            card,
            ui.getString(R.string.music_buffer),
            listOf(
                ui.getString(R.string.s_300_ms_default),
                ui.getString(R.string.s_500_ms),
                ui.getString(R.string.s_1000_ms_most_stable),
            ),
            bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(ui.context)).coerceAtLeast(0),
        ) {
            AirPlayPersistence.saveMediaBufferMillis(ui.context, bufferPresets[it])
        }
    }
}
