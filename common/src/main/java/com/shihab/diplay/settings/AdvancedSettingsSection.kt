package com.shihab.diplay.settings

import android.widget.LinearLayout
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.CarPlayRotation
import com.shihab.diplay.SidePanelSettings
import com.shihab.diplay.SplitScreenSettings
import com.shihab.diplay.host.R

/** Advanced category: experimental display + opt-in media controls. */
internal object AdvancedSettingsSection {

    fun populateExperimentalDisplay(card: LinearLayout, ui: SettingsSectionUi) {
        val ctx = ui.context
        ui.toggle(
            card,
            ui.getString(R.string.settings_harman_fullscreen_experimental),
            ui.getString(R.string.settings_harman_fullscreen_experimental_description),
            AirPlayPersistence.loadHarmanFullscreen(ctx),
        ) { enabled ->
            AirPlayPersistence.saveHarmanFullscreen(ctx, enabled)
        }
        ui.toggle(
            card,
            ui.getString(R.string.split_screen_areas),
            ui.getString(R.string.split_screen_areas_description),
            SplitScreenSettings.enabled(ctx),
        ) {
            SplitScreenSettings.setEnabled(ctx, it)
            ui.markReconnectNeeded()
        }
        ui.toggle(
            card,
            ui.getString(R.string.carplay_rotation),
            ui.getString(R.string.carplay_rotation_description),
            CarPlayRotation.enabled(ctx),
        ) {
            CarPlayRotation.setEnabled(ctx, it)
            ui.render()
            ui.markReconnectNeeded()
        }
        ui.toggle(
            card,
            ui.getString(R.string.side_panel),
            ui.getString(R.string.side_panel_description),
            SidePanelSettings.enabled(ctx),
        ) {
            SidePanelSettings.setEnabled(ctx, it)
            ui.markReconnectNeeded()
        }
        if (CarPlayRotation.enabled(ctx)) {
            val pictures = CarPlayRotation.Picture.entries
            ui.choice(
                card,
                ui.getString(R.string.carplay_rotation_picture),
                listOf(
                    ui.getString(R.string.carplay_rotation_smoother),
                    ui.getString(R.string.carplay_rotation_sharper),
                ),
                pictures.indexOf(CarPlayRotation.picture(ctx)),
                reconnects = false,
            ) {
                CarPlayRotation.setPicture(ctx, pictures[it])
                ui.markReconnectNeeded()
            }
        }
    }

    fun populateAdvancedMedia(
        card: LinearLayout,
        ui: SettingsSectionUi,
        audioFocusControls: (LinearLayout) -> Unit,
    ) {
        val ctx = ui.context
        // Opt-in controls that can cost sound or video on some head units.
        ui.toggle(
            card,
            ui.getString(R.string.efficient_video),
            ui.getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility),
            AirPlayPersistence.loadHevcEnabled(ctx),
        ) {
            AirPlayPersistence.saveHevcEnabled(ctx, it)
            ui.markReconnectNeeded()
        }
        ui.toggle(
            card,
            ui.getString(R.string.smooth_video),
            ui.getString(R.string.smooth_video_description),
            AirPlayPersistence.loadSmoothVideo(ctx),
        ) {
            AirPlayPersistence.saveSmoothVideo(ctx, it)
            ui.reconnectIfRunning()
        }
        ui.toggle(
            card,
            ui.getString(R.string.settings_direct_surface_view),
            ui.getString(R.string.settings_direct_surface_view_description),
            AirPlayPersistence.loadDirectSurfaceView(ctx),
        ) {
            AirPlayPersistence.saveDirectSurfaceView(ctx, it)
            ui.reconnectIfRunning()
        }
        ui.toggle(
            card,
            ui.getString(R.string.call_echo_cancellation),
            ui.getString(R.string.call_echo_cancellation_description),
            AirPlayPersistence.loadCallEchoCancellation(ctx),
        ) {
            AirPlayPersistence.saveCallEchoCancellation(ctx, it)
            ui.markReconnectNeeded()
        }
        ui.toggle(
            card,
            ui.getString(R.string.call_voice_filter),
            ui.getString(R.string.call_voice_filter_description),
            AirPlayPersistence.loadCallVoiceFilter(ctx),
        ) {
            AirPlayPersistence.saveCallVoiceFilter(ctx, it)
            ui.markReconnectNeeded()
        }
        audioFocusControls(card)
        if (ui.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
            ui.toggle(
                card,
                ui.getString(R.string.advanced_audio_channel_mapping),
                ui.getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                AirPlayPersistence.loadAdvancedAudioChannelMapping(ctx),
            ) {
                AirPlayPersistence.saveAdvancedAudioChannelMapping(ctx, it)
                ui.markReconnectNeeded()
            }
        }
        ui.toggle(
            card,
            ui.getString(R.string.main_buffered_audio),
            ui.getString(R.string.main_buffered_audio_description),
            AirPlayPersistence.loadMainBufferedAudio(ctx),
        ) {
            AirPlayPersistence.saveMainBufferedAudio(ctx, it)
            ui.reconnectIfRunning()
        }
    }
}
