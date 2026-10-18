package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.market.Binance
import com.cryptochecker.marketdata.model.market.BinanceFutures
import com.cryptochecker.marketdata.util.BulkCompleteness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regeln der Kursabfrage (Build 47): Einzelabfragen nach 429/418 stoppen ([SingleRateGate]), «vollständig» je
 * Teilabfrage ([BulkCompleteness]), inverse Perpetuals ([FuturesContractType.normalized]) und «veraltet» am Abruf,
 * am letzten Handel erst ab der Grenze der Alarmsperre (Market.readTicker, PriceFreshness.staleReason).
 * iOS: `PriceFetchRulesTests.swift`.
 */
class PriceFetchRulesTest {

    // --- SingleRateGate ---

    @Test
    fun gateBlocksRemainingSinglesAfterRateLimit() = runBlocking {
        val gate = SingleRateGate()
        var sent = 0
        val first = gate.send { sent++; Fetched(null, "HttpCode: 429 (retry-after 30 s)", fromSingle = true) }
        assertEquals("HttpCode: 429 (retry-after 30 s)", first.error)
        val second = gate.send { sent++; Fetched(SimpleTicker().apply { last = 1.0 }, null, fromSingle = true) }
        // Nicht gesendet, derselbe Fehler (samt retry-after für ExchangeBackoff), zählt nicht als Einzelabfrage
        assertEquals(1, sent)
        assertNull(second.ticker)
        assertEquals("HttpCode: 429 (retry-after 30 s)", second.error)
        assertFalse(second.fromSingle)
        assertEquals(30_000L, ExchangeBackoff.retryAfterMillis(listOf(second.error)))
    }

    @Test
    fun gateBlocksAfterBan() = runBlocking {
        val gate = SingleRateGate()
        gate.send { Fetched(null, "HttpCode: 418") }
        assertEquals("HttpCode: 418", gate.blocked)
        assertTrue(ExchangeBackoff.banned(listOf(gate.send { Fetched(null, "never") }.error)))
    }

    @Test
    fun gateIgnoresOtherErrors() = runBlocking {
        val gate = SingleRateGate()
        var sent = 0
        gate.send { sent++; Fetched(null, "HttpCode: 404") }
        gate.send { sent++; Fetched(null, "timeout") }
        gate.send { sent++; Fetched(SimpleTicker().apply { last = 2.0 }, null) }
        assertEquals(3, sent)
        assertNull(gate.blocked)
    }

    @Test
    fun gateKeepsFirstRateLimitText() {
        val gate = SingleRateGate()
        gate.note("HttpCode: 429 (retry-after 60 s)")
        gate.note("HttpCode: 418")
        assertEquals("HttpCode: 429 (retry-after 60 s)", gate.blocked)
    }

    // --- BulkCompleteness ---

    @Test
    fun alphaFailureKeepsSpotComplete() {
        val binance = Binance()
        val ids = listOf("BTCUSDT", "ETHUSDT", "ALPHA_175USDT")
        val failedGroups = setOf(binance.bulkTickersRequestGroup(1))
        val incomplete = BulkCompleteness.incomplete(ids, failedGroups, binance::bulkTickersPairGroup)
        assertEquals(setOf("ALPHA_175USDT"), incomplete)
        assertTrue(BulkCompleteness.anyComplete(ids, failedGroups, incomplete))
        // Spot gescheitert (Gruppe 0) → Spot-Paare unvollständig
        assertEquals(setOf("BTCUSDT", "ETHUSDT"), BulkCompleteness.incomplete(ids, setOf(0), binance::bulkTickersPairGroup))
        // Alle Alpha-Ticker (ab Anfrage 2) gehören zur Alpha-Gruppe
        assertEquals(binance.bulkTickersRequestGroup(1), binance.bulkTickersRequestGroup(3))
        assertEquals(0, binance.bulkTickersRequestGroup(0))
    }

    @Test
    fun singleGroupMarketsStayAllOrNothing() {
        val ids = listOf("A", "B")
        val incomplete = BulkCompleteness.incomplete(ids, setOf(0)) { 0 }
        assertEquals(setOf("A", "B"), incomplete)
        assertFalse(BulkCompleteness.anyComplete(ids, setOf(0), incomplete))
        assertTrue(BulkCompleteness.anyComplete(ids, emptySet(), emptySet()))
        assertFalse(BulkCompleteness.anyComplete(emptyList(), setOf(0), emptySet()))
    }

    @Test
    fun coinMFailureKeepsUsdMComplete() {
        val futures = BinanceFutures()
        val ids = listOf("BTCUSDT", "2:BTCUSD_PERP")
        val incomplete = BulkCompleteness.incomplete(ids, setOf(futures.bulkTickersRequestGroup(1)), futures::bulkTickersPairGroup)
        assertEquals(setOf("2:BTCUSD_PERP"), incomplete)
    }

    @Test
    fun unknownPairsFromPairList() {
        assertEquals(setOf("KRW-OLD"), BulkCompleteness.unknownPairs(listOf("KRW-BTC", "KRW-OLD"), setOf("KRW-BTC", "KRW-ETH")))
        assertNull(BulkCompleteness.unknownPairs(listOf("KRW-BTC"), setOf("KRW-BTC")))
        // Leere Paarliste sagt nichts
        assertNull(BulkCompleteness.unknownPairs(listOf("KRW-BTC"), emptySet()))
    }

