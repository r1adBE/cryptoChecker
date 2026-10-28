package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.FundingPoint
import com.cryptochecker.app.domain.activity.FundingTrendRules
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
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Funding-Verlauf für «Warum?» ([fundingHistory]): gleiche Ausweich-Kette wie die Kennzahlen, leere Antwort zählt
 * als Fehler, Ergebnis ~10 Min. im Speicher, Fehlschlag wird nicht gemerkt.
 */
class FuturesFundingHistoryTest {

    private val hits = ConcurrentHashMap<String, Int>()
    private val limit = FundingTrendRules.LIMIT

    private fun binance(symbol: String) = "https://fapi.binance.com/fapi/v1/fundingRate?symbol=$symbol&limit=$limit"
    private fun binanceCoinM(symbol: String) = "https://dapi.binance.com/dapi/v1/fundingRate?symbol=$symbol&limit=$limit"
    private fun bybit(symbol: String) = "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=$symbol&limit=$limit"
    private fun okx(id: String) = "https://www.okx.com/api/v5/public/funding-rate-history?instId=$id&limit=$limit"

    private fun source(replies: Map<String, String>) = FuturesDataSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits.merge(url, 1) { a, b -> a + b }
            val body = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    private fun watch(marketKey: String, pairId: String?, contract: FuturesContractType = FuturesContractType.PERPETUAL) =
        WatchEntity(marketKey = marketKey, marketName = marketKey, baseAsset = "btc", quoteAsset = "USDT", contractType = contract, pairId = pairId)

    private val spot = watch("Kraken", null, FuturesContractType.NONE)

    private fun assumeOpen(vararg hosts: String) {
        for (host in hosts) assumeFalse("$host gesperrt", BlockedSources.isBlocked(host))
    }

    // Binance: älteste zuerst, «fundingRate» als Text
    private val binanceBody = """[{"symbol":"BTCUSDT","fundingTime":1792972800000,"fundingRate":"0.00010000","markPrice":"60000"},""" +
        """{"symbol":"BTCUSDT","fundingTime":1793001600000,"fundingRate":"0.00020000","markPrice":"60100"}]"""

    @Before
    fun resetCache() {
        FuturesDataSource.historyCache.clear()
    }

    @Test
    fun spotPairTakesBinanceAndCachesTheResult() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE)
        val futures = source(mapOf(binance("BTCUSDT") to binanceBody))
        val history = futures.fundingHistory(spot)!!
        assertEquals("Binance Futures", history.source)
        assertEquals(listOf(1792972800000L, 1793001600000L), history.points.map { it.time })
        assertEquals(0.01, history.points[0].ratePercent, 1e-12)
        assertEquals(0.02, history.points[1].ratePercent, 1e-12)

        // Innerhalb von 10 Minuten aus dem Speicher
        assertEquals(history, futures.fundingHistory(spot))
        assertEquals(1, hits[binance("BTCUSDT")])
    }

    @Test
    fun emptyBinanceAnswerFallsBackToBybit() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE, FuturesDataSource.BYBIT)
        val history = source(
            mapOf(
                binance("BTCUSDT") to "[]",
                bybit("BTCUSDT") to """{"retCode":0,"retMsg":"OK","result":{"category":"linear","list":[""" +
                    """{"symbol":"BTCUSDT","fundingRate":"0.0003","fundingRateTimestamp":"1793001600000"},""" +
                    """{"symbol":"BTCUSDT","fundingRate":"0.0001","fundingRateTimestamp":"1792972800000"}]}}""",
            ),
        ).fundingHistory(spot)!!
        assertEquals("Bybit", history.source)
        // Neueste zuerst geliefert, älteste zuerst gelesen
        assertEquals(listOf(0.01, 0.03), history.points.map { Math.round(it.ratePercent * 1000) / 1000.0 })
    }

    @Test
    fun okxWatchReadsTheRealizedRateOfItsContract() = runBlocking {
        assumeOpen(FuturesDataSource.OKX)
        val id = "BTC-USD-SWAP"
        val history = source(
            mapOf(
                okx(id) to """{"code":"0","msg":"","data":[{"instId":"$id","fundingRate":"0.0002","realizedRate":"0.00015",""" +
                    """"fundingTime":"1793001600000"}]}""",
            ),
        ).fundingHistory(watch("OkexFutures", id))!!
        assertEquals("OKX", history.source)
        assertEquals(1793001600000L, history.points.single().time)
        // «realizedRate» vor «fundingRate»
        assertEquals(0.015, history.points.single().ratePercent, 1e-12)
        assertNull(hits[binance("BTCUSDT")])
    }

    @Test
    fun coinMWatchAsksDapiFirst() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE_COIN_M)
        val history = source(
            mapOf(binanceCoinM("BTCUSD_PERP") to """[{"symbol":"BTCUSD_PERP","fundingTime":1793001600000,"fundingRate":"0.00005"}]"""),
        ).fundingHistory(watch("BinanceFutures", "2:BTCUSD_PERP"))!!
        assertEquals(FuturesSources.BINANCE_COIN_M, history.source)
        assertEquals(0.005, history.points.single().ratePercent, 1e-12)
    }

    @Test
    fun bybitWatchUsesItsPairIdFirst() = runBlocking {
        assumeOpen(FuturesDataSource.BYBIT)
        val history = source(
            mapOf(
                bybit("BTCPERP") to """{"retCode":0,"retMsg":"OK","result":{"list":[""" +
                    """{"symbol":"BTCPERP","fundingRate":"0.0001","fundingRateTimestamp":"1793001600000"}]}}""",
            ),
        ).fundingHistory(watch("BybitFutures", "BTCPERP"))!!
        assertEquals("Bybit", history.source)
        assertNull(hits[binance("BTCUSDT")])
    }

    @Test
    fun withoutAnySourceItIsNullAndRetriedNextTime() = runBlocking {
        assumeOpen(FuturesDataSource.BINANCE, FuturesDataSource.BYBIT, FuturesDataSource.OKX)
        val futures = source(emptyMap())
        val usdc = watch("BinanceFutures", "BTCUSDC")
        assertNull(futures.fundingHistory(usdc))
        assertEquals(1, hits[binance("BTCUSDC")])
        assertEquals(1, hits[bybit("BTCUSDT")])
        assertEquals(1, hits[okx("BTC-USDT-SWAP")])
        // Fehlschlag nicht gemerkt
        assertNull(futures.fundingHistory(usdc))
        assertEquals(2, hits[binance("BTCUSDC")])
    }

    @Test
    fun emptyPointsAreAnError() {
        val futures = source(emptyMap())
        try {
            futures.history(emptyList(), "Test")
            fail("Fehler erwartet")
        } catch (e: IllegalArgumentException) {
            assertEquals("Kein Funding-Verlauf von Test", e.message)
        }
        val point = FundingPoint(1L, 0.01)
        assertEquals(listOf(point), futures.history(listOf(point), "Test").points)
    }
}
