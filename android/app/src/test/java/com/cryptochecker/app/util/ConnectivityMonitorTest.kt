package com.cryptochecker.app.util

import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Online» nur mit bestätigtem Netz: ein Captive-Portal-WLAN (INTERNET ohne VALIDATED) gilt als offline. */
class ConnectivityMonitorTest {
    private fun caps(vararg present: Int): (Int) -> Boolean = { it in present }

    @Test
    fun validatedInternetIsOnline() {
        assertTrue(
            ConnectivityMonitor.usable(
                caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            ),
        )
    }

    @Test
    fun captivePortalIsOffline() {
        assertFalse(ConnectivityMonitor.usable(caps(NetworkCapabilities.NET_CAPABILITY_INTERNET)))
        assertFalse(
            ConnectivityMonitor.usable(
                caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            ),
        )
    }

    @Test
    fun validatedWithoutInternetIsOffline() {
        assertFalse(ConnectivityMonitor.usable(caps(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
        assertFalse(ConnectivityMonitor.usable(caps()))
    }
}
