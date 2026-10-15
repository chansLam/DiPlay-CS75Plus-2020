package com.shihab.diplay.network

import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Reads the framework's hidden SoftAP (Wi-Fi tethering) configuration via reflection.
 *
 * Consolidated in P2: this exact `WifiManager.getWifiApConfiguration` reflective read was
 * previously duplicated in [CarHotspotStatus] and [ManualHotspotManager]. Behaviour is
 * identical — any reflection failure yields `null` instead of throwing.
 */
@SuppressLint("PrivateApi")
internal fun readSoftApConfiguration(wifi: WifiManager): WifiConfiguration? =
    runCatching {
        @Suppress("DEPRECATION")
        WifiManager::class.java
            .getMethod("getWifiApConfiguration")
            .invoke(wifi) as? WifiConfiguration
    }.getOrNull()
