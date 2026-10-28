package com.cryptochecker.marketdata.model.exchange

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.ExchangeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Bitunix Futures und EZB-Referenzkurse, Kategorien der Registry. Wie iOS `ExchangeListParityTests`. */
class NewExchangesTest {

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
        assertEquals(ExchangeCategory.FOREX, ecb.category)
    }

    @Test
    fun registryCategories() {
        val byCategory = ExchangesConfig.EXCHANGES.values.groupBy({ it.category }, { it.key })
        assertEquals(listOf("DexScreener"), byCategory[ExchangeCategory.DEX])
        assertEquals(listOf("EcbReference"), byCategory[ExchangeCategory.FOREX])
        val futures = byCategory.getValue(ExchangeCategory.FUTURES).toSet()
        assertTrue(ExchangesConfig.EXCHANGES.keys.filter { it.endsWith("Futures") }.all { it in futures })
        assertTrue(setOf("Hyperliquid", "Aster", "Lighter", "Deribit").all { it in futures })
        assertEquals(14, futures.size)
        // Nur EZB-Kurse ohne Alarme
        assertEquals(listOf("EcbReference"), ExchangesConfig.EXCHANGES.values.filter { !it.allowsAlarms }.map { it.key })
    }
}
