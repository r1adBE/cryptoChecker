package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Futures-Kennzahlen ([FuturesDataSource]) mit einem Interceptor als Börsen: Binance USDⓈ-M zuerst (Open Interest
 * aus Kontrakten × Mark-Preis, Intervall aus fundingInfo mit Zwischenspeicher), sonst Bybit, zuletzt OKX; eigene
 * Quelle der beobachteten Börse; gesperrte Hosts übersprungen ([FuturesDataSource.firstOf]).
 */
class FuturesDataSourceTest {

    private val hits = ConcurrentHashMap<String, Int>()

    private fun premium(symbol: String) = "https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol"
    private fun openInterest(symbol: String) = "https://fapi.binance.com/fapi/v1/openInterest?symbol=$symbol"
    private val fundingInfo = "https://fapi.binance.com/fapi/v1/fundingInfo"
    private fun bybit(symbol: String) = "https://api.bybit.com/v5/market/tickers?category=linear&symbol=$symbol"
    private fun okxFunding(id: String) = "https://www.okx.com/api/v5/public/funding-rate?instId=$id"
    private fun okxOpenInterest(id: String) = "https://www.okx.com/api/v5/public/open-interest?instType=SWAP&instId=$id"
    private fun okxMark(id: String) = "https://www.okx.com/api/v5/public/mark-price?instType=SWAP&instId=$id"
    private fun deribit(instrument: String) = "https://www.deribit.com/api/v2/public/ticker?instrument_name=$instrument"

    /** Antworten je Adresse (Status, Text); fehlende Adresse = kein Netz. */
    private fun source(replies: Map<String, Pair<Int, String>>) = FuturesDataSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits.merge(url, 1) { a, b -> a + b }
            val (code, body) = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("status $code")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    private fun ok(body: String) = 200 to body

    private fun watch(exchangeKey: String, base: String, pairId: String?, contract: FuturesContractType = FuturesContractType.PERPETUAL) =
        WatchEntity(exchangeKey = exchangeKey, exchangeName = exchangeKey, baseAsset = base, quoteAsset = "USDT", contractType = contract, pairId = pairId)

    /** Ein Test, der eine Quelle braucht, läuft nur, wenn kein anderer Test im selben Prozess sie gesperrt hat. */
    private fun assumeOpen(vararg hosts: String) {
        for (host in hosts) assumeFalse("$host gesperrt", BlockedSources.isBlocked(host))
    }

    private fun binancePremium(symbol: String, mark: String, rate: String) =
        ok("""{"symbol":"$symbol","markPrice":"$mark","indexPrice":"$mark","lastFundingRate":"$rate","nextFundingTime":1793000000000,"time":1792990000000}""")

    private val bybitBtc = ok(
        """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":[{"symbol":"BTCUSDT","fundingRate":"0.0002",""" +
            """"openInterestValue":"1500000000","nextFundingTime":"1793000000000","fundingIntervalHour":"4"}]}}""",
    )

    @Before
    fun resetCaches() {
        FuturesDataSource.binanceIntervalCache = null
        FuturesDataSource.binanceCoinMSizeCache = null
        FuturesDataSource.historyCache.clear()
    }

    @Test
    fun spotPairHasNoOwnFuturesData() = runBlocking {
        assertNull(source(emptyMap()).fetch(watch("Binance", "BTC", null, FuturesContractType.NONE)))
        assertTrue("keine Anfrage: $hits", hits.isEmpty())
    }

