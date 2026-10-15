package com.shihab.diplay.orchestration

import com.shihab.diplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfiTargetConfigTest {
    @Test
    fun i2cTargetUsesTheConfiguredDevicePath() {
        val config = config(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-7",
        )

        assertEquals("/dev/i2c-7", config.linuxI2cPath)
    }

    @Test
    fun i2cTargetRejectsABlankDevicePath() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.I2C, linuxI2cPath = "  ")
        }
    }

    @Test
    fun remoteTargetDoesNotRequireALocalDevice() {
        val config = config(
            mfiTarget = MfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.test",
            remoteMfiToken = "secret",
        )

        assertEquals(MfiTarget.REMOTE, config.mfiTarget)
        assertEquals("https://mfi.example.test", config.remoteMfiServer)
        assertEquals("secret", config.remoteMfiToken)
    }

    @Test
    fun remoteTargetRequiresAServerAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            config(mfiTarget = MfiTarget.REMOTE, remoteMfiServer = "  ")
        }
    }

    @Test
    fun wiredTransportIgnoresIncompleteManualHotspotSettings() {
        val config = config(
            mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRED,
            wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        )

        assertEquals(CarPlayTransport.WIRED, config.transport)
    }

    @Test
    fun wirelessManualHotspotStillRequiresSsid() {
        assertThrows(IllegalArgumentException::class.java) {
            config(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                manualHotspotSsid = null,
            )
        }
    }

    private fun config(
        mfiTarget: MfiTarget,
        linuxI2cPath: String? = null,
        remoteMfiServer: String? = null,
        remoteMfiToken: String? = null,
        transport: CarPlayTransport = CarPlayTransport.WIRED,
        wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid: String? = "DiPlay",
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        linuxI2cPath = linuxI2cPath,
        remoteMfiServer = remoteMfiServer,
        remoteMfiToken = remoteMfiToken,
        transport = transport,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
