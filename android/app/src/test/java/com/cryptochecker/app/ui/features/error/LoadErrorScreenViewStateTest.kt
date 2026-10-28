package com.cryptochecker.app.ui.features.error

import com.cryptochecker.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Fehlerseite: Symbol, Titel und Satz je Fehlerart (Aufbau wie jeder leere Zustand). */
class LoadErrorScreenViewStateTest {

    @Test
    fun network() {
        val state = LoadErrorScreenViewState(IOException("offline"))
        assertEquals(R.drawable.ic_cloud_off, state.iconRes())
        assertEquals(R.string.offline_status, state.titleRes())
        assertEquals(R.string.no_internet_connection, state.messageRes())
    }

    @Test
    fun timeoutBeforeNetwork() {
        // Zeitüberschreitung ist auch eine IOException: eigener Titel und Satz
        val state = LoadErrorScreenViewState(SocketTimeoutException())
        assertEquals(R.drawable.ic_cloud_off, state.iconRes())
        assertEquals(R.string.error_market_unreachable_short, state.titleRes())
        assertEquals(R.string.timeout_error_message, state.messageRes())
    }

    @Test
    fun otherError() {
        val state = LoadErrorScreenViewState(IllegalStateException("boom"))
        assertEquals(R.drawable.ic_error, state.iconRes())
        assertEquals(R.string.something_went_wrong, state.titleRes())
        assertNull(state.messageRes())
    }
}
