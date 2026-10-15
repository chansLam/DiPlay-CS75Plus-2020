package com.shihab.diplay.orchestration

import android.content.Context
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupPolicy

/**
 * P5: car-hotspot (framework SoftAP) enable precondition extracted from CarPlayController.
 *
 * Polls until the hidden `setWifiApEnabled` request takes effect, honouring cancellation.
 * Behaviour is unchanged: same deadline, same poll interval, same failure reason.
 */
internal object CarHotspotEnableGuard {
    fun awaitSoftApEnabled(context: Context, isCancelled: () -> Boolean) {
        val deadline = System.nanoTime() + WirelessStartupPolicy.SOFTAP_ENABLE_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isCancelled() || Thread.currentThread().isInterrupted) {
                throw java.io.InterruptedIOException("SoftAP enable wait cancelled")
            }
            if (CarHotspotStatus.isEnabled(context) == true) return
            try {
                Thread.sleep(WirelessStartupPolicy.INTERFACE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("SoftAP enable wait interrupted")
            }
        }
        if (CarHotspotStatus.isEnabled(context) != true) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Timed out waiting for the car SoftAP to enable. Turn the hotspot on in car settings and connect again.",
            )
        }
    }
}