    @Test
    fun bithumbFindsUnknownPairsViaPairList() {
        val bithumb = MarketsConfig.MARKETS["Bithumb"]!!
        val upbit = MarketsConfig.MARKETS["Upbit"]!!
        assertEquals("https://api.bithumb.com/v1/market/all?isDetails=false", bithumb.getBulkKnownPairsUrl(0))
        // Upbit hat die ungefilterte Liste
        assertNull(upbit.getBulkKnownPairsUrl(0))
        assertTrue(bithumb.bulkTickersComplete)
    }

    @Test
    fun krakenBulkIsComplete() {
        // Fehlendes Paar: einzeln nachfragen, «EQuery:Unknown asset pair» → nicht mehr gehandelt (BulkDelisted lernt es)
        assertTrue(MarketsConfig.MARKETS["Kraken"]!!.bulkTickersComplete)
        assertTrue(com.cryptochecker.app.domain.watch.NotTraded.confirmedBySingle(false, "EQuery:Unknown asset pair"))
        assertFalse(com.cryptochecker.app.domain.watch.NotTraded.confirmedBySingle(false, "EGeneral:Too many requests"))
    }

    // --- inverse Perpetuals ---

    @Test
    fun inversePerpetualIds() {
        val n = { market: String, id: String?, type: FuturesContractType -> FuturesContractType.normalized(market, id, type) }
        assertEquals(FuturesContractType.INVERSE_PERPETUAL, n("BinanceFutures", "2:BTCUSD_PERP", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.PERPETUAL, n("BinanceFutures", "BTCUSDT", FuturesContractType.PERPETUAL))
        // COIN-M-Laufzeit bleibt
        assertEquals(FuturesContractType.QUARTERLY, n("BinanceFutures", "2:BTCUSD_251226", FuturesContractType.QUARTERLY))
        assertEquals(FuturesContractType.INVERSE_PERPETUAL, n("OkexFutures", "BTC-USD-SWAP", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.PERPETUAL, n("OkexFutures", "BTC-USDT-SWAP", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.PERPETUAL, n("OkexFutures", "BTC-USDC-SWAP", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.INVERSE_PERPETUAL, n("Deribit", "ETH-PERPETUAL", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.PERPETUAL, n("Deribit", "SOL_USDC-PERPETUAL", FuturesContractType.PERPETUAL))
        // Andere Börsen und Spot unverändert; schon invers bleibt invers
        assertEquals(FuturesContractType.PERPETUAL, n("BybitFutures", "BTCUSD", FuturesContractType.PERPETUAL))
        assertEquals(FuturesContractType.NONE, n("Okex", "BTC-USD", FuturesContractType.NONE))
        assertEquals(FuturesContractType.INVERSE_PERPETUAL, n("Deribit", "BTC-PERPETUAL", FuturesContractType.INVERSE_PERPETUAL))
        assertEquals(FuturesContractType.PERPETUAL, n("OkexFutures", null, FuturesContractType.PERPETUAL))
    }

    @Test
    fun inverseCountsAsPerpetual() {
        assertTrue(FuturesContractType.INVERSE_PERPETUAL.isPerpetual)
        assertTrue(FuturesContractType.PERPETUAL.isPerpetual)
        assertFalse(FuturesContractType.QUARTERLY.isPerpetual)
        assertFalse(FuturesContractType.NONE.isPerpetual)
    }

    // --- «veraltet»: Abruf wie bisher, Zeit der Börse erst ab der Grenze der Alarmsperre ---

    @Test
    fun fetchTimeDrivesStalenessExchangeTimeOnlyPastAlarmLimit() {
        val upbit = MarketsConfig.MARKETS["Upbit"]!!
        val body = """[{"market":"KRW-BTC","trade_timestamp":1728540000000,"trade_price":86000000,""" +
            """"acc_trade_price_24h":1.0,"acc_trade_volume_24h":2.0}]"""
        val before = System.currentTimeMillis()
        val ticker = SimpleTicker()
        upbit.readTicker(0, body, ticker, PairQuery("BTC", "KRW", "KRW-BTC", FuturesContractType.NONE))
        val after = System.currentTimeMillis()
        // Letzter Handel nur als exchangeTime; timestamp = Abruf → lastUpdate frisch, am Abruf nicht veraltet
        assertEquals(1_728_540_000_000L, ticker.exchangeTime)
        assertTrue(ticker.timestamp in before..after)
        assertFalse(OutdatedRule.isOutdated(ticker.timestamp, after, OutdatedRule.MIN_MILLIS))
        // Handel länger als die Alarmsperre (2 h) her: Zeile «veraltet» aus demselben Grund wie die Sperre
        assertEquals(
            PriceFreshness.StaleReason.TRADE,
            PriceFreshness.staleReason(ticker.timestamp, ticker.exchangeTime, after, OutdatedRule.MIN_MILLIS),
        )
        // Ruhiges Paar (letzter Handel vor 1 h): frischer Abruf zählt, nicht veraltet
        assertNull(PriceFreshness.staleReason(ticker.timestamp, after - 3_600_000L, after, OutdatedRule.MIN_MILLIS))

        val bulk = upbit.parseBulkTickersDetailed(0, body).tickers["KRW-BTC"]
        assertNotNull(bulk)
        assertEquals(1_728_540_000_000L, bulk!!.exchangeTime)
        assertTrue(bulk.timestamp >= before)
    }
}
