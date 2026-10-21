package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Einordnung einer gescheiterten DEX-Suche ([DexFailure]) — wie `DexFailureTests` (iOS). */
class DexFailureTest {

    private fun kindOf(error: Throwable): DexFailure =
        DexFailure.of(RefreshReportLogic.classify(error.toMarketFailure(), error.toString()))

    @Test
    fun ursachenWieInDerMerkliste() {
        // Zeitüberschreitung zählt wie in der Merkliste als «Keine Verbindung»
        assertEquals(DexFailure.OFFLINE, DexFailure.of(RefreshFailure.OFFLINE))
        assertEquals(DexFailure.OFFLINE, DexFailure.of(RefreshFailure.TIMEOUT))
        assertEquals(DexFailure.UNREACHABLE, DexFailure.of(RefreshFailure.SERVER))
        assertEquals(DexFailure.UNREACHABLE, DexFailure.of(RefreshFailure.RATE_LIMIT))
        assertEquals(DexFailure.OTHER, DexFailure.of(RefreshFailure.NO_DATA))
        assertEquals(DexFailure.OTHER, DexFailure.of(RefreshFailure.UNAVAILABLE))
        assertEquals(DexFailure.OTHER, DexFailure.of(RefreshFailure.OTHER))
    }

    @Test
    fun ausnahmenWerdenEingeordnet() {
        assertEquals(DexFailure.OFFLINE, kindOf(UnknownHostException("api.dexscreener.com")))
        assertEquals(DexFailure.OFFLINE, kindOf(SocketTimeoutException("read timed out")))
        assertEquals(DexFailure.OTHER, kindOf(IllegalStateException("kaputt")))
    }
}
