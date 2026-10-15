package com.shihab.diplay.network

import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.BplistCodec
import com.shihab.diplay.airplay.PairingStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

/** Uses real loopback sockets; this does not simulate iPhone authentication or media streaming. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], manifest = Config.NONE)
class AndroidEightCarPlayServiceTest {
    private val address = InetAddress.getByName("127.0.0.1")

    @Test fun startsAnswersCarPlayInfoAndRestartsAfterDetach() {
        val controller = Robolectric.buildService(CarPlayWirelessService::class.java).create()
        val service = controller.get()
        try {
            repeat(2) { attempt ->
                val port = attach(service, 0)
                assertTrue(service.isAttached())
                val info = fetchInfo(port)
                assertEquals("Android 8 service test", info["name"])
                val display = (info["displays"] as List<*>).single() as Map<*, *>
                assertEquals(1024, (display["widthPixels"] as Number).toInt())
                assertEquals(600, (display["heightPixels"] as Number).toInt())
                assertFalse((info["audioFormats"] as List<*>).isEmpty())
                assertFalse((info["hidDevices"] as List<*>).isEmpty())
                service.detach()
                assertFalse("Detached on attempt $attempt", service.isAttached())
                // TIME_WAIT can briefly keep the port busy after detach on some JVMs/CI runners.
                assertTrue("Port $port still held after detach (attempt $attempt)", portReleased(port))
            }
        } finally { controller.destroy() }
    }

    @Test fun occupiedWirelessPortFallsBackToAWorkingListener() {
        val controller = Robolectric.buildService(CarPlayWirelessService::class.java).create()
        try {
            ServerSocket(0, 1, address).use { occupied ->
                val port = attach(controller.get(), occupied.localPort)
                assertNotEquals(occupied.localPort, port)
                assertEquals("Android 8 service test", fetchInfo(port)["name"])
            }
        } finally { controller.destroy() }
    }

    private fun attach(service: CarPlayWirelessService, port: Int): Int {
        val result = service.attachWireless(
            bindAddress = address,
            config = AirPlayConfig(
                deviceName = "Android 8 service test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(1024, 600, fps = 30),
                port = port,
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
        assertTrue("Listener startup: $result", result is CarPlayWirelessService.AttachResult.Started)
        return service.boundPort() ?: port
    }

    private fun fetchInfo(port: Int): Map<*, *> {
        val client = URL("http://127.0.0.1:$port/info").openConnection() as HttpURLConnection
        client.connectTimeout = 5000
        client.readTimeout = 5000
        client.setRequestProperty("CSeq", "1")
        try {
            assertEquals(200, client.responseCode)
            assertEquals("application/x-apple-binary-plist", client.contentType)
            return client.inputStream.use { BplistCodec.decode(it.readBytes()) as Map<*, *> }
        } finally { client.disconnect() }
    }

    private fun portReleased(port: Int): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            try {
                ServerSocket().use { released ->
                    released.reuseAddress = true
                    released.bind(InetSocketAddress(address, port))
                }
                return true
            } catch (_: java.net.BindException) {
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(25)
            }
        }
    }
}
