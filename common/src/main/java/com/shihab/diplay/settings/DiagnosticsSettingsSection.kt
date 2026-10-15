package com.shihab.diplay.settings

import android.widget.Button
import android.widget.LinearLayout
import com.shihab.diplay.host.R

/** Diagnostics category card body for DiPlay settings. */
internal object DiagnosticsSettingsSection {

    fun populate(
        card: LinearLayout,
        ui: SettingsSectionUi,
        exportInProgress: Boolean,
        onExport: () -> Unit,
        onChooseDestination: () -> Unit,
        onExportButtonCreated: (Button) -> Unit,
    ) {
        val exportButton = ui.button(
            if (exportInProgress) ui.getString(R.string.saving_report)
            else ui.getString(R.string.save_diagnostic_report),
            false,
            onExport,
        ).apply { isEnabled = !exportInProgress }
        onExportButtonCreated(exportButton)
        card.addView(exportButton, ui.matchButton(10, 60))
        card.addView(
            ui.button(ui.getString(R.string.choose_save_location), false, onChooseDestination),
            ui.matchButton(10, 60),
        )
        val destination = ui.getString(R.string.choose_where_to_save_your_report)
        card.addView(
            ui.label(
                destination + ui.getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden),
                14,
                ui.mutedColor(),
            ).apply { setPadding(0, ui.dp(12), 0, 0) },
        )
    }
}
