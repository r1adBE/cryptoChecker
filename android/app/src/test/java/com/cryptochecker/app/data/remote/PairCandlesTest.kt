package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.candles.BinanceFuturesCandles
import com.cryptochecker.app.data.remote.candles.CandleHttp
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.ExchangeCandleRegistry
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.market.Sourced
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kerzen für Volumen-Alarm und «Warum?» wie der Chart (eigene Börse, Ausweich-Kette nur bei gleichem
 * Token, nie für Laufzeit-Kontrakte) und eigene Kerzen der Binance-Quartals-Kontrakte.
 * Gegenstück: `Tests/PairCandlesTests.swift`.
 */
class PairCandlesTest {

    private fun candle(close: Double, t: Long = 0L) = HourCandle(t, close, close, close, close, 1.0)
    private val series = listOf(candle(100.0, 0L), candle(101.0, 3_600_000L))

    private fun pair(
        contract: FuturesContractType = FuturesContractType.NONE,
        exchangeKey: String = "Kraken",
        pairId: String? = null,
        tradFi: Boolean = false,
        base: String = "BTC",
        quote: String = "USDT",
    ) = CandlePair(exchangeKey, exchangeKey, base, quote, pairId, contract, tradFi)

    /**
     * Ausweich-Kette mit Reihen [rows] nacheinander (Name der Quelle je Reihe: [names]); gibt die erste akzeptierte
     * zurück. Wie CandleDataSource: eine Quelle mit Namen nur ohne klaren Widerspruch ([TokenIdentity.compatible]).
     */
    private class FakeChain(
        private val rows: List<List<HourCandle>>,
        private val names: List<String?> = emptyList(),
        private val base: String = "BTC",
    ) {
        var calls = 0
        var identity: String? = null
        val chain: suspend (String?, suspend (List<HourCandle>) -> Boolean) -> Sourced<List<HourCandle>>? = { name, accept ->
            calls++
            identity = name
            rows.withIndex().firstOrNull { (i, row) ->
                (name == null || TokenIdentity.compatible(base, name, names.getOrNull(i))) && accept(row)
            }?.let { Sourced(it.value, "Binance") }
        }
    }

    @Test
    fun ownCandlesFirst() = runBlocking {
        val own = Sourced(series, "Kraken")
        val fake = FakeChain(listOf(series))
        val result = VolumeDataSource.pairCandlesOf(pair(), 101.0, identityName = null, ownSupported = true, own = { own }, chain = fake.chain)
        assertSame(own, result)
        assertEquals(0, fake.calls)
    }

    @Test
    fun fallbackOnlyWithSameToken() = runBlocking {
        // Erste Quelle: fremder Token gleichen Kürzels (Kurs 10× daneben) → nächste Quelle
        val foreign = listOf(candle(10.0), candle(10.1, 3_600_000L))
        val fake = FakeChain(listOf(foreign, series))
        val result = VolumeDataSource.pairCandlesOf(pair(), 120.0, identityName = null, ownSupported = true, own = { null }, chain = fake.chain)
        assertEquals(series, result?.value)
        assertEquals("Binance", result?.provider)
        // Kein Kurs: keine Ausweich-Reihe, die Kette wird gar nicht gefragt
        val noPrice = FakeChain(listOf(series))
        assertNull(VolumeDataSource.pairCandlesOf(pair(), null, identityName = null, ownSupported = false, own = { null }, chain = noPrice.chain))
        assertEquals(0, noPrice.calls)
        // Alle Reihen fremd → nil
        val allForeign = FakeChain(listOf(foreign))
        assertNull(VolumeDataSource.pairCandlesOf(pair(), 101.0, identityName = null, ownSupported = false, own = { null }, chain = allForeign.chain))
    }

    @Test
    fun fallbackRejectsNameMismatchLikeChart() = runBlocking {
        // «Warum?»/Volumen wie der Chart: an der Börse «Simon's Cat», Binance führt «Catcoin» — Kurs im Band, trotzdem nie
        val cat = pair(base = "CAT")
        val foreign = FakeChain(listOf(series), names = listOf("Catcoin"), base = "CAT")
        assertNull(VolumeDataSource.pairCandlesOf(cat, 101.0, identityName = "Simon's Cat", ownSupported = false, own = { null }, chain = foreign.chain))
        assertEquals("Simon's Cat", foreign.identity)
        // Gleicher Token (andere Schreibweise): mit Kursband
        val same = FakeChain(listOf(series), names = listOf("Simons Cat"), base = "CAT")
        assertEquals(series, VolumeDataSource.pairCandlesOf(cat, 101.0, identityName = "Simon's Cat", ownSupported = false, own = { null }, chain = same.chain)?.value)
        // Name unbekannt: nur Kürzel + Kursband
        val unknown = FakeChain(listOf(series), names = listOf("Catcoin"), base = "CAT")
        assertEquals(series, VolumeDataSource.pairCandlesOf(cat, 101.0, identityName = null, ownSupported = false, own = { null }, chain = unknown.chain)?.value)
    }

