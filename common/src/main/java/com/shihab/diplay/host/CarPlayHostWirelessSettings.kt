package com.shihab.diplay.host

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.CompoundButtonCompat
import com.shihab.diplay.messageResource
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.ManualHotspotBand
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.orchestration.isManualHotspotChannelCompatible

/**
 * Wireless-mode settings UI and hotspot status formatting for [com.shihab.diplay.CarPlayHostActivity].
 * Extracted to thin the host Activity; behavior is unchanged.
 */
internal class CarPlayHostWirelessSettings(private val host: Host) {

    data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )

    interface Host {
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
        fun dp(value: Int): Int
        fun menuText(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView
        fun settingsInputRow(
            label: String,
            value: String,
            password: Boolean = false,
            numeric: Boolean = false,
            onInputCreated: ((android.widget.EditText) -> Unit)? = null,
            onChanged: (String) -> Unit,
        ): View
        fun <T> settingsChoiceRow(
            label: String,
            options: List<Pair<T, String>>,
            selected: T,
            onSelected: (T) -> Unit,
        ): View
        fun appendLog(message: String)

        var wirelessHotspotMode: WirelessHotspotMode
        var wirelessEnabled: Boolean
        var hotspotStatus: HotspotStatus
        var manualHotspotSsid: String
        var manualHotspotPassphrase: String
        var manualHotspotBand: ManualHotspotBand
        var manualHotspotChannel: Int
        var manualHotspotSecurity: ManualHotspotSecurity
        var existingWifiSsid: String
        var existingWifiPassphrase: String
        var manualHotspotFields: View?
        var existingWifiFields: View?
        var existingWifiErrorView: TextView?
        var manualHotspotErrorView: TextView?
        var hotspotStatusView: TextView?

        val menuSecondary: Int
        val menuAccent: Int
        val menuDanger: Int
        val activityContext: android.content.Context
    }

    fun buildHotspotModeSection(): View {
        val section = LinearLayout(host.activityContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            host.menuText(host.getString(R.string.wi_fi_session), 20f, host.menuSecondary),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(host.activityContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, host.dp(8), 0, 0)
        }
        val modes = buildList {
            add(WirelessHotspotMode.MANUAL to host.getString(R.string.built_in_car_hotspot))
            add(WirelessHotspotMode.WIFI_P2P to host.getString(R.string.wifi_direct))
            add(WirelessHotspotMode.EXISTING_WIFI to host.getString(R.string.existing_wifi_title))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(host.activityContext).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(host.menuSecondary)
                CompoundButtonCompat.setButtonTintList(this, ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(host.menuAccent, host.menuSecondary),
                ))
                tag = mode
                isChecked = host.wirelessHotspotMode == mode
            }
            if (host.wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (host.wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            host.wirelessHotspotMode = selected
            host.hotspotStatus = HotspotStatus(state = if (host.wirelessEnabled) host.getString(R.string.hotspot_state_stopped) else host.getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            host.appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(host.wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(host.activityContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            host.settingsInputRow(host.getString(R.string.hotspot_ssid), host.manualHotspotSsid) { value ->
                host.manualHotspotSsid = value
                host.manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            host.settingsChoiceRow(
                label = host.getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to host.getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to host.getString(R.string.s_2_4_ghz),
                    ManualHotspotBand.GHZ_5 to host.getString(R.string.s_5_ghz),
                ),
                selected = host.manualHotspotBand,
            ) { value ->
                host.manualHotspotBand = value
                host.manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(10) },
        )

        manualFields.addView(
            host.settingsInputRow(
                label = host.getString(R.string.channel_0_auto),
                value = host.manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                host.manualHotspotChannel = value.toIntOrNull() ?: -1
                host.manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(10) },
        )

        manualFields.addView(
            host.settingsInputRow(
                label = host.getString(R.string.hotspot_password),
                value = host.manualHotspotPassphrase,
                password = true,
            ) { value ->
                host.manualHotspotPassphrase = value
                host.manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(10) },
        )

        manualFields.addView(
            host.settingsChoiceRow(
                label = host.getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to host.getString(R.string.open),
                    ManualHotspotSecurity.WPA2 to host.getString(R.string.wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to host.getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to host.getString(R.string.wpa3),
                ),
                selected = host.manualHotspotSecurity,
            ) { value ->
                host.manualHotspotSecurity = value
                host.manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(10) },
        )

        val error = host.menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = host.dp(8) },
        )
        host.manualHotspotFields = manualFields
        host.manualHotspotErrorView = error
        val existingFields = LinearLayout(host.activityContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(host.menuText(host.getString(R.string.existing_wifi_instructions), 14f, host.menuSecondary))
            addView(host.settingsInputRow(host.getString(R.string.existing_wifi_ssid), host.existingWifiSsid) {
                host.existingWifiSsid = it
                host.existingWifiErrorView?.visibility = View.GONE
            })
            addView(host.settingsInputRow(host.getString(R.string.existing_wifi_password), host.existingWifiPassphrase, password = true) {
                host.existingWifiPassphrase = it
                host.existingWifiErrorView?.visibility = View.GONE
            })
        }
        host.existingWifiErrorView = host.menuText("", 14f, host.menuDanger).apply { visibility = View.GONE }
        existingFields.addView(host.existingWifiErrorView)
        section.addView(existingFields)
        host.existingWifiFields = existingFields
        updateManualHotspotFields()
        return section
    }

    fun updateManualHotspotFields() {
        host.existingWifiFields?.visibility = if (host.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        host.existingWifiErrorView?.visibility = View.GONE
        val visible = host.wirelessHotspotMode == WirelessHotspotMode.MANUAL
        host.manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) host.manualHotspotErrorView?.visibility = View.GONE
    }


    fun manualHotspotSettingsError(): String? {
        if (host.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shihab.diplay.orchestration.ManualHotspotValidation.error(host.existingWifiSsid, host.existingWifiPassphrase)
            return error?.let { host.getString(it.messageResource()) }
        }
        if (host.wirelessHotspotMode != WirelessHotspotMode.MANUAL) return null
        return when {
            host.manualHotspotSsid.isBlank() -> host.getString(R.string.hotspot_ssid_is_required)
            host.manualHotspotSsid.encodeToByteArray().size > 32 ->
                host.getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in host.manualHotspotSsid -> host.getString(R.string.hotspot_ssid_contains_u_0000)
            host.manualHotspotChannel !in 0..196 -> host.getString(R.string.channel_must_be_0_or_1_196)
            host.manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(host.manualHotspotBand, host.manualHotspotChannel) ->
                host.getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in host.manualHotspotPassphrase -> host.getString(R.string.hotspot_password_contains_u_0000)
            host.manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                host.manualHotspotPassphrase.isNotEmpty() ->
                host.getString(R.string.password_must_be_empty_when_security_is_open)
            host.manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                host.manualHotspotPassphrase.length !in 8..63 ->
                host.getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
    }

    fun validateManualHotspotSettings(): Boolean {
        val error = manualHotspotSettingsError()
        host.existingWifiErrorView?.text = if (host.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) error.orEmpty() else ""
        host.existingWifiErrorView?.visibility = if (host.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI && error != null) View.VISIBLE else View.GONE
        host.manualHotspotErrorView?.text = error.orEmpty()
        host.manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> host.getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> host.getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> host.getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> host.getString(R.string.existing_wifi_title)
    }

    fun updateHotspotStatus(status: CarPlayStatus) {
        if (!host.wirelessEnabled) return
        host.hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = host.getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = host.getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                host.hotspotStatus.copy(state = host.getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                host.hotspotStatus.copy(state = host.getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                host.hotspotStatus.copy(state = host.getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                host.hotspotStatus.copy(state = host.getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                host.hotspotStatus.copy(state = host.getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> host.hotspotStatus.copy(state = host.getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    fun updateHotspotStatusBlock() {
        if (!host.wirelessEnabled) {
            host.hotspotStatusView?.text = host.getString(R.string.wireless_hotspot_off)
            return
        }
        val status = host.hotspotStatus
        host.hotspotStatusView?.text = buildString {
            append(host.getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(host.getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(host.getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(host.getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(host.getString(R.string.hotspot_channel_prefix))
                    .append(if (it == 0) host.getString(R.string.auto_label) else it.toString())
            }
        }
    }
}
