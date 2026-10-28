package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grenzfälle der eigenen Kerzen, zweite und dritte Runde (ExchangeCandleSourcesGlobal.kt,
 * ExchangeCandleSourcesSpecial.kt): Wochenkerzen ohne Anfrage, keine oder unlesbare Antwort = keine Kerzen,
 * Fehlercodes der Börsen, nicht unterstützte Paare. Die gelesenen Kerzen selbst prüft `OwnCandlesParityTest`.
 */
class ExchangeCandleSourceErrorsTest {

    private fun pair(exchangeKey: String, pairId: String?, contract: FuturesContractType = FuturesContractType.NONE) =
        CandlePair(exchangeKey, exchangeKey, "BTC", "USDT", pairId, contract)

    /** Antwortet auf jede Anfrage (GET und POST) mit [body]; merkt sich die Anfragen. */
    private class Http(private val body: String?) : CandleHttp {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): String? {
            requested += url
            return body
        }

        override suspend fun post(url: String, json: String): String? {
            requested += "POST $url"
            return body
        }

        override val now: Long get() = 1_760_000_000_000L
    }

    private val perpetual = FuturesContractType.PERPETUAL

    /** Je Quelle ein unterstütztes Paar. */
    private val supported: List<Pair<ExchangeCandleSource, CandlePair>> = listOf(
        BitfinexCandles to pair("Bitfinex", "tBTCUSD"),
        BitgetCandles to pair("Bitget", "BTCUSDT"),
        BitgetCandles to pair("BitgetFutures", "BTCUSDT", perpetual),
        GateIoCandles to pair("GateIo", "BTC_USDT"),
        KucoinCandles to pair("Kucoin", "BTC-USDT"),
        MexcCandles to pair("Mexc", "BTCUSDT"),
        MexcFuturesCandles to pair("MexcFutures", "BTC_USDT", perpetual),
        HtxCandles to pair("Htx", "BTCUSDT"),
        GeminiCandles to pair("Gemini", "BTCUSD"),
        CryptoComCandles to pair("CryptoCom", "BTC_USDT"),
        UpbitCandles to pair("Upbit", "KRW-BTC"),
        WooCandles to pair("Woo", "SPOT_BTC_USDT"),
        WooCandles to pair("WooFutures", "PERP_BTC_USDT", perpetual),
        PoloniexCandles to pair("Poloniex", "BTC_USDT"),
        DeribitCandles to pair("Deribit", "BTC-PERPETUAL", perpetual),
        HyperliquidCandles to pair("Hyperliquid", "BTC", perpetual),
        OneTradingCandles to pair("OneTrading", "BTC_EUR"),
    )

    @Test
    fun everySourceSupportsItsPairAndIsRegistered() {
        for ((source, pair) in supported) {
            assertTrue("${pair.exchangeKey}: supports", source.supports(pair))
            assertTrue("${pair.exchangeKey}: exchangeKeys", pair.exchangeKey in source.exchangeKeys)
            assertEquals("${pair.exchangeKey}: registry", source, ExchangeCandleRegistry.sourceFor(pair))
            assertFalse("${pair.exchangeKey}: rate limit", source.isRateLimited("""{"ok":true}"""))
        }
    }

    @Test
    fun weeklyCandlesNeedNoRequest() = runBlocking {
        for ((source, pair) in supported) {
            val http = Http("[]")
            assertNull("${pair.exchangeKey}: W1", source.fetch(pair, CandleInterval.W1, 10, http))
            assertTrue("${pair.exchangeKey}: no request ${http.requested}", http.requested.isEmpty())
        }
    }

    @Test
    fun missingAnswerMeansNoCandles() = runBlocking {
        for ((source, pair) in supported) {
            for (interval in listOf(CandleInterval.H1, CandleInterval.H4, CandleInterval.D1)) {
                val http = Http(null)
                assertNull("${pair.exchangeKey} $interval", source.fetch(pair, interval, 5, http))
                assertTrue("${pair.exchangeKey} $interval: asked", http.requested.isNotEmpty())
            }
        }
    }

    @Test
    fun unreadableAnswerMeansNoCandles() = runBlocking {
        for ((source, pair) in supported) {
            val http = Http("<html>502 Bad Gateway</html>")
            assertNull("${pair.exchangeKey}", source.fetch(pair, CandleInterval.H1, 5, http))
        }
    }

    @Test
    fun geminiTriesBothSpellingsAndHtxAsksLowercase() = runBlocking {
        val gemini = Http(null)
        assertNull(GeminiCandles.fetch(pair("Gemini", "BTCUSD"), CandleInterval.D1, 2, gemini))
        assertEquals(
            listOf("https://api.gemini.com/v2/candles/btcusd/1day", "https://api.gemini.com/v2/candles/btcusd/1d"),
            gemini.requested,
        )
        val htx = Http(null)
        HtxCandles.fetch(pair("Htx", "BTCUSDT"), CandleInterval.H4, 2, htx)
        // 4 h aus Stundenkerzen: viermal so viele und ein Block Reserve
        assertEquals(listOf("https://api.huobi.pro/market/history/kline?symbol=btcusdt&period=60min&size=12"), htx.requested)
        val hyper = Http(null)
        HyperliquidCandles.fetch(pair("Hyperliquid", "BTC", perpetual), CandleInterval.H1, 2, hyper)
        assertEquals(listOf("POST https://api.hyperliquid.xyz/info"), hyper.requested)
    }

    @Test
    fun unsupportedPairsAreRejected() = runBlocking {
        // Ohne gültige Kennung bzw. mit falscher Vertragsart: auch fetch fragt nicht
        val withoutRequest = listOf(
            BitfinexCandles to pair("Bitfinex", "BTCUSD"),
            BitgetCandles to pair("Bitget", "BTCUSDT", perpetual),
            BitgetCandles to pair("BitgetFutures", "BTCUSDT"),
            BitgetCandles to pair("BitgetFutures", "BTCUSDT", FuturesContractType.QUARTERLY),
            GateIoCandles to pair("GateIo", null),
            KucoinCandles to pair("Kucoin", "BTC/USDT"),
            CryptoComCandles to pair("CryptoCom", null),
            WooCandles to pair("Woo", "PERP_BTC_USDT"),
            WooCandles to pair("WooFutures", "SPOT_BTC_USDT", perpetual),
            WooCandles to pair("WooFutures", "PERP_BTC_USDT"),
            OneTradingCandles to pair("OneTrading", "BTC EUR"),
            PoloniexCandles to pair("Poloniex", null),
        )
        for ((source, pair) in withoutRequest) {
            assertFalse("${pair.exchangeKey} ${pair.pairId} ${pair.contractType}", source.supports(pair))
            val http = Http("[]")
            assertNull(source.fetch(pair, CandleInterval.H1, 5, http))
            assertTrue("${pair.exchangeKey}: no request", http.requested.isEmpty())
        }
        // Falsche Vertragsart bei gültiger Kennung: die Auswahl (supports) schliesst sie aus
        val wrongContract = listOf(
            BitfinexCandles to pair("Bitfinex", "tBTCUSD", perpetual),
            GateIoCandles to pair("GateIo", "BTC_USDT", perpetual),
            MexcFuturesCandles to pair("MexcFutures", "BTC_USDT"),
            DeribitCandles to pair("Deribit", "BTC-PERPETUAL"),
            HyperliquidCandles to pair("Hyperliquid", "BTC"),
            UpbitCandles to pair("Upbit", "KRW-BTC", perpetual),
        )
        for ((source, pair) in wrongContract) {
            assertFalse("${pair.exchangeKey} ${pair.contractType}", source.supports(pair))
            assertNull("${pair.exchangeKey}: registry", ExchangeCandleRegistry.sourceFor(pair))
        }
    }

    @Test
    fun exchangeErrorCodesMeanNoCandles() {
        assertNull(BitgetCandles.parse("""{"code":"40034","msg":"Parameter does not exist","data":null}"""))
        assertNull(BitgetCandles.parse("""{"code":"00000","msg":"success"}"""))
        assertNull(KucoinCandles.parse("""{"code":"400100","msg":"This pair is not provided at present"}"""))
        assertNull(MexcFuturesCandles.parse("""{"success":false,"code":1001,"message":"contract not exist"}"""))
        assertNull(MexcFuturesCandles.parse("""{"success":true,"data":{}}"""))
        assertNull(HtxCandles.parse("""{"status":"error","err-code":"invalid-parameter","err-msg":"invalid symbol"}"""))
        assertNull(CryptoComCandles.parse("""{"id":-1,"code":40004,"message":"Invalid instrument_name"}"""))
        assertNull(CryptoComCandles.parse("""{"code":0,"result":{}}"""))
        assertNull(WooCandles.parse("""{"success":false,"code":-1001,"message":"symbol not found"}"""))
        assertNull(DeribitCandles.parse("""{"jsonrpc":"2.0","result":{"status":"no_data","ticks":[]}}"""))
        assertNull(DeribitCandles.parse("""{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"}}"""))
        // One Trading: «no_data» = leere Reihe, sonst unlesbar
        assertEquals(emptyList<Any>(), OneTradingCandles.parse("""{"status":"no_data"}"""))
        assertNull(OneTradingCandles.parse("""{"error":"INSTRUMENT_INVALID"}"""))
    }

    @Test
    fun rowsWithoutTimeOrShapeAreSkipped() {
        // Upbit/One Trading: unlesbare Zeit überspringen, Rest behalten
        val upbit = UpbitCandles.parse(
            """[{"candle_date_time_utc":"kaputt","opening_price":1,"high_price":1,"low_price":1,"trade_price":1},""" +
                """{"candle_date_time_utc":"2025-10-14T00:00:00","opening_price":100,"high_price":110,"low_price":90,""" +
                """"trade_price":105,"candle_acc_trade_volume":2,"candle_acc_trade_price":210}, 7]""",
        )
        assertEquals(1, upbit.size)
        assertEquals(CandleTime.parseIso("2025-10-14T00:00:00"), upbit.single().openTime)
        assertEquals(210.0, upbit.single().quoteVolume!!, 0.0)

        val one = OneTradingCandles.parse(
            """{"candlesticks":[{"start_time":"x","close":"1"},""" +
                """{"start_time":"2025-10-14T00:00:00.000Z","open":"100","high":"110","low":"90","close":"100","volume":"5000"}]}""",
        )!!
        assertEquals(1, one.size)
        // «volume» ist der Quote-Umsatz: Menge genähert über den Schluss
        assertEquals(50.0, one.single().volume, 1e-12)
        assertEquals(5000.0, one.single().quoteVolume!!, 0.0)

        // MEXC Futures: fehlende Spalte → NaN, die Kerze fällt später in CandleParse.finish weg
        val mexc = MexcFuturesCandles.parse("""{"success":true,"data":{"time":[1760000000],"close":[100],"amount":[1000]}}""")!!
        assertEquals(1_760_000_000_000L, mexc.single().openTime)
        assertTrue(mexc.single().open.isNaN())
        assertEquals(10.0, mexc.single().volume, 1e-12)
        assertTrue(CandleParse.finish(mexc, 5).isEmpty())

        val deribit = DeribitCandles.parse(
            """{"result":{"status":"ok","ticks":[1760000000000],"open":[100],"high":[110],"low":[90],"close":[105],"volume":[2],"cost":[210]}}""",
        )
        assertNotNull(deribit)
        assertEquals(105.0, deribit!!.single().close, 0.0)
        assertEquals(210.0, deribit.single().quoteVolume!!, 0.0)
    }
}
