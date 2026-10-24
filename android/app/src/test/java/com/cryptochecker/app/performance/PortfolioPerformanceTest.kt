package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.performance.PerformanceTiming.TRANSACTIONS
import com.cryptochecker.app.performance.PerformanceTiming.assertLimitOnly
import com.cryptochecker.app.performance.PerformanceTiming.assertScales
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Bestände eines grossen Portfolios (bis 10 000 Transaktionen, [PortfolioPerformanceData]): Gesamtsicht der Karte
 * ([PortfolioCalculator.summarize]: Bestand, Einstand, Ø bzw. FIFO-Posten, realisiert) je Einstandsmethode und der
 * Bestand zu einem Zeitpunkt ([PortfolioCalculator.holdingsAt], Verlauf, Stichtag). Grenzen und Skalierung:
 * [PerformanceTiming] mit [TRANSACTIONS]. iOS: PortfolioPerformanceTests.swift.
 */
class PortfolioPerformanceTest {

    private val prices = PortfolioPerformanceData.prices()

    private fun summary(trades: List<PortfolioTrade>, method: CostMethod): PortfolioSummary =
        PortfolioCalculator.summarize(trades, prices, method, PortfolioPerformanceData::priceAtDouble)

    @Test
    fun dataIsDeterministicAndMixed() {
        val n = TRANSACTIONS.large
        val trades = PortfolioPerformanceData.trades(n)
        assertEquals(trades, PortfolioPerformanceData.trades(n))
        assertEquals(n, trades.size)
        assertTrue("zeitlich aufsteigend", trades.zipWithNext().all { (a, b) -> a.time < b.time })
        val years = trades.map { Instant.ofEpochMilli(it.time).atZone(ZoneOffset.UTC).year }.toSet()
        assertEquals((2019..2025).toSet(), years)
        // Jede Art kommt vor (mindestens 0,5 %), Gebühren in allen drei Formen
        val byType = trades.groupingBy { it.type }.eachCount()
        PortfolioTxType.entries.forEach { assertTrue("$it: ${byType[it]}", (byType[it] ?: 0) >= n / 200) }
        assertTrue(trades.any { it.coinFee.signum() > 0 } && trades.any { it.quoteFee.signum() > 0 })
        assertTrue(trades.any { it.thirdFeeCoin == "BNB" })
        assertTrue(trades.any { it.acquiredTime != null } && trades.any { it.priceUsdt == null })
        val wallets = trades.mapNotNull { it.exchange ?: it.transferTo }.toSet()
        assertEquals(PortfolioPerformanceData.WALLETS.toSet(), wallets)
    }

    @Test
    fun summaryFifo() {
        // Karte mit FIFO-Posten über alle Wallets: erwartet rund 50–150 ms für 10 000 Transaktionen
        assertScales("Portfolio-Übersicht FIFO", limitMillis = 1_500.0, sizes = TRANSACTIONS) { n ->
            val trades = PortfolioPerformanceData.trades(n)
            medianMillis { summary(trades, CostMethod.FIFO) }
        }
        assertSameHoldings(CostMethod.FIFO)
    }

    @Test
    fun summaryFifoPerWallet() {
        // FIFO je Wallet (Umbuchungen verschieben Posten): erwartet rund 60–200 ms
        assertScales("Portfolio-Übersicht FIFO je Wallet", limitMillis = 2_000.0, sizes = TRANSACTIONS) { n ->
            val trades = PortfolioPerformanceData.trades(n)
            medianMillis { summary(trades, CostMethod.FIFO_WALLET) }
        }
        assertSameHoldings(CostMethod.FIFO_WALLET)
    }

    @Test
    fun summaryAverage() {
        // Ø ohne Herkunftsteile (Karte in USDT, Widget, Alarme): linear wie FIFO; erwartet rund 50–150 ms für 10 000
        // Transaktionen. Nur «Seit Kauf» in der Anzeigewährung und der Steuer-Export führen die Teile
        // (PortfolioCalculatorAverage, `takeParts`: Zugänge × Abgänge je Coin) — deren Grenze prüft summaryAverageWithParts
        assertScales("Portfolio-Übersicht Ø", limitMillis = 1_500.0, sizes = TRANSACTIONS) { n ->
            val trades = PortfolioPerformanceData.trades(n)
            medianMillis {
                PortfolioCalculator.summarize(
                    trades, prices, CostMethod.AVERAGE, PortfolioPerformanceData::priceAtDouble, withParts = false,
                )
            }
        }
        assertSameHoldings(CostMethod.AVERAGE)
    }

    @Test
    fun summaryAverageWithParts() {
        // Ø mit Herkunftsteilen («Seit Kauf» in der Anzeigewährung): wächst schneller als linear — jeder Abgang nimmt aus
        // ALLEN Teilen anteilig, bis der Bestand ganz weg ist. Darum nur die feste Grenze; erwartet rund 0,5–2 s für
        // 10 000 Transaktionen (Verhältnis zu 2500 etwa ×10–16)
        assertLimitOnly("Portfolio-Übersicht Ø mit Herkunftsteilen", limitMillis = 15_000.0, sizes = TRANSACTIONS) { n ->
            val trades = PortfolioPerformanceData.trades(n)
            medianMillis { summary(trades, CostMethod.AVERAGE) }
        }
    }

    @Test
    fun holdingsAtTime() {
        // Bestand per Ende 2024 nach allen drei Methoden (Verlauf, Stichtag): erwartet rund 20–60 ms
        val end2024 = 1_735_689_599_999L
        assertScales("Bestand zum Zeitpunkt (3 Methoden)", limitMillis = 1_000.0, sizes = TRANSACTIONS) { n ->
            val trades = PortfolioPerformanceData.trades(n)
            medianMillis { CostMethod.entries.map { PortfolioCalculator.holdingsAt(trades, end2024, it) } }
        }
    }

    /** Die Karte nennt dieselben Mengen wie [PortfolioCalculator.holdingsAt] (eine Regel für alle Bestände). */
    private fun assertSameHoldings(method: CostMethod) {
        val trades = PortfolioPerformanceData.trades(TRANSACTIONS.large)
        val s = summary(trades, method)
        val held = PortfolioCalculator.holdingsAt(trades, method = method)
        assertTrue("$method: ${s.open.size} offene Coins", s.open.size >= PortfolioPerformanceData.COINS.size / 2)
        assertEquals(held.keys, s.open.map { it.coin }.toSet())
        s.open.forEach { p ->
            val diff = (held.getValue(p.coin) - p.ledgerHoldingsExact).abs()
            assertTrue("${p.coin} $method: Unterschied $diff", diff <= TOLERANCE)
        }
    }

    private companion object {
        /** Rundung der anteiligen Teile (34 Stellen) ist weit darunter. */
        val TOLERANCE = BigDecimal("1E-12")
    }
}
