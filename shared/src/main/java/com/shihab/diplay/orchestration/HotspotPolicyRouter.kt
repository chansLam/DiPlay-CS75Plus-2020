package com.shihab.diplay.orchestration

import android.content.Context
import android.os.Build
import com.shihab.diplay.network.ExistingWifiManager
import com.shihab.diplay.network.LocalOnlyHotspotManager
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.network.WifiP2pGroupManager

/**
 * Single policy routing point over the hotspot backends: Wi-Fi P2P, LocalOnlyHotspot,
 * Manual/Car-hotspot, and Existing Wi-Fi.
 */
internal object HotspotPolicyRouter {
    fun create(
        mode: WirelessHotspotMode,
        context: Context,
        config: CarPlayRuntimeConfig,
        /** Plain diagnostic sink for P2P / LocalOnly / Existing Wi-Fi builds. */
        onLog: (String) -> Unit,
        /** Manual-hotspot diagnostic sink (caller decorates it with the run generation). */
        onManualDiagnostic: (String) -> Unit,
        isCancelled: () -> Boolean,
        onNetworkChanged: () -> Unit,
    ): WirelessHotspotManager = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> {
            check(Build.VERSION.SDK_INT >= 28)
            WifiP2pGroupManager(context, onLog, preferredChannel = config.wifiP2pPreferredChannel)
        }
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> {
            check(Build.VERSION.SDK_INT >= 26) { "Local hotspot requires Android 8; choose Wi-Fi Direct or Car hotspot" }
            LocalOnlyHotspotManager(context, onLog)
        }
        WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
            context, config.existingWifiSsid, config.existingWifiPassphrase, onLog,
            onNetworkChanged = { onNetworkChanged() },
        )
        WirelessHotspotMode.MANUAL -> ManualHotspotManager(
            context = context,
            ssid = config.manualHotspotSsid
                ?: throw WirelessStartupException(
                    WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "Manual hotspot SSID is not configured",
                ),
            passphrase = config.manualHotspotPassphrase.orEmpty(),
            band = config.manualHotspotBand,
            channel = config.manualHotspotChannel,
            security = config.manualHotspotSecurity,
            onDiagnostic = onManualDiagnostic,
            isCancelled = isCancelled,
        )
    }
}
