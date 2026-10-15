package com.shihab.diplay.settings

/**
 * P6: settings information architecture (categories / sections / routing map),
 * relocated from DiPlayActivity. Pure data — no Android View or Activity dependency —
 * so it lives beside the per-category renderers in the `settings/` package.
 */
internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, DIAGNOSTICS, ADVANCED,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    HEAD_UNIT_APPS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    DISPLAY_AND_PERFORMANCE,
    EXPERIMENTAL_DISPLAY,
    ADVANCED_MEDIA,
    AUDIO_ROUTING,
    LOCATION,
    PERMISSIONS_AND_HELP,
    ABOUT,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    // CS75 Plus: no BYD ADB/HUD/cluster, no multi-brand OEM button picker.
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to setOf(SettingsSection.ABOUT, SettingsSection.LANGUAGE),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.HEAD_UNIT_APPS,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.EXPERIMENTAL_DISPLAY,
            SettingsSection.ADVANCED_MEDIA,
        ),
    )
}
