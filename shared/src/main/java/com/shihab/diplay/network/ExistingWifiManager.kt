package com.shihab.diplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Looper
import com.shihab.diplay.orchestration.ManualHotspotValidation
import com.shihab.diplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    @Suppress("DEPRECATION")
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("ConnectivityManager is unavailable")
    @Suppress("DEPRECATION")
    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    private var monitor: Thread? = null

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(timeoutMillis > 0)
        synchronized(lock) {
            check(!closed) { "Existing Wi-Fi attachment was cancelled" }
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!closed && System.nanoTime() < deadline) {
            val address = stationAddress()
            val iface = address?.let { target ->
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .firstOrNull { it.isUp && !it.isLoopback && Collections.list(it.inetAddresses).contains(target) }
            }
            if (address != null && iface != null) {
                val addresses = existingWifiHostAddresses(Collections.list(iface.inetAddresses), iface.index)
                onDiagnostic(
                    "Existing Wi-Fi attached legacy iface=${iface.name} host=${address.hostAddress} channel=auto",
                )
                monitor = Thread({
                    try {
                        while (!closed) {
                            Thread.sleep(1000)
                            if (!closed && runCatching { stationAddress() }.getOrNull() != address) {
                                onNetworkChanged()
                                return@Thread
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }, "diplay-legacy-wifi").apply { isDaemon = true; start() }
                return WirelessHotspotInfo(
                    ssid,
                    passphrase,
                    if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
                    0,
                    null,
                    null,
                    iface.name,
                    address,
                    "Auto",
                    WirelessHotspotBackend.EXISTING_WIFI,
                    hostAddresses = addresses,
                )
            }
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi is unavailable or startup was cancelled")
    }

    @Suppress("DEPRECATION")
    private fun stationAddress(): InetAddress? {
        if (connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected != true) return null
        val info = wifi.connectionInfo ?: return null
        val live = info.ssid?.removeSurrounding("\"")
        if (!live.isNullOrEmpty() && live != WifiManager.UNKNOWN_SSID && live != ssid) {
            throw IOException("Saved Wi-Fi name does not match the connected Wi-Fi")
        }
        val ip = info.ipAddress
        return if (ip == 0) null else InetAddress.getByAddress(ByteArray(4) { (ip ushr (8 * it)).toByte() })
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            monitor?.interrupt()
        }
    }
}
