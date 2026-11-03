package com.cryptochecker.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Abruf der Uhr ([WatchPriceSource]): Ein Abbruch wird nicht geschluckt (kein Weiterfragen bei Bybit/OKX), und der
 * ganze Abruf hat eine Gesamtfrist (wie die Apple Watch `WatchQuoteFetch.within`).
 */
class WatchPriceSourceTest {

    @Test
    fun cancellationIsNotSwallowed() {
        val asked = ArrayList<String>()
        runBlocking {
            try {
                WatchPriceSource.fetch(listOf("BTC", "ETH")) { url ->
                    asked += url
                    throw CancellationException("cancelled")
                }
                fail("Abbruch geschluckt")
            } catch (expected: CancellationException) {
                // erwartet
            }
        }
        // Nach dem Abbruch fragt der Abruf keine weitere Börse
        assertEquals(1, asked.size)
    }

    @Test
    fun failureFallsBackToTheNextExchange() {
        val asked = ArrayList<String>()
        val result = runBlocking {
            WatchPriceSource.fetch(listOf("BTC")) { url ->
                asked += url
                throw IllegalStateException("HTTP 451")
            }
        }
        assertTrue(result.isEmpty())
        // Binance, dann Bybit und OKX je Coin
        assertEquals(3, asked.size)
    }

    @Test
    fun wholeFetchIsBoundedByTheDeadline() {
        assertEquals(20_000L, WatchPriceSource.FETCH_DEADLINE_MS)
        val started = System.nanoTime()
        val result = runBlocking {
            WatchPriceSource.within(50L) { WatchPriceSource.fetch(listOf("BTC")) { awaitCancellation() } }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.isEmpty())
        assertTrue("Frist griff nicht ($millis ms)", millis < 5_000)
    }

    @Test
    fun resultWithinTheDeadlineIsKept() {
        val quote = WatchQuote("BTC", 1.0, null, WatchQuotes.BINANCE)
        val result = runBlocking { WatchPriceSource.within(5_000L) { mapOf("BTC" to quote) } }
        assertEquals(mapOf("BTC" to quote), result)
    }
}
