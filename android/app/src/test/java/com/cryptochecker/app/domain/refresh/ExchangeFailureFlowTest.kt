package com.cryptochecker.app.domain.refresh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Art des Fehlers ([ExchangeFailure]) wandert mit dem Text durch die Kursabfrage: Sperre nach 429/418
 * ([SingleRateGate]) und Börsentext im Bericht ([StoredError.reportDetail]) richten sich nach ihr.
 */
class ExchangeFailureFlowTest {

    @Test
    fun gateBlocksOnTypedRateLimitAndKeepsTheKind() = runBlocking {
        val gate = SingleRateGate()
        val limited = ExchangeFailure.RateLimited(5, 429)
        gate.send { Fetched(null, "throttled", failure = limited) }
        assertEquals("throttled", gate.blocked)
        var sent = 0
        val next = gate.send { sent++; Fetched(null, null) }
        assertEquals(0, sent)
        assertEquals("throttled", next.error)
        assertEquals(limited, next.failure)
    }

    @Test
    fun typedKindWinsOverMisleadingText() = runBlocking {
        val gate = SingleRateGate()
        // Zeitüberschreitung, deren Text zufällig «too many requests» enthält: keine Sperre
        gate.send { Fetched(null, "proxy: too many requests timeout", failure = ExchangeFailure.Timeout) }
        assertNull(gate.blocked)
        // Ohne Art (gespeicherter Text): über die Stichwörter
        assertEquals(true, SingleRateGate.isRateLimit("EGeneral:Too many requests"))
        assertEquals(false, SingleRateGate.isRateLimit("EGeneral:Too many requests", ExchangeFailure.HttpStatus(404)))
    }

    @Test
    fun reportDetailFiltersByTheGivenCauses() {
        val errors = listOf("Invalid API key", "Invalid API key", "HttpCode: 500")
        // Ursachen aus der Art: die beiden Meldungen der Börse gelten als SERVER
        val failures = listOf(RefreshFailure.SERVER, RefreshFailure.SERVER, RefreshFailure.SERVER)
        assertEquals("Invalid API key", StoredError.reportDetail(errors, RefreshFailure.SERVER, failures))
        // Ohne Angabe: aus dem Text (dort sind die Meldungen OTHER)
        assertNull(StoredError.reportDetail(errors, RefreshFailure.SERVER))
        assertEquals("Invalid API key", StoredError.reportDetail(errors, RefreshFailure.OTHER))
    }
}
