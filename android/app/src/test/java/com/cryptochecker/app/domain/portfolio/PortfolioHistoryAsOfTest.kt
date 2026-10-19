package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Älterer Stand im Wertverlauf (eigene Börse nicht erreichbar); Gegenstück `PortfolioHistoryAsOfTests.swift`. */
class PortfolioHistoryAsOfTest {

    private val now = 1_700_000_000_000L
    private val ttl = 12 * 60 * 60_000L

    @Test
    fun oldestStaleTimeAmongDrawnCoins() {
        val stale = mapOf("BTC" to now - 3 * ttl, "ETH" to now - 2 * ttl, "SOL" to now - 5 * ttl)
        // SOL ist nicht gezeichnet (ohne Verlauf) und zählt nicht
        assertEquals(now - 3 * ttl, PortfolioHistoryAsOf.of(stale, listOf("BTC", "ETH", "ADA")))
        assertNull(PortfolioHistoryAsOf.of(stale, listOf("ADA")))
        assertNull(PortfolioHistoryAsOf.of(emptyMap(), listOf("BTC")))
        assertNull(PortfolioHistoryAsOf.of(mapOf("BTC" to 0L), listOf("BTC")))
    }

    @Test
    fun fallbackIsStaleOnlyWhenOlderThanTheCacheTime() {
        // Jünger als 12 h: heutiger Verlauf (nur evtl. kürzer), kein «Stand …»
        assertNull(PortfolioHistoryAsOf.staleSince(now - 60_000L, now, ttl))
        // Älter: mit seiner Zeit markiert
        assertEquals(now - ttl, PortfolioHistoryAsOf.staleSince(now - ttl, now, ttl))
        assertEquals(now - 3 * ttl, PortfolioHistoryAsOf.staleSince(now - 3 * ttl, now, ttl))
        // Unbekannte Zeit gilt nicht als frisch; künftige (Uhr zurückgestellt) wie frisch, nie eine künftige Anzeige
        assertEquals(0L, PortfolioHistoryAsOf.staleSince(0L, now, ttl))
        assertNull(PortfolioHistoryAsOf.staleSince(now + 60_000L, now, ttl))
    }
}
