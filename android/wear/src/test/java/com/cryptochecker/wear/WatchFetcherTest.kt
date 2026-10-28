package com.cryptochecker.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Abrufe der Uhr-App ([WatchFetcher]): ein langsamer erster Abruf überschreibt den schnelleren zweiten nicht, ein
 * veraltetes Ergebnis fällt weg, ein Teil-Ausfall behält je Coin den letzten Kurs samt Zeit, und ein früher gestarteter
 * Abruf der Komplikation ersetzt keinen Kurs eines später gestarteten ([WatchCache]).
 */
class WatchFetcherTest {

    private fun q(coin: String, price: Double) = WatchQuote(coin, price, null, WatchQuotes.BINANCE)

    /** Gestartete Abrufe laufen lassen, bis keiner mehr lädt (höchstens [max] Durchgänge). */
    private suspend fun settle(fetcher: WatchFetcher, max: Int = 100) {
        repeat(max) {
            if (!fetcher.loading.value) return
            yield()
        }
    }

    @Test
    fun slowFirstFetchDoesNotOverwriteTheFastSecond() {
        runBlocking {
            var shown = listOf("BTC", "ETH")
            val slow = CompletableDeferred<Unit>()
            val asked = ArrayList<List<String>>()
            val saved = ArrayList<Map<String, WatchQuote>>()
            val fetcher = WatchFetcher(this, shown = { shown }, fetch = { coins ->
                asked += coins
                if (coins == listOf("BTC", "ETH")) {
                    slow.await()
                    mapOf("BTC" to q("BTC", 1.0), "ETH" to q("ETH", 2.0))
                } else {
                    mapOf("SOL" to q("SOL", 3.0))
                }
            }, save = { quotes, _ -> saved += quotes })

            fetcher.refresh()
            yield()
            // Neue Liste vom Handy, während der erste Abruf noch wartet
            shown = listOf("SOL")
            fetcher.refresh()
            settle(fetcher)
            slow.complete(Unit)
            yield()

            assertEquals(listOf(listOf("BTC", "ETH"), listOf("SOL")), asked)
            assertEquals(listOf(mapOf("SOL" to q("SOL", 3.0))), saved)
            assertFalse(fetcher.loading.value)
            assertFalse(fetcher.failed.value)
        }
    }

    @Test
    fun staleResultIsDroppedEvenIfTheFetchIgnoresCancellation() {
        runBlocking {
            val shown = listOf("BTC")
            val slow = CompletableDeferred<Unit>()
            var calls = 0
            val saved = ArrayList<Map<String, WatchQuote>>()
            val fetcher = WatchFetcher(this, shown = { shown }, fetch = {
                calls++
                if (calls == 1) {
                    // Blockierende Abfrage, die den Abbruch erst danach bemerkt
                    withContext(NonCancellable) { slow.await() }
                    mapOf("BTC" to q("BTC", 1.0))
                } else {
                    mapOf("BTC" to q("BTC", 2.0))
                }
            }, save = { quotes, _ -> saved += quotes })

            fetcher.refresh()
            yield()
            fetcher.refresh()
            settle(fetcher)
            slow.complete(Unit)
            repeat(5) { yield() }

            assertEquals(listOf(mapOf("BTC" to q("BTC", 2.0))), saved)
        }
    }

    @Test
    fun nothingDeliveredKeepsTheCacheAndReportsFailure() {
        runBlocking {
            var saves = 0
            val fetcher = WatchFetcher(this, shown = { listOf("BTC") }, fetch = { emptyMap() }, save = { _, _ -> saves++ })
            fetcher.refresh()
            settle(fetcher)
            assertEquals(0, saves)
            assertTrue(fetcher.failed.value)
        }
    }

    @Test
    fun partialTimeoutKeepsTheLastPricePerCoin() {
        runBlocking {
            val coins = listOf("BTC", "SOL")
            var cache = emptyMap<String, WatchCached>()
            var time = 1_000L
            var solTimesOut = false
            val fetcher = WatchFetcher(this, shown = { coins }, fetch = { wanted ->
                WatchPriceSource.fetch(wanted) { url ->
                    when {
                        "binance" in url && solTimesOut ->
                            """[{"symbol":"BTCUSDT","lastPrice":"67000","priceChangePercent":"1"}]"""
                        "binance" in url -> """[{"symbol":"BTCUSDT","lastPrice":"66000","priceChangePercent":"1"},
                            {"symbol":"SOLUSDT","lastPrice":"150","priceChangePercent":"2"}]"""
                        else -> throw SocketTimeoutException("timeout")
                    }
                }
            }, save = { fresh, startedAt -> cache = WatchCache.merge(cache, fresh, startedAt, coins) }, now = { time })

            fetcher.refresh()
            settle(fetcher)
            assertEquals(150.0, cache.getValue("SOL").quote.price, 0.0)

            // Zweiter Abruf: SOL läuft bei allen Börsen in die Zeitüberschreitung
            solTimesOut = true
            time = 2_000L
            fetcher.refresh()
            settle(fetcher)

            assertEquals(67000.0, cache.getValue("BTC").quote.price, 0.0)
            assertEquals(2_000L, cache.getValue("BTC").time)
            assertEquals(150.0, cache.getValue("SOL").quote.price, 0.0)
            assertEquals(1_000L, cache.getValue("SOL").time)
            // Stand der Liste = ältester Kurs, kein gemeinsamer «aktualisiert»-Stempel
            assertEquals(1_000L, WatchCache.listTime(cache, coins))
            assertFalse(fetcher.failed.value)
        }
    }

    @Test
    fun saveGetsTheStartOfTheFetchNotTheSaveTime() {
        runBlocking {
            var clock = 1_000L
            val slow = CompletableDeferred<Unit>()
            val starts = ArrayList<Long>()
            val fetcher = WatchFetcher(this, shown = { listOf("BTC") }, fetch = {
                slow.await()
                mapOf("BTC" to q("BTC", 1.0))
            }, save = { _, startedAt -> starts += startedAt }, now = { clock })

            fetcher.refresh()
            yield()
            clock = 5_000L
            slow.complete(Unit)
            settle(fetcher)

            assertEquals(listOf(1_000L), starts)
        }
    }

    @Test
    fun staleComplicationFetchDoesNotOverwriteTheNewerAppFetch() {
        val keep = listOf("BTC", "ETH")
        // Komplikation startet um 1000, die Uhr-App um 2000 und ist zuerst fertig
        var cache = WatchCache.merge(emptyMap(), mapOf("BTC" to q("BTC", 2.0)), 2_000L, keep)
        cache = WatchCache.merge(cache, mapOf("BTC" to q("BTC", 1.0), "ETH" to q("ETH", 3.0)), 1_000L, keep)

        assertEquals(WatchCached(q("BTC", 2.0), 2_000L), cache["BTC"])
        // ETH hatte keinen neueren Stand: der Kurs der Komplikation gilt, mit ihrem Start als Zeit
        assertEquals(WatchCached(q("ETH", 3.0), 1_000L), cache["ETH"])
    }
}
