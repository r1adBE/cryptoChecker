package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.domain.market.DataFreshness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ausweich-Kette der Kerzen ([CandleDataSource.candlesSourced]) über einen Interceptor: Reihenfolge Binance-Spiegel,
 * Spot, USDⓈ-M, Binance.US, Coinbase; nur laufende Reihen; Prüfung des Aufrufers; gemerkte Quelle; Geo-Sperre;
 * Namenssperre; Coinbase mit 4-Stunden- und Wochen-Zusammenfassung und älterer Seite.
 */
class CandleDataSourceChainTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.releaseChain()

    private fun source(http: RouteHttp) = CandleDataSource(http.client, testTokenIdentityStore(temp.newFolder()))

    private val notFound = Reply("""{"code":-1121,"msg":"Invalid symbol."}""", 404)

    @Test
    fun mirrorFirst() = runBlocking {
        val http = RouteHttp { url ->
            if (url.startsWith("https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=5")) {
                Reply(Klines.binance(5) { 100.0 + it })
            } else {
                null
            }
        }
        // «USD» heisst bei Binance «USDT»; Kleinschreibung und Leerzeichen egal
        val sourced = source(http).candlesSourced(" btc ", "usd", CandleInterval.D1, 5)!!
        assertEquals(DataFreshness.BINANCE, sourced.provider)
        assertEquals(listOf(100.0, 101.0, 102.0, 103.0, 104.0), sourced.value.map { it.close })
        assertEquals(1040.0, sourced.value.last().quoteVolume!!, 1e-9)
        assertEquals(1, http.requested.size)
    }

    @Test
    fun workingSourceIsAskedFirstNextTime() = runBlocking {
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://data-api.binance.vision/") -> notFound
                url.startsWith("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=4h") ->
                    Reply(Klines.binance(3, step = 4 * Klines.HOUR, last = System.currentTimeMillis() / Klines.HOUR * Klines.HOUR) { 9.0 })
                else -> null
            }
        }
        val candles = source(http)
        assertEquals(3, candles.candles("BTC", "USDT", CandleInterval.H4, 3)!!.size)
        assertEquals(2, http.requested.size)
        // Spot zuerst: der Spiegel wird übersprungen
        assertEquals(3, candles.candles("BTC", "USDT", CandleInterval.H4, 3)!!.size)
        assertEquals(3, http.requested.size)
        assertTrue(http.requested.last().startsWith("https://api.binance.com/"))
    }

    @Test
    fun invalidAssetsNeedNoRequest() = runBlocking {
        val http = RouteHttp { null }
        val candles = source(http)
        assertNull(candles.candles("BT C", "USDT", CandleInterval.H1, 10))
        assertNull(candles.candles("BTC", "", CandleInterval.H1, 10))
        assertNull(candles.candles("BTC/", "USDT", CandleInterval.H1, 10))
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun endedSeriesFallsThroughToCoinbase() = runBlocking {
        val today = Klines.today()
        val http = RouteHttp { url ->
            when {
                // Spiegel: Reihe endet vor 30 Tagen (Delisting) → nächste Quelle
                url.startsWith("https://data-api.binance.vision/") ->
                    Reply(Klines.binance(10, last = today - 30 * Klines.DAY) { 50.0 })
                url.startsWith("https://api.binance.com/") -> notFound
                url.startsWith("https://api.binance.us/") -> notFound
                url.startsWith("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400") ->
                    Reply(Klines.coinbase(6, Klines.DAY, today) { 200.0 + it })
                else -> null
            }
        }
        val sourced = source(http).candlesSourced("BTC", "USDT", CandleInterval.D1, 6)!!
        assertEquals(DataFreshness.COINBASE, sourced.provider)
        // Coinbase liefert neueste zuerst → aufsteigend
        assertEquals(listOf(200.0, 201.0, 202.0, 203.0, 204.0, 205.0), sourced.value.map { it.close })
        assertNull(sourced.value.first().quoteVolume)
        // Binance.US: erst USDT, dann USD
        assertEquals(1, http.count("api.binance.us/api/v3/klines?symbol=BTCUSDT&"))
        assertEquals(1, http.count("api.binance.us/api/v3/klines?symbol=BTCUSD&"))
        assertEquals(1, http.count("fapi.binance.com"))
    }

    @Test
    fun futuresPairsAskUsdmFirst() = runBlocking {
        val http = RouteHttp { url ->
            if (url.startsWith("https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT&interval=1h")) {
                Reply(Klines.binance(3, step = Klines.HOUR, last = System.currentTimeMillis() / Klines.HOUR * Klines.HOUR) { 3000.0 })
            } else {
                null
            }
        }
        val sourced = source(http).candlesSourced("ETH", "USDT", CandleInterval.H1, 3, preferFutures = true)!!
        assertEquals(DataFreshness.BINANCE_FUTURES, sourced.provider)
        assertTrue(http.requested.first().startsWith("https://fapi.binance.com/"))
        assertEquals(1, http.requested.size)
    }

    @Test
    fun callerCheckRejectsTheFirstSeries() = runBlocking {
        val http = RouteHttp { url ->
            when {
                // Fremder Token gleichen Kürzels beim Spiegel (Kurs 10), der richtige bei Spot
                url.startsWith("https://data-api.binance.vision/") -> Reply(Klines.binance(4) { 10.0 })
                url.startsWith("https://api.binance.com/") -> Reply(Klines.binance(4) { 100.0 })
                else -> null
            }
        }
        val sourced = source(http).candlesSourced("ABC", "USDT", CandleInterval.D1, 4, accept = { it.last().close > 50.0 })!!
        assertEquals(100.0, sourced.value.last().close, 0.0)
        assertEquals(2, http.requested.size)

        // Keine Quelle passt: null, und keine Quelle wird gemerkt
        val none = RouteHttp { Reply(Klines.binance(4) { 10.0 }) }
        assertNull(source(none).candlesSourced("ABC", "USDT", CandleInterval.D1, 4, accept = { false }))
        assertTrue("alle Quellen gefragt", none.count("api.exchange.coinbase.com") >= 1)
    }

    @Test
    fun geoBlockedSourceIsSkipped() = runBlocking {
        val mirror = "data-api.binance.vision"
        try {
            val http = RouteHttp { url ->
                when {
                    url.startsWith("https://$mirror/") -> Reply("""{"code":0,"msg":"restricted location"}""", 451)
                    url.startsWith("https://api.binance.com/") -> Reply(Klines.binance(3) { 7.0 })
                    else -> null
                }
            }
            val candles = source(http)
            assertEquals(DataFreshness.BINANCE, candles.candlesSourced("SOL", "USDT", CandleInterval.D1, 3)!!.provider)
            assertTrue(BlockedSources.isBlocked(mirror))
            // Anderes Paar: der gesperrte Spiegel wird gar nicht mehr gefragt
            candles.candlesSourced("XRP", "USDT", CandleInterval.D1, 3)
            assertEquals(1, http.count(mirror))
        } finally {
            BlockedSources.unblock(mirror)
        }
    }

    @Test
    fun conflictingNameSkipsTheWholeChain() = runBlocking {
        val http = RouteHttp { Reply(Klines.binance(3) { 1.0 }) }
        val store = testTokenIdentityStore(temp.newFolder())
        store.saveBinance(mapOf("CAT" to "Catcoin"))
        val candles = CandleDataSource(http.client, store)
        assertNull(candles.candles("CAT", "USDT", CandleInterval.D1, 3, identityName = "Simon's Cat"))
        assertTrue(http.requested.isEmpty())
        // Gleicher Token: Kette wie sonst
        assertEquals(3, candles.candles("CAT", "USDT", CandleInterval.D1, 3, identityName = "Catcoin")!!.size)
    }

    @Test
    fun coinbaseFourHourCandlesAreCombined() = runBlocking {
        val bucket = 4 * Klines.HOUR
        val now = System.currentTimeMillis()
        val current = now - now % bucket
        // Zwei volle 4-Stunden-Blöcke und die erste Stunde des laufenden
        val http = RouteHttp { url ->
            if (url.startsWith("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=3600")) {
                Reply(Klines.coinbase(9, Klines.HOUR, current) { 100.0 + it })
            } else {
                null
            }
        }
        val candles = source(http).candles("BTC", "USD", CandleInterval.H4, 10)!!
        assertEquals(listOf(current - 2 * bucket, current - bucket, current), candles.map { it.openTime })
        assertEquals(100.0, candles[0].open, 0.0)
        assertEquals(103.0, candles[0].close, 0.0)
        assertEquals(103.0 * 1.01, candles[0].high, 1e-9)
        assertEquals(100.0 * 0.99, candles[0].low, 1e-9)
        assertEquals(8.0, candles[0].volume, 1e-9)
        assertEquals(108.0, candles[2].close, 0.0)
    }

    @Test
    fun coinbaseWeeksStartOnMonday() = runBlocking {
        val today = Klines.today()
        val monday = 4 * Klines.DAY
        val thisWeek = Math.floorDiv(today - monday, Klines.WEEK) * Klines.WEEK + monday
        // Ab einem Tag vor dem Beginn der vorletzten vollen Woche bis heute
        val days = ((today - thisWeek) / Klines.DAY).toInt() + 16
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://api.exchange.coinbase.com/products/ETH-USD/") -> null
                url.startsWith("https://api.exchange.coinbase.com/products/ETH-USDC/candles?granularity=86400") ->
                    Reply(Klines.coinbase(days, Klines.DAY, today) { 1.0 + it })
                else -> null
            }
        }
        val weeks = source(http).candles("ETH", "USDT", CandleInterval.W1, 10)!!
        // Der angeschnittene erste Block (ein Tag) fällt weg; zwei volle Wochen und die laufende bleiben
        assertEquals(listOf(thisWeek - 2 * Klines.WEEK, thisWeek - Klines.WEEK, thisWeek), weeks.map { it.openTime })
        assertEquals(14.0, weeks[0].volume, 1e-9)
    }

    @Test
    fun coinbaseLoadsAnOlderPageForLongSeries() = runBlocking {
        val today = Klines.today()
        val firstOfPage = today - 299 * Klines.DAY
        val http = RouteHttp { url ->
            when {
                !url.startsWith("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400") -> null
                "&start=" in url -> Reply(Klines.coinbase(100, Klines.DAY, firstOfPage - Klines.DAY) { 1.0 })
                else -> Reply(Klines.coinbase(300, Klines.DAY, today) { 2.0 })
            }
        }
        val candles = source(http).candles("BTC", "USDT", CandleInterval.D1, 400)!!
        assertEquals(400, candles.size)
        assertEquals(1.0, candles.first().close, 0.0)
        assertEquals(2.0, candles.last().close, 0.0)
        assertEquals(firstOfPage - 100 * Klines.DAY, candles.first().openTime)
        assertEquals(1, http.count("&start="))
    }
}