    @Test
    fun binanceFirstWithOpenInterestAndCachedIntervals() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE)
        val futures = source(
            mapOf(
                premium("BTCUSDT") to binancePremium("BTCUSDT", "60000.0", "0.00010000"),
                openInterest("BTCUSDT") to ok("""{"symbol":"BTCUSDT","openInterest":"100.5","time":1792990000000}"""),
                premium("ETHUSDT") to binancePremium("ETHUSDT", "2500.0", "-0.00005000"),
                fundingInfo to ok("""[{"symbol":"BTCUSDT","adjustedFundingRateCap":"0.02","fundingIntervalHours":4}]"""),
            ),
        )
        val btc = futures.fetchForBase(" btc ")
        assertEquals("Binance Futures", btc.source)
        assertEquals(0.01, btc.fundingRatePercent!!, 1e-12)
        assertEquals(1793000000000L, btc.nextFundingTime)
        assertEquals(100.5 * 60000.0, btc.openInterestUsd!!, 1e-6)
        assertEquals(4, btc.fundingIntervalHours)

        // Zweiter Coin: Intervall-Liste aus dem Speicher; nicht gelistet = 8 Std.; ohne Open Interest bleibt es leer
        val eth = futures.fetchForBase("ETH")
        assertEquals(-0.005, eth.fundingRatePercent!!, 1e-12)
        assertNull(eth.openInterestUsd)
        assertEquals(8, eth.fundingIntervalHours)
        assertEquals(1, hits[fundingInfo])
        assertNull(hits[bybit("BTCUSDT")])
    }

    @Test
    fun bybitWhenBinanceAnswersWithAnError() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE, FuturesDataSource.BYBIT)
        val info = source(mapOf(premium("BTCUSDT") to (500 to "Internal Server Error"), bybit("BTCUSDT") to bybitBtc))
            .fetchForBase("BTC")
        assertEquals("Bybit", info.source)
        assertEquals(0.02, info.fundingRatePercent!!, 1e-12)
        assertEquals(1.5e9, info.openInterestUsd!!, 0.0)
        assertEquals(1793000000000L, info.nextFundingTime)
        assertEquals(4, info.fundingIntervalHours)
        // Ein Serverfehler sperrt Binance nicht
        assertTrue(!BlockedSources.isBlocked(FuturesDataSource.BINANCE))
    }

    @Test
    fun okxLastWithOpenInterestFromMarkPrice() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE, FuturesDataSource.BYBIT, FuturesDataSource.OKX)
        val id = "SOL-USDT-SWAP"
        val info = source(
            mapOf(
                // Bybit ohne Funding und Open Interest zählt als Fehler
                bybit("SOLUSDT") to ok("""{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"SOLUSDT"}]}}"""),
                okxFunding(id) to ok(
                    """{"code":"0","msg":"","data":[{"instId":"$id","fundingRate":"-0.0001",""" +
                        """"fundingTime":"1793000000000","nextFundingTime":"1793028800000"}]}""",
                ),
                okxOpenInterest(id) to ok("""{"code":"0","msg":"","data":[{"instId":"$id","oi":"10","oiCcy":"1000"}]}"""),
                okxMark(id) to ok("""{"code":"0","msg":"","data":[{"instId":"$id","markPx":"150"}]}"""),
            ),
        ).fetchForBase("SOL")
        assertEquals("OKX", info.source)
        assertEquals(-0.01, info.fundingRatePercent!!, 1e-12)
        assertEquals(1793000000000L, info.nextFundingTime)
        // 1000 Coins × 150 USD
        assertEquals(150_000.0, info.openInterestUsd!!, 1e-6)
        // Abstand nächste ↔ übernächste Abrechnung: 8 Std.
        assertEquals(8, info.fundingIntervalHours)
        assertEquals(1, hits[premium("SOLUSDT")])
        assertEquals(1, hits[bybit("SOLUSDT")])
    }

    @Test
    fun allSourcesFailingThrows() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE, FuturesDataSource.BYBIT, FuturesDataSource.OKX)
        try {
            source(mapOf(okxFunding("XRP-USDT-SWAP") to ok("""{"code":"51001","msg":"Instrument ID does not exist","data":[]}""")))
                .fetchForBase("XRP")
            fail("Fehler erwartet")
        } catch (e: IllegalArgumentException) {
            // Letzter Fehler der Kette: OKX meldet einen Fehlercode
            assertTrue(e.message!!.contains("Instrument ID does not exist"))
        }
        assertEquals(1, hits[premium("XRPUSDT")])
        assertEquals(1, hits[bybit("XRPUSDT")])
    }

    @Test
    fun bybitWatchAsksBybitFirst() = runBlocking {
        assumeOpen(FuturesDataSource.BYBIT)
        val info = source(mapOf(bybit("BTCUSDT") to bybitBtc)).fetch(watch("BybitFutures", "BTC", "BTCUSDT"))!!
        assertEquals("Bybit", info.source)
        assertTrue("Binance nicht gefragt: $hits", hits.keys.none { it.contains("fapi.binance.com") })
    }

    @Test
    fun okxWatchUsesItsOwnContract() = runBlocking {
        assumeOpen(FuturesDataSource.OKX)
        val id = "BTC-USD-SWAP"
        val info = source(
            mapOf(
                okxFunding(id) to ok(
                    """{"code":"0","msg":"","data":[{"fundingRate":"0.0001","fundingTime":"1793000000000","nextFundingTime":"1793014400000"}]}""",
                ),
                okxOpenInterest(id) to ok("""{"code":"0","msg":"","data":[{"oiUsd":"5000000","oiCcy":"80"}]}"""),
            ),
        ).fetch(watch("OkexFutures", "BTC", id))!!
        assertEquals("OKX", info.source)
        assertEquals(5_000_000.0, info.openInterestUsd!!, 0.0)
        assertEquals(4, info.fundingIntervalHours)
        assertNull("oiUsd vorhanden: kein Mark-Preis nötig", hits[okxMark(id)])
    }

    @Test
    fun binanceWatchKeepsItsUsdcContract() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE)
        val info = source(mapOf(premium("ETHUSDC") to binancePremium("ETHUSDC", "2500.0", "0.00010000")))
            .fetch(watch("BinanceFutures", "ETH", "ETHUSDC"))!!
        assertEquals("Binance Futures", info.source)
        assertNull(hits[premium("ETHUSDT")])
        // Intervall-Liste nicht ladbar und nichts im Speicher: unbekannt statt still 8 Std.
        assertNull(info.fundingIntervalHours)
    }

    @Test
    fun deribitOwnSourceThenBinanceAsReference() = runBlocking {
        assumeOpen(FuturesDataSource.DERIBIT, FuturesDataSource.BINANCE)
        val deribitBody = ok(
            """{"jsonrpc":"2.0","result":{"instrument_name":"BTC-PERPETUAL","open_interest":950000000,"mark_price":60000,"funding_8h":0.00012}}""",
        )
        val own = source(mapOf(deribit("BTC-PERPETUAL") to deribitBody)).fetch(watch("Deribit", "BTC", "BTC-PERPETUAL"))!!
        assertEquals("Deribit", own.source)
        assertEquals(0.012, own.fundingRatePercent!!, 1e-12)
        // Inverser Kontrakt: Open Interest schon in USD
        assertEquals(9.5e8, own.openInterestUsd!!, 0.0)

        // Eigene Quelle mit Serverfehler: Binance als Richtwert
        val reference = source(
            mapOf(deribit("BTC-PERPETUAL") to (502 to "Bad Gateway"), premium("BTCUSDT") to binancePremium("BTCUSDT", "60000", "0.0001")),
        ).fetch(watch("Deribit", "BTC", "BTC-PERPETUAL"))!!
        assertEquals("Binance Futures", reference.source)
    }

    @Test
    fun exchangeWithoutOwnSourceUsesTheChain() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE)
        val info = source(mapOf(premium("BTCUSDT") to binancePremium("BTCUSDT", "60000", "0.0001")))
            .fetch(watch("PhemexFutures", "btc", null))!!
        assertEquals("Binance Futures", info.source)
    }

    @Test
    fun outdatedIntervalListStillCountsWhenReloadFails() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE)
        val replies = mapOf(premium("BTCUSDT") to binancePremium("BTCUSDT", "60000", "0.0001"))
        val now = System.currentTimeMillis()
        // Älter als 6 Std., jünger als 7 Tage: Neuladen scheitert → alte Liste gilt weiter
        FuturesDataSource.binanceIntervalCache = (now - FuturesDataSource.INTERVAL_CACHE_MILLIS - 60_000L) to mapOf("BTCUSDT" to 1)
        assertEquals(1, source(replies).fetchForBase("BTC").fundingIntervalHours)
        assertEquals(1, hits[fundingInfo])

        // Älter als 7 Tage: unbekannt
        FuturesDataSource.binanceIntervalCache = (now - FuturesDataSource.INTERVAL_STALE_MILLIS - 60_000L) to mapOf("BTCUSDT" to 1)
        assertNull(source(replies).fetchForBase("BTC").fundingIntervalHours)
    }

    @Test
    fun firstOfSkipsBlockedHosts() = runBlocking {
        val futures = source(emptyMap())
        val blocked = "futures-test-blocked.example"
        assertTrue(BlockedSources.noteFailure(blocked, HttpExchangeError(451, "unavailable")))
        var askedBlocked = false
        val result = futures.firstOf(
            futures.source<String>(blocked) { askedBlocked = true; "gesperrt" },
            futures.source<String>("futures-test-failing.example") { throw IOException("kaputt") },
            futures.source<String>("futures-test-open.example") { "offen" },
        )
        assertEquals("offen", result)
        assertTrue(!askedBlocked)
        try {
            futures.firstOf(futures.source<String>(blocked) { "gesperrt" })
            fail("Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertEquals("Alle Futures-Quellen gesperrt", e.message)
        }
    }
}
