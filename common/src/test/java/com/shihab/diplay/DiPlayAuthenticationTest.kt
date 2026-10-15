package com.shihab.diplay

import android.os.Looper
import com.shihab.diplay.airplay.*
import com.shihab.diplay.orchestration.*
import com.shihab.diplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayAuthenticationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.noBackupFilesDir, "offline-mfi")

    @Before fun setUp() {
        // The JVM test host has no vehicle SOME/IP gateway.
        shadowOf(context).declareActionUnbindable("com.ts.car.someip.SomeIpServerService")
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    @Test fun freshInstallStillDefaultsToLocalAuthentication() {
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun savedI2cChoiceSurvivesBootstrapWithoutLocalAssets() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.I2C)
        DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(MfiTarget.I2C, AirPlayPersistence.loadMfiTarget(context))
        assertFalse(directory.exists())
        assertFalse(File(context.noBackupFilesDir, "offline-mfi-staging").exists())
    }

    @Test fun i2cBootstrapDoesNotReadAnExistingBrokenLocalIdentity() {
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid local identity")
        DiPlayBootstrap.ensure(context, MfiTarget.I2C)
        assertEquals("invalid local identity", File(directory, "identity.pk8").readText())
        assertFalse(File(directory, "certificate.p7b").exists())
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun switchingFromI2cToLocalStillRequiresLocalIdentity() {
        DiPlayBootstrap.ensure(context, MfiTarget.I2C)
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun missingLocalIdentityFailsWithoutFallingBackToI2c() {
        val statuses = startAuthentication(MfiTarget.LOCAL)
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
    }

    private fun startAuthentication(target: MfiTarget): List<CarPlayStatus> {
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = target,
                linuxI2cPath = "/dev/i2c-99",
                transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            { statuses.add(it) },
        )
        try {
            controller.javaClass.getDeclaredMethod("startMfi").apply { isAccessible = true }.invoke(controller)
            val executor = controller.javaClass.getDeclaredField("executor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            return statuses.toList()
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }
}
