package com.cryptochecker.app.parity

import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.candles.CandleHttp
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.ExchangeCandleRegistry
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.app.settings.scoped
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.Binance
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import com.cryptochecker.app.domain.logos.alphaNames
import com.cryptochecker.app.domain.logos.alphaSymbols
import com.cryptochecker.app.domain.logos.alphaTokens
import com.cryptochecker.app.domain.logos.parseAlpha

/**
 * Binance Alpha (`testdata/parity/binance_alpha.json`): Paarliste Spot + Alpha, Kurse aus der Token-Liste
 * und vom Ticker, Kerzen, Einordnung «α» auf anderen Börsen und Filter «inkl. Alpha-Tokens».
 * Gegenstück: `Tests/BinanceAlphaParityTests.swift`.
 */
class BinanceAlphaParityTest {

    private val data = ParityJson.load("binance_alpha.json")
    private val entries = CoinLogos.parseAlpha(data["tokenList"] as String)!!
    private val tokens = CoinLogos.alphaTokens(entries)
    private val alphaSymbols = CoinLogos.alphaSymbols(entries)
    private val alphaNames = CoinLogos.alphaNames(tokens)

    private fun near(label: String, expected: Double, actual: Double) {
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual) <= max(abs(expected) * 1e-9, 1e-12))
    }

    private fun triple(raw: Any?): MarketPair {
        val l = raw.list()
        return MarketPair(l[0] as String, l[1] as String, l[2] as String?)
    }

    @Test
    fun pairListMergesSpotAndAlpha() {
        val spot = data["spotPairs"].list().map { triple(it) }
        val alpha = BinanceAlpha.pairs(tokens, spot)
        val expected = data["pairs"].list().map { it.list() }
        assertEquals(expected, alpha.sortedBy { it.base }.map { listOf(it.base, it.quote, it.pairId) })
        // Spot-Paare bleiben normal (kein «α»), Alpha-Paare tragen die Kennung
        assertTrue(alpha.all { BinanceAlpha.isPairId(it.pairId) && it.quote == BinanceAlpha.QUOTE })
        assertTrue(spot.none { BinanceAlpha.isPairId(it.pairId) })
        // Zweite Zusammenführung mit schon enthaltenen Alpha-Paaren: keine Doppel
        assertEquals(alpha.map { it.pairId }, BinanceAlpha.pairs(tokens, spot + alpha).map { it.pairId })
        assertEquals(data["alphaSymbols"].list(), alphaSymbols.sorted())
    }

    @Test
    fun ids() {
        assertTrue(BinanceAlpha.isPairId("ALPHA_373USDT"))
        assertFalse(BinanceAlpha.isPairId("ALPHA_USDT"))
        assertFalse(BinanceAlpha.isPairId("ALPHA_373"))
        assertFalse(BinanceAlpha.isPairId("FROGGIEUSDT"))
        assertFalse(BinanceAlpha.isPairId("ALPHA_1234567890USDT"))
        assertEquals("ALPHA_175USDT", BinanceAlpha.pairId(" alpha_175 "))
        assertNull(BinanceAlpha.pairId(""))
        assertTrue(BinanceAlpha.isAlphaPair("Binance", "ALPHA_1USDT"))
        assertFalse(BinanceAlpha.isAlphaPair("BinanceUs", "ALPHA_1USDT"))
        // Kennung bleibt beim Speichern der Listen erhalten
        assertEquals("ALPHA_373", entries.first { it.symbol == "FROGGIE" }.alphaId)
    }

    @Test
    fun bulkTickersFromTokenList() {
        val market = Binance()
        val bulk = data["bulk"].obj()
        for (raw in bulk["cases"].list()) {
            val c = raw.obj()
            val ids = c["ids"].list().map { it as String }
            val count = market.bulkTickersRequestCount(ids)
            assertEquals("count $ids", c["count"].num()!!.toInt(), count)
            assertEquals("urls $ids", c["urls"].list(), (0 until count).map { market.getBulkTickersUrl(it, ids) })
        }
        // Ab Anfrage 1 keine ungefilterte Ersatz-Anfrage (ein gescheiterter Ticker holt nicht die ganze Liste)
        assertNull(market.getBulkTickersUrl(1))
        assertNull(market.getBulkTickersUrl(2))
        val parsed = market.parseBulkTickersDetailed(1, data["tokenList"] as String)
        val expected = bulk["tickers"].obj()
        assertEquals(expected.keys, parsed.tickers.keys)
        for ((id, rawExpected) in expected) {
            val e = rawExpected.obj()
            val t = parsed.tickers.getValue(id)
            near("$id last", e["last"].num()!!, t.last)
            e["high"].num()?.let { near("$id high", it, t.high) } ?: assertEquals(Ticker.MISSING, t.high, 0.0)
            e["low"].num()?.let { near("$id low", it, t.low) } ?: assertEquals(Ticker.MISSING, t.low, 0.0)
            near("$id volQuote", e["volQuote"].num()!!, t.volQuote)
            near("$id change", e["change"].num()!!, t.change24hPercent ?: Double.NaN)
            // Keine Handelszeit in der Liste: Zeit der Börse unbekannt
            assertNull("$id exchangeTime", t.exchangeTime)
        }
        assertEquals(bulk["failed"].list().toSet(), parsed.failed)
    }

    @Test
    fun bulkSwitchOver() {
        // Bis MAX_SINGLE_TICKERS Alpha-Paare je Paar ein Ticker, darüber einmal die Token-Liste
        val ids = (1..BinanceAlpha.MAX_SINGLE_TICKERS + 1).map { "ALPHA_${it}USDT" }
        assertEquals(emptyList<String?>(), BinanceAlpha.bulkRequestUrls(listOf("BTCUSDT")))
        val ten = BinanceAlpha.bulkRequestUrls(ids.take(BinanceAlpha.MAX_SINGLE_TICKERS) + "BTCUSDT")
        assertEquals(BinanceAlpha.MAX_SINGLE_TICKERS + 1, ten.size)
        assertNull(ten[0])
        assertTrue(ten.drop(1).all { it!!.startsWith(BinanceAlpha.TICKER_URL) })
        assertEquals(listOf<String?>(BinanceAlpha.TOKEN_LIST_URL), BinanceAlpha.bulkRequestUrls(ids))
        // Doppelte Kennungen zählen einmal
        assertEquals(3, BinanceAlpha.bulkRequestUrls(listOf("ALPHA_1USDT", "ALPHA_1USDT", "ALPHA_2USDT")).size)

        // Einzel-Ticker als Folgeanfrage: Kennung aus `data.symbol`; Fehler-Code → keine Zeile
        val market = Binance()
        val b = data["bulkTicker"].obj()
        val requestId = b["requestId"].num()!!.toInt()
        val parsed = market.parseBulkTickersDetailed(requestId, data["ticker"].obj()["body"] as String)
        assertEquals(setOf(b["id"]), parsed.tickers.keys)
        near("bulk ticker last", b["last"].num()!!, parsed.tickers.getValue(b["id"] as String).last)
        val none = market.parseBulkTickersDetailed(requestId, data["tickerError"].obj()["body"] as String)
        assertTrue(none.tickers.isEmpty() && none.failed.isEmpty())
    }

    @Test
    fun listCacheKeepsSixtySeconds() {
        val cache = BinanceAlpha.ListCache()
        assertNull(cache.get(1_000L))
        cache.put("list", 1_000L)
        assertEquals("list", cache.get(1_000L))
        assertEquals("list", cache.get(1_000L + BinanceAlpha.LIST_CACHE_MILLIS - 1))
        assertNull(cache.get(1_000L + BinanceAlpha.LIST_CACHE_MILLIS))
        // Uhr zurückgestellt: nicht weiterverwenden
        assertNull(cache.get(999L))
    }

    @Test
    fun singleTicker() {
        val market = Binance()
        val t = data["ticker"].obj()
        val id = t["id"] as String
        val info = PairQuery("FROGGIE", "USDT", id, FuturesContractType.NONE)
        assertEquals(t["url"], market.tickerAddress(0, info))
        val ticker = market.readTicker(0, t["body"] as String, MutableTicker(), info)
        val e = t["expected"].obj()
        near("last", e["last"].num()!!, ticker.last)
        near("high", e["high"].num()!!, ticker.high)
        near("low", e["low"].num()!!, ticker.low)
        near("vol", e["vol"].num()!!, ticker.vol)
        near("volQuote", e["volQuote"].num()!!, ticker.volQuote)
        near("change", e["change"].num()!!, ticker.change24hPercent ?: Double.NaN)
        assertNull(ticker.exchangeTime)
        val err = data["tickerError"].obj()
        try {
            market.readTicker(0, err["body"] as String, MutableTicker(), info)
            fail("error body must not parse")
        } catch (expected: Exception) {
            // erwartet
        }
        assertEquals(err["message"], market.readError(0, err["body"] as String, info))
        // Spot-Paare unverändert
        assertEquals(
            "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT",
            market.tickerAddress(0, PairQuery("BTC", "USDT", "BTCUSDT", FuturesContractType.NONE)),
        )
    }

    private fun candlePair(raw: Any?): CandlePair {
        val p = raw.obj()
        return CandlePair(
            marketKey = p["marketKey"] as String,
            marketName = p["marketName"] as String,
            base = p["base"] as String,
            quote = p["quote"] as String,
            pairId = p["pairId"] as String?,
            contractType = FuturesContractType.valueOf(p["contract"] as String),
        )
    }

    @Test
    fun candles() = runBlocking {
        val k = data["klines"].obj()
        val pair = candlePair(k["pair"])
        val requested = mutableListOf<String>()
        val body = k["body"] as String
        val expectedUrl = k["url"] as String
        val http = object : CandleHttp {
            override suspend fun get(url: String): String? {
                requested += url
                return if (url == expectedUrl) body else null
            }
        }
        assertTrue(ExchangeCandleRegistry.supports(pair, CandleInterval.H1))
        assertFalse(ExchangeCandleRegistry.supports(pair, CandleInterval.W1))
        val source = ExchangeCandleRegistry.sourceFor(pair)
        assertNotNull(source)
        val result = source!!.fetch(pair, CandleInterval.valueOf(k["interval"] as String), k["limit"].num()!!.toInt(), http)
        assertEquals(listOf(expectedUrl), requested)
        val rows = k["expected"].list()
        assertEquals(rows.size, result!!.size)
        rows.forEachIndexed { i, raw ->
            val r = raw.list()
            val c = result[i]
            assertEquals(r[0].num()!!.toLong(), c.openTime)
            near("open $i", r[1].num()!!, c.open)
            near("high $i", r[2].num()!!, c.high)
            near("low $i", r[3].num()!!, c.low)
            near("close $i", r[4].num()!!, c.close)
            near("volume $i", r[5].num()!!, c.volume)
            near("quoteVolume $i", r[6].num()!!, c.quoteVolume ?: Double.NaN)
        }
        // Herkunft «Binance Alpha», keine Ausweich-Kette (gleiches Kürzel anderswo = anderer Token)
        assertEquals(k["provider"], OwnCandlePolicy.provider(pair))
        assertFalse(OwnCandlePolicy.allowsFallback(pair))
        // Spot-Paar von Binance bleibt bei den Spot-Kerzen
        val spot = candlePair(k["spotPair"])
        assertEquals("Binance", OwnCandlePolicy.provider(spot))
        assertTrue(OwnCandlePolicy.allowsFallback(spot))
        assertTrue(ExchangeCandleRegistry.sourceFor(spot) !== source)
    }

    @Test
    fun kinds() {
        for (raw in data["kinds"].list()) {
            val c = raw.obj()
            val kind = AssetKind.of(
                marketKey = c["marketKey"] as String,
                base = c["base"] as String,
                tradFi = c["tradFi"] as Boolean,
                alphaSymbols = alphaSymbols,
                pairId = c["pairId"] as String?,
                pairName = c["pairName"] as String?,
                alphaNames = alphaNames,
            )
            assertEquals(c["name"] as String, c["expected"], kind.name)
        }
    }

    @Test
    fun allowsOnOff() {
        for (raw in data["allows"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val marketKey = c["marketKey"] as String
            val base = c["base"] as String
            val pair = MarketPair(base, "USDT", c["pairId"] as String?)
            val symbols = if (c["emptyList"] as Boolean) emptySet() else alphaSymbols
            val hidden = AssetScope.hiddenAlphaSymbols(c["includeAlpha"] as Boolean, symbols)
            val pairNames = (c["pairName"] as String?)?.let { mapOf(TokenIdentity.key(base) to it) }.orEmpty()
            assertEquals(name, c["expected"], AssetScope.TOKENS.allows(pair, marketKey, hidden, pairNames, alphaNames))
            // Gleiches Ergebnis über die ganze Liste
            val info = MarketPairsInfo(1L, listOf(pair))
            assertEquals(name, c["expected"], info.scoped(AssetScope.TOKENS, marketKey, hidden, pairNames, alphaNames).pairs.isNotEmpty())
        }
    }
}
