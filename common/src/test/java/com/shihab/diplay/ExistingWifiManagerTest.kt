package com.shihab.diplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.shihab.diplay.network.ExistingWifiManager
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KitKat Existing Wi-Fi attaches via legacy station APIs. Full attach needs a live
 * [java.net.NetworkInterface] address match, which Robolectric does not always provide,
 * so this suite covers construction validation only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExistingWifiManagerTest {
    private val context = mock(Context::class.java)

    @Before fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mock(ConnectivityManager::class.java))
        `when`(context.getSystemService(Context.WIFI_SERVICE))
            .thenReturn(mock(WifiManager::class.java))
    }

    @Test fun rejectsInvalidCredentialsAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            ExistingWifiManager(context, "Pocket Wi-Fi", "short")
        }
    }

    @Test fun acceptsOpenAndWpa2Credentials() {
        ExistingWifiManager(context, "Pocket Wi-Fi", "").close()
        ExistingWifiManager(context, "Pocket Wi-Fi", "pocket-password").close()
    }
}
