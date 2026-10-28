package com.cryptochecker.app.domain.starter

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterPairsTest {

    @Test
    fun binanceUsdtOutsideUs() {
        val p = StarterPairs.pairFor("btc", "DE")
        assertEquals(StarterPairs.Pair("Binance", "Binance", "BTC", "USDT", "BTCUSDT"), p)
        assertEquals("Binance", StarterPairs.pairFor("ETH", "AT").exchangeKey)
        assertEquals("Binance", StarterPairs.pairFor("ETH", null).exchangeKey)
        assertEquals("Binance", StarterPairs.pairFor("ETH", "").exchangeKey)
    }

    @Test
    fun binanceUsInUs() {
        assertEquals(
            StarterPairs.Pair("BinanceUs", "BinanceUs", "SOL", "USDT", "SOLUSDT"),
            StarterPairs.pairFor("SOL", "us")
        )
    }

    @Test
    fun exchangeNameFromRegistry() {
        assertEquals("Binance Spot", StarterPairs.pairFor("BTC", "DE") { "Binance Spot" }.exchangeName)
    }

    @Test
    fun skipsExistingAndDuplicates() {
        val wanted = listOf("BTC", "ETH", "SOL").map { StarterPairs.pairFor(it, "DE") } +
            StarterPairs.pairFor("BTC", "DE")
        val existing = listOf(
            Triple("Binance", "btc", "usdt"),
            Triple("BinanceUs", "ETH", "USDT"),   // andere Börse: zählt nicht
        )
        assertEquals(listOf("ETH", "SOL"), StarterPairs.missing(wanted, existing).map { it.base })
    }

    @Test
    fun fallbackFiveCoins() {
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL"), StarterCoins.fallback("DE").map { it.symbol })
        // Schweiz/Liechtenstein: Binance wie überall ausser den USA (Entscheid 15. Oktober 2026)
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL"), StarterCoins.fallback("CH").map { it.symbol })
        // Binance.US: ohne XRP
        assertEquals(listOf("BTC", "ETH", "BNB", "SOL", "DOGE"), StarterCoins.fallback("US").map { it.symbol })
    }

    @Test
    fun binanceEverywhereExceptUs() {
        assertEquals("Binance", StarterPairs.exchangeKey("CH"))
        assertEquals("Binance", StarterPairs.exchangeKey(" li "))
        assertEquals("BinanceUs", StarterPairs.exchangeKey("US"))
        assertEquals("Binance", StarterPairs.exchangeKey("DE"))
        assertEquals("USDT", StarterPairs.startQuote("CH"))
        assertEquals("USDT", StarterPairs.startQuote(null))
        assertEquals(StarterPairs.Pair("Binance", "Binance", "BTC", "USDT", "BTCUSDT"), StarterPairs.pairFor("btc", "CH"))
    }

    /** Binance sperrt den Standort (451/403) → Startpaare auf Binance.US, wie iOS `StarterPairsTests`. */
    @Test
    fun geoBlockSwitchesToBinanceUs() {
        assertEquals(true, StarterPairs.isGeoBlocked(451))
        assertEquals(true, StarterPairs.isGeoBlocked(403))
        assertEquals(false, StarterPairs.isGeoBlocked(429))
        assertEquals(false, StarterPairs.isGeoBlocked(null))
        assertEquals("US", StarterPairs.effectiveRegion("CH", binanceBlocked = true))
        assertEquals("US", StarterPairs.effectiveRegion(null, binanceBlocked = true))
        assertEquals("CH", StarterPairs.effectiveRegion("CH", binanceBlocked = false))
        assertEquals("US", StarterPairs.effectiveRegion("US", binanceBlocked = true))
        val blocked = StarterPairs.pairFor("btc", StarterPairs.effectiveRegion("DE", binanceBlocked = true))
        assertEquals("BinanceUs", blocked.exchangeKey)
        assertEquals("BTCUSDT", blocked.pairId)
        assertEquals(
            listOf("BTC", "ETH", "BNB", "SOL", "DOGE"),
            StarterCoins.fallback(StarterPairs.effectiveRegion("CH", binanceBlocked = true)).map { it.symbol },
        )
        assertEquals(false, StarterPairs.shouldProbe("us"))
        assertEquals(true, StarterPairs.shouldProbe("CH"))
    }

    @Test
    fun blockedAfterProbe() {
        assertEquals(true, StarterPairs.blockedAfterProbe(false, 451))
        assertEquals(true, StarterPairs.blockedAfterProbe(false, 403))
        assertEquals(false, StarterPairs.blockedAfterProbe(true, 200))
        // Kein Netz oder anderer Fehler: Stand bleibt
        assertEquals(true, StarterPairs.blockedAfterProbe(true, null))
        assertEquals(false, StarterPairs.blockedAfterProbe(false, null))
        assertEquals(true, StarterPairs.blockedAfterProbe(true, 500))
        assertEquals(false, StarterPairs.blockedAfterProbe(false, 429))
    }

    @Test
    fun exchangeNameForStarterText() {
        val names = mapOf("Binance" to "Binance", "BinanceUs" to "Binance.US")
        assertEquals("Binance", StarterPairs.exchangeName("CH") { names[it] })
        assertEquals("Binance.US", StarterPairs.exchangeName("US") { names[it] })
        // Standort-Sperre: die Start-Auswahl nennt Binance.US
        assertEquals("Binance.US", StarterPairs.exchangeName(StarterPairs.effectiveRegion("DE", binanceBlocked = true)) { names[it] })
        // Ohne Registry-Namen: Schlüssel
        assertEquals("BinanceUs", StarterPairs.exchangeName("US"))
        assertEquals("USDT", StarterPairs.startQuote("US"))
    }
}
