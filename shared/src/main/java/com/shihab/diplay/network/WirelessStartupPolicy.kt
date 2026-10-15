package com.shihab.diplay.network

import java.io.IOException

object WirelessStartupPolicy {
    /**
     * Wait for SoftAP to report ENABLED after [CarHotspotStatus.enableIfPossible] /
     * [CarHotspotTethering] (STA teardown + SoftAP bring-up on Freescale can take several seconds).
     */
    const val SOFTAP_ENABLE_MILLIS = 12_000L

    /**
     * Wait for the SoftAP interface to expose a usable address after the AP is already on.
     * Two 250ms samples (~500ms) confirm stability; 8s leaves headroom without slowing the
     * common case where the car hotspot was already running.
     */
    const val INTERFACE_READY_MILLIS = 8_000L

    /** @deprecated Use [INTERFACE_READY_MILLIS] / [SOFTAP_ENABLE_MILLIS]; kept for call-site clarity. */
    const val HOTSPOT_READY_MILLIS = INTERFACE_READY_MILLIS

    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 2
    const val FIRST_TCP_MILLIS = 30_000L
    /** Wi-Fi-only discovery wait when Bluetooth bootstrap is unavailable. */
    const val WIFI_DISCOVERY_WAIT_MILLIS = 45_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure { HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION }

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
