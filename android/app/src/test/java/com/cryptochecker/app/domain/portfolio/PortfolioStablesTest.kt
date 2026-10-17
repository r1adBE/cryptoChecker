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
    fun usdtAlwaysOneOtherStablesMarketElseNoValue() {
        assertEquals(1.0, PortfolioStables.price("USDT", 1.0004)!!, d)
        assertEquals(1.0, PortfolioStables.price("usdt", null)!!, d)
        assertEquals(0.99, PortfolioStables.price("USDC", 0.99)!!, d)
        // Kein stilles 1 mehr: ohne Kurs kein Wert
        assertNull(PortfolioStables.price("USDC", null))
        assertNull(PortfolioStables.price("DAI", Double.NaN))
        assertNull(PortfolioStables.price("DAI", 0.0))
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
        // DAI ohne Kurs: kein Wert («—», «ohne Kurs»), zählt unter «ohne N Coins»
        val dai = s.open.first { it.coin == "DAI" }
        assertNull(dai.value)
        assertNull(dai.currentPrice)
        assertNull(dai.unrealized)
        assertEquals(5.0, s.open.first { it.coin == "USDT" }.value!!, d)
        assertEquals(104.0, s.totalValue, d)
        assertEquals(listOf("DAI"), s.missingCurrentPrices)
        // Gewinn seit Kauf nur über Coins mit Kurs: USDC −1, USDT 0; DAI fehlt (kein angenommenes 1)
        assertEquals(-1.0, s.unrealized!!, d)
    }

    @Test
    fun historyUsesStableClosesWithoutFallbackOne() {
        val zone = ZoneId.of("Europe/Zurich")
        val dayEnd: (Long) -> Long = { day -> LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 }
        val today = LocalDate.of(2026, 10, 6).toEpochDay()
        val noon = LocalDate.ofEpochDay(today - 3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val trades = listOf(buy("USDC", 100.0, noon), buy("DAI", 10.0, noon))
        // USDC: Schluss je Tag; DAI ganz ohne Kerzen → fehlt (kein stilles 1), steht unter «ohne …»
        val closes = mapOf("USDC" to mapOf(today - 3 to 0.97, today - 2 to 0.98, today - 1 to 0.99))
        val s = PortfolioHistory.build(
            trades, closes, mapOf("USDC" to 0.995), PortfolioHistoryRange.WEEK, today, dayEnd,
        )
        assertEquals(listOf("DAI"), s.skipped)
        assertEquals(listOf(97.0, 98.0, 99.0, 99.5), s.points.map { Math.round(it.value * 1000) / 1000.0 })
    }

    @Test
    fun cutoffRowsUseStableCloseElseNoPrice() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("USDC", 10.0), CutoffHolding("DAI", 5.0), CutoffHolding("USDT", 2.0)),
            mapOf("USDC" to java.math.BigDecimal("0.97"), "USDT" to java.math.BigDecimal("1.2")),
            java.math.BigDecimal.ONE,
        )
        assertEquals(0, java.math.BigDecimal("0.97").compareTo(rows[0].priceUsdt))
        assertEquals(0, java.math.BigDecimal("9.7").compareTo(rows[0].valueUsdt))
        // DAI ohne Tagesschluss: «ohne Kurs» wie jeder Coin
        assertNull(rows[1].priceUsdt)
        assertNull(rows[1].valueUsdt)
        assertTrue(rows[1].noPrice)
        // USDT ist die Bewertungswährung: immer 1
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(rows[2].priceUsdt))
    }

    @Test
    fun todaySampleAppliesStableRule() {
        // Wie PortfolioSnapshotUpdater: Kursstand = Kurse nach der Stablecoin-Regel. 5'000 USDC mit
        // USDCUSDT 1.0 + 1 BTC 5'000 → 5'500 ergibt +5 % (USDC in Stand und Vergleich), nicht +10 %
        val trades = listOf(buy("USDC", 5_000.0, 1), buy("BTC", 1.0, 1))
        fun current(btc: Double) = PortfolioCalculator.summarize(trades, mapOf("BTC" to btc, "USDC" to 1.0)).open
            .mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()
        val sample = PriceSample(1L, current(5_000.0))
        assertEquals(1.0, sample.prices.getValue("USDC"), d)
        // Ohne USDCUSDT-Kurs fehlt USDC im Kursstand ganz (kein angenommenes 1)
        val withoutUsdc = PortfolioCalculator.summarize(trades, mapOf("BTC" to 5_000.0)).open
            .mapNotNull { p -> p.currentPrice?.let { p.coin to it } }.toMap()
        assertFalse("USDC" in withoutUsdc)
        val change = PortfolioSnapshotMath.todayChange(
            holdings = mapOf("USDC" to 5_000.0, "BTC" to 1.0),
            current = current(5_500.0),
            baseline = sample.prices,
        )!!
        assertEquals(500.0, change.amountUsd, d)
        assertEquals(5.0, change.percent!!, d)
    }
}