    @Test
    fun noFallbackForDatedTradFiDex() = runBlocking {
        for (p in listOf(
            pair(FuturesContractType.QUARTERLY, "BinanceFutures"),
            pair(FuturesContractType.BIQUARTERLY, "BinanceFutures", "2:BTCUSD_251226", quote = "USD"),
            pair(FuturesContractType.PERPETUAL, "BinanceFutures", tradFi = true),
            pair(exchangeKey = "DexScreener"),
        )) {
            val fake = FakeChain(listOf(series))
            assertNull(p.toString(), VolumeDataSource.pairCandlesOf(p, 101.0, identityName = null, ownSupported = false, own = { null }, chain = fake.chain))
            assertEquals(0, fake.calls)
            assertFalse(OwnCandlePolicy.allowsFallback(p))
        }
        // Perpetual darf weiter auf die Kette (mit Prüfung)
        assertTrue(OwnCandlePolicy.allowsFallback(pair(FuturesContractType.PERPETUAL, "BinanceFutures")))
        assertTrue(OwnCandlePolicy.allowsFallback(pair(FuturesContractType.INVERSE_PERPETUAL, "BybitFutures")))
    }

    @Test
    fun acceptsFallbackTolerance() {
        assertTrue(OwnCandlePolicy.acceptsFallback(series, 101.0))
        assertTrue(OwnCandlePolicy.acceptsFallback(series, 126.0))
        assertFalse(OwnCandlePolicy.acceptsFallback(series, 127.0))
        assertTrue(OwnCandlePolicy.acceptsFallback(series, 76.0))
        assertFalse(OwnCandlePolicy.acceptsFallback(series, 75.0))
        assertFalse(OwnCandlePolicy.acceptsFallback(series, null))
        assertFalse(OwnCandlePolicy.acceptsFallback(series, 0.0))
        assertFalse(OwnCandlePolicy.acceptsFallback(emptyList(), 101.0))
    }

    @Test
    fun binanceQuarterlyUsesOwnContractCandles() = runBlocking {
        val body = "[[1760400000000, \"62000\", \"62100\", \"61900\", \"62050\", \"10\", 1760403599999, \"620500\"]," +
            "[1760403600000, \"62050\", \"62200\", \"62000\", \"62150\", \"12\", 1760407199999, \"745800\"]]"
        // USDⓈ-M: Symbol der Börse aus der Paarliste, nie aus dem Kalender (Abrechnung Freitag 08:00 UTC)
        val usdM = pair(FuturesContractType.QUARTERLY, "BinanceFutures", "BTCUSDT_251226")
        assertTrue(ExchangeCandleRegistry.sourceFor(usdM) === BinanceFuturesCandles)
        val urls = mutableListOf<String>()
        val http = object : CandleHttp {
            override suspend fun get(url: String): String? { urls += url; return body }
        }
        val candles = BinanceFuturesCandles.fetch(usdM, CandleInterval.H1, 2, http)
        assertEquals(listOf("https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT_251226&interval=1h&limit=2"), urls)
        assertEquals(2, candles?.size)
        // COIN-M: dapi mit dem gespeicherten BTCUSD_<Verfallstag>, nie BTCUSD_PERP
        urls.clear()
        val coinM = pair(FuturesContractType.QUARTERLY, "BinanceFutures", "2:BTCUSD_250926", quote = "USD")
        BinanceFuturesCandles.fetch(coinM, CandleInterval.D1, 2, http)
        assertEquals(listOf("https://dapi.binance.com/dapi/v1/klines?symbol=BTCUSD_250926&interval=1d&limit=2"), urls)
        // Zweites Quartal: eigenes Symbol
        assertEquals("BTCUSDT_260327" to false, BinanceFuturesCandles.id(pair(FuturesContractType.BIQUARTERLY, "BinanceFutures", "BTCUSDT_260327")))
        // Laufzeit-Kontrakt ohne Verfallstag in der Kennung: kein Symbol, kein Chart (nie aus dem Kalender)
        assertNull(BinanceFuturesCandles.id(pair(FuturesContractType.QUARTERLY, "BinanceFutures", "BTCUSDT")))
        // Perpetual unverändert
        assertEquals("BTCUSD_PERP" to true, BinanceFuturesCandles.id(pair(FuturesContractType.PERPETUAL, "BinanceFutures", "2:BTCUSD_PERP", quote = "USD")))
        // Andere Börse mit Laufzeit-Kontrakt: keine eigene Quelle → kein Chart, keine Ausweich-Reihe
        val okxDated = pair(FuturesContractType.QUARTERLY, "OkexFutures", "BTC-USDT-251226")
        assertNull(ExchangeCandleRegistry.sourceFor(okxDated))
        assertFalse(OwnCandlePolicy.allowsFallback(okxDated))
    }
}
