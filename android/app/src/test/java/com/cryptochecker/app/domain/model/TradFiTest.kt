package com.cryptochecker.app.domain.model

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.settings.AssetScope
import com.cryptochecker.marketdata.util.TradFi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kennzeichen «kein Krypto-Token» je Börse — Werte aus den öffentlichen Paarlisten (Oktober 2026).
 * Wie `TradFiTests.swift`.
 */
class TradFiTest {

    @Test
    fun binance() {
        assertTrue(TradFi.binance("TRADIFI_PERPETUAL", listOf("TradFi")))
        assertTrue(TradFi.binance("PERPETUAL", listOf("Pre-IPO", "TradFi")))
        assertFalse(TradFi.binance("PERPETUAL", listOf("Layer-1", "Crypto")))
        assertFalse(TradFi.binance("PERPETUAL", listOf("Index", "Crypto")))
        assertFalse(TradFi.binance("CURRENT_QUARTER", emptyList()))
    }

    @Test
    fun bybit() {
        listOf("stock", "ETF", "commodity", "forex").forEach { assertTrue(it, TradFi.bybit(it)) }
        listOf("", "innovation", "adventure").forEach { assertFalse(it, TradFi.bybit(it)) }
    }

    @Test
    fun okx() {
        assertTrue(TradFi.okx("3"))
        assertTrue(TradFi.okx("4"))
        assertFalse(TradFi.okx("1"))
        assertFalse(TradFi.okx(""))
    }

    @Test
    fun mexc() {
        // Aktie (Typ 2), Gold (Typ 1, Bereich «metals»/«tradfi»), Bitcoin
        assertTrue(TradFi.mexc(listOf("mc-trade-zone-Stock", "mc-trade-zone-0fees", "mc-trade-zone-tradfi"), 2))
        assertTrue(TradFi.mexc(listOf("mc-trade-zone-metals", "mc-trade-zone-tradfi"), 1))
        assertTrue(TradFi.mexc(emptyList(), 2))
        assertFalse(TradFi.mexc(listOf("mc-trade-zone-mainly", "mc-trade-zone-layer2", "mc-trade-zone-pow"), 1))
    }

    @Test
    fun bitget() {
        assertTrue(TradFi.bitget("YES"))
        assertFalse(TradFi.bitget("NO"))
        assertFalse(TradFi.bitget(""))
    }

    @Test
    fun htxAndPhemex() {
        assertTrue(TradFi.htx(listOf("stock")))
        assertTrue(TradFi.htx(listOf("", "commodity")))
        assertFalse(TradFi.htx(listOf("")))
        assertFalse(TradFi.htx(listOf(" ")))
        assertFalse(TradFi.htx(emptyList()))
        assertTrue(TradFi.phemex("TradFi"))
        assertFalse(TradFi.phemex("Crypto"))
        assertFalse(TradFi.phemex(""))
    }

    @Test
    fun aster() {
        listOf(
            listOf("STOCK"), listOf("STOCK", "Semiconductor"), listOf("STOCK", "ETF"), listOf("ETF"),
            listOf("Commodities"), listOf("STOCK", "AOS2", "USD1-RWA"), listOf("pre-launch", "STOCK"), listOf("stock"),
        ).forEach { assertTrue("$it", TradFi.aster(it)) }
        listOf(listOf("Top"), listOf("Meme"), listOf("AI"), emptyList(), listOf("AOS2"), listOf("pre-launch"))
            .forEach { assertFalse("$it", TradFi.aster(it)) }
    }

    @Test
    fun bingx() {
        listOf("NCSKAAPL2USD", "NCSINIKKEI2252USD", "NCCOGOLD2USD", "NCFXEUR2USD").forEach { assertTrue(it, TradFi.bingx(it)) }
        listOf("BTC", "NEIROCTO", "PAXG", "NC", "NCT", "NCSK", "").forEach { assertFalse(it, TradFi.bingx(it)) }
        mapOf(
            "AAPL-USDT" to "AAPL", "GOLD(XAU)-USDT" to "XAU", "SILVER(XAG)-USDT" to "XAG",
            "Palladium(XPD)-USDT" to "XPD", "Oil Brent-USDT" to "OILBRENT", "Nikkei 225-USDT" to "NIKKEI225",
            "EURUSD-USDT" to "EURUSD", "" to "NCSKX2USD",
        ).forEach { (name, base) -> assertEquals(name, base, TradFi.bingxBase(name, "NCSKX2USD")) }
    }

    @Test
    fun lighterRobinhood() {
        // nicht in tokenlist: Vorsilbe «rh» + Grossbuchstabe genügt
        assertTrue(TradFi.lighterRobinhood("rhSPY", null))
        assertTrue(TradFi.lighterRobinhood("rhQQQ", ""))
        // Rest in tokenlist: dessen asset_type entscheidet
        assertTrue(TradFi.lighterRobinhood("rhQQQ", "RWA"))
        assertFalse(TradFi.lighterRobinhood("rhETH", "CRYPTO"))
        listOf("rh", "rhx", "RHSPY", "rh1", "SPY", "ETH").forEach { assertFalse(it, TradFi.lighterRobinhood(it, null)) }
    }

    /** «Nur TradFi» zeigt die Börsen, deren Paarliste TradFi kennzeichnet — Hyperliquid tut das nicht. */
    @Test
    fun tradFiMarkets() {
        listOf("Aster", "BingXFutures", "Lighter", "PhemexFutures").forEach {
            assertTrue(it, it in CoinLogos.TRADFI_MARKETS)
            assertTrue(it, AssetScope.TRADFI.allowsMarket(it))
        }
        listOf("Hyperliquid", "BingX").forEach { assertFalse(it, AssetScope.TRADFI.allowsMarket(it)) }
    }
}
