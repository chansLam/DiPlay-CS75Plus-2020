package com.shihab.diplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.shihab.diplay.compat.BluetoothCompat
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.BluetoothRfcommDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    private val adapterName: String = "AndroidBluetoothAdapter"

    private val adapter: BluetoothAdapter?
        get() = runCatching { BluetoothCompat.getAdapter(context) }.getOrNull()

    override fun isSupported(): Boolean = adapter != null

    override fun isEnabled(): Boolean = runCatching {
        adapter?.isEnabled == true
    }.getOrDefault(false)

    override fun enable(): Boolean = runCatching {
        val a = adapter ?: return false
        if (a.isEnabled) {
            return true
        }
        a.enable()
    }.getOrDefault(false)

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        val a = adapter ?: return emptyList()
        return runCatching {
            (a.bondedDevices ?: emptySet()).map { device ->
                CarPlayBluetoothDevice(
                    name = device.name ?: "Unknown",
                    address = device.address,
                    isConnected = isDeviceConnected(device)
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val paired = getPairedDevices()
        return paired.firstOrNull { it.isConnected }
    }

    override fun localAddress(context: Context): String? {
        return BluetoothAddressResolver.resolve(context)
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        }.getOrDefault(false)
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val a = adapter ?: throw IOException("Bluetooth adapter is unavailable")
        val device = a.getRemoteDevice(deviceAddress)
        val serviceUuid = UUID.fromString(uuid)

        var socket: BluetoothSocket? = null
        var lastError: Throwable? = null

        // 1. Try standard secure RFCOMM
        try {
            socket = connectSocket(device, a) { it.createRfcommSocketToServiceRecord(serviceUuid) }
        } catch (t: Throwable) {
            lastError = t
        }

        // 2. Fallback to insecure RFCOMM on legacy or custom vehicle ROMs where MITM pairing is absent
        if (socket == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
            try {
                socket = connectSocket(device, a) { it.createInsecureRfcommSocketToServiceRecord(serviceUuid) }
            } catch (t: Throwable) {
                lastError = t
            }
        }

        // 3. Channel 1 reflection fallback if SDP service record resolution was stripped by vehicle ROM
        if (socket == null) {
            try {
                socket = connectSocket(device, a) { dev ->
                    val method = dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(dev, 1) as BluetoothSocket
                }
            } catch (_: Throwable) {
                // Ignore reflection fallback error and keep primary lastError
            }
        }

        if (socket == null) {
            if (lastError is IOException) throw lastError
            throw IOException("Failed to connect Bluetooth RFCOMM to $deviceAddress", lastError)
        }

        return BluetoothRfcommDuplexStream(socket)
    }

    private fun connectSocket(
        device: BluetoothDevice,
        adapter: BluetoothAdapter,
        // KitKat Freescale stacks often need >10s for the first iAP2 RFCOMM.
        timeoutMillis: Long = 18_000L,
        socketFactory: (BluetoothDevice) -> BluetoothSocket,
    ): BluetoothSocket {
        val socket = socketFactory(device)
        val result = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Thread({
            try {
                runCatching { adapter.cancelDiscovery() }
                socket.connect()
            } catch (t: Throwable) {
                result.set(t)
            } finally {
                latch.countDown()
            }
        }, "aosp-rfcomm-connect").apply {
            isDaemon = true
            start()
        }

        val completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            runCatching { socket.close() }
            throw IOException("Timed out connecting AOSP RFCOMM to ${device.address}")
        }

        val err = result.get()
        if (err != null) {
            runCatching { socket.close() }
            if (err is IOException) throw err
            throw IOException("Could not connect RFCOMM", err)
        }

        return socket
    }
}
