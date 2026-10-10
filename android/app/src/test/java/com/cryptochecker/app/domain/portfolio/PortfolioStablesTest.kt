package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Eine Stablecoin-Regel für Kopf, Verlauf, Stichtag und Widget ([PortfolioStables]). */
class PortfolioStablesTest {

    private val d = 1e-9
    private var nextId = 1L

    private fun buy(coin: String, amount: Double, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, 1.0, time)

    @Test
    fun oneListEverywhere() {
        assertEquals(CurrencyConversion.USD_STABLES, PortfolioStables.COINS)
        assertEquals(CurrencyConversion.USD_STABLES, CutoffExport.STABLES)
        // früher fehlten diese im Portfolio
        assertTrue(PortfolioHistory.isStable("usde"))
        assertTrue(CutoffExport.isStable(" PYUSD "))
        assertFalse(PortfolioStables.isStable("BTC"))
    }

    @Test
    fun usdtAlwaysOneOtherStablesMarketElseOne() {
        assertEquals(1.0, PortfolioStables.price("USDT", 1.0004)!!, d)
        assertEquals(1.0, PortfolioStables.price("usdt", null)!!, d)
        assertEquals(0.99, PortfolioStables.price("USDC", 0.99)!!, d)
        assertEquals(1.0, PortfolioStables.price("USDC", null)!!, d)
        assertEquals(1.0, PortfolioStables.price("DAI", Double.NaN)!!, d)
        assertEquals(1.0, PortfolioStables.price("DAI", 0.0)!!, d)
        assertEquals(50.0, PortfolioStables.price("BTC", 50.0)!!, d)
        assertNull(PortfolioStables.price("BTC", null))
        assertFalse(PortfolioStables.needsQuote("USDT"))
        assertTrue(PortfolioStables.needsQuote("USDC"))
    }

    @Test
    fun openBookUsesMarketPriceOfStable() {
        val trades = listOf(buy("USDC", 100.0, 1), buy("DAI", 10.0, 1), buy("USDT", 5.0, 1))
        val s = PortfolioCalculator.summarize(trades, mapOf("USDC" to 0.99))
        val usdc = s.open.first { it.coin == "USDC" }
        assertEquals(99.0, usdc.value!!, d)
        // DAI ohne Kurs: 1
        assertEquals(10.0, s.open.first { it.coin == "DAI" }.value!!, d)
        assertEquals(5.0, s.open.first { it.coin == "USDT" }.value!!, d)
        assertEquals(114.0, s.totalValue, d)
        assertTrue(s.missingCurrentPrices.isEmpty())
    }

    @Test
    fun historyUsesStableClosesWithFallbackOne() {
        val zone = ZoneId.of("Europe/Zurich")
        val dayEnd: (Long) -> Long = { day -> LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }
        val today = LocalDate.of(2026, 10, 6).toEpochDay()
        val noon = LocalDate.ofEpochDay(today - 3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val trades = listOf(buy("USDC", 100.0, noon), buy("DAI", 10.0, noon))
        // USDC: Schluss nur vorgestern (0.98) und gestern (0.99); vorher fehlt er → 1
        val closes = mapOf("USDC" to mapOf(today - 2 to 0.98, today - 1 to 0.99))
        val s = PortfolioHistory.build(
            trades, closes, mapOf("USDC" to 0.995), PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertTrue(s.skipped.isEmpty())
        assertEquals(listOf(110.0, 108.0, 109.0, 109.5), s.points.map { Math.round(it.value * 1000) / 1000.0 })
    }

    @Test
    fun cutoffRowsUseStableCloseElseOne() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("USDC", 10.0), CutoffHolding("DAI", 5.0), CutoffHolding("USDT", 2.0)),
            mapOf("USDC" to 0.97, "USDT" to 1.2),
            1.0,
        )
        assertEquals(0.97, rows[0].priceUsdt!!, d)
        assertEquals(9.7, rows[0].valueUsdt!!, d)
        assertEquals(1.0, rows[1].priceUsdt!!, d)
        assertFalse(rows[1].noPrice)
        // USDT ist die Bewertungswährung: immer 1
        assertEquals(1.0, rows[2].priceUsdt!!, d)
    }
}
