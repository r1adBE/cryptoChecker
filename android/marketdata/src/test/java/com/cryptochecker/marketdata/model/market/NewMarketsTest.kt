package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.MarketCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Bitunix Futures und EZB-Referenzkurse (Build 133), Kategorien der Registry. Wie iOS `ExchangeListParityTests`. */
class NewMarketsTest {

    @Test
    fun bitunixKeepsTheMultiplierInTheBase() {
        assertEquals("1000PEPE", BitunixFutures.baseOf("1000PEPEUSDT", "PEPE", "USDT"))
        assertEquals("BTC", BitunixFutures.baseOf("BTCUSDT", "BTC", "USDT"))
        assertEquals("X", BitunixFutures.baseOf("USDT", "X", "USDT"))
        assertEquals("ODD", BitunixFutures.baseOf("ODDPERP", "ODD", "USDT"))
    }

    @Test
    fun ecbSeriesAndGroups() {
        assertEquals(
            "https://api.frankfurter.dev/v1/2026-10-16..?base=EUR&symbols=CHF,USD",
            EcbReference.seriesUrl("EUR", listOf("CHF", "USD"), LocalDate.of(2026, 10, 26)),
        )
        assertEquals(
            listOf("EUR" to listOf("CHF", "USD"), "USD" to listOf("JPY")),
            EcbReference.bulkGroups(listOf("EURUSD", "USDJPY", "EURCHF", "EURUSD", "eurusd", "EUR/USD", " EURCHF ")),
        )
        val ecb = EcbReference()
        assertFalse(ecb.allowsAlarms)
        assertTrue(ecb.priceIsDailyReference)
        assertEquals(MarketCategory.FOREX, ecb.category)
    }

    @Test
    fun registryCategories() {
        val byCategory = MarketsConfig.MARKETS.values.groupBy({ it.category }, { it.key })
        assertEquals(listOf("DexScreener"), byCategory[MarketCategory.DEX])
        assertEquals(listOf("EcbReference"), byCategory[MarketCategory.FOREX])
        val futures = byCategory.getValue(MarketCategory.FUTURES).toSet()
        assertTrue(MarketsConfig.MARKETS.keys.filter { it.endsWith("Futures") }.all { it in futures })
        assertTrue(setOf("Hyperliquid", "Aster", "Lighter", "Deribit").all { it in futures })
        assertEquals(14, futures.size)
        // Nur EZB-Kurse ohne Alarme
        assertEquals(listOf("EcbReference"), MarketsConfig.MARKETS.values.filter { !it.allowsAlarms }.map { it.key })
    }
}
