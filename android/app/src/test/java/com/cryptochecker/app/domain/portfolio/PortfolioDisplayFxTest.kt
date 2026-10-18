package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * «Seit Kauf» und «7 T» des Portfolio-Widgets in der Anzeigewährung aus dem Merkzettel der App ([PortfolioDisplayFigures]):
 * dieselbe Zahl wie die Gesamtkarte ([PortfolioCostFx.figures]), nie USDT × heutiger Kurs. Spiegel: PortfolioDisplayFxTests.swift.
 */
class PortfolioDisplayFxTest {

    private val day = 86_400_000L
    private val d0 = 20_000L
    private val d1 = 20_100L
    private val today = 20_200L

    private fun trade(id: Long, coin: String, amount: String, price: String, epochDay: Long) = PortfolioTrade(
        id = id, coin = coin, type = PortfolioTxType.BUY, amount = BigDecimal(amount), priceUsdt = BigDecimal(price),
        time = epochDay * day + day / 2,
    )

    private val trades = listOf(trade(1, "BTC", "1", "20000", d0), trade(2, "SOL", "10", "100", d1))
    private val prices = mapOf("BTC" to 30_000.0, "SOL" to 150.0)

    /** USD → CHF nur am Kauftag von BTC (SOL ohne Devisenkurs: fehlt, «unvollständig»). */
    private val rates = mapOf(d0 to BigDecimal("0.9"))

    @Test
    fun unrealizedMatchesTheCardFigures() {
        val summary = PortfolioCalculator.summarize(trades, prices)
        val figures = PortfolioCostFx.figures(summary, "CHF", BigDecimal("0.8"), today, rates, emptyMap())
        // Karte: BTC 30000 × 0.8 − 20000 × 0.9 = 24000 − 18000 = 6000 (+33.33 %), SOL ohne Kurs am Kauftag
        assertEquals(0, BigDecimal(6000).compareTo(figures.unrealized!!.setScale(6, java.math.RoundingMode.HALF_UP)))
        assertEquals(listOf("SOL"), figures.fxMissingCoins)
        val memo = PortfolioDisplayFigures.memo("chf", figures, summary.open, null, today)!!
        assertEquals("CHF", memo.currency)
        assertEquals(setOf("BTC"), memo.costs.keys)
        val widget = PortfolioDisplayFigures.unrealized(summary.open, "CHF", 0.8, memo)!!
        assertEquals(6000.0, widget.amount, 1e-6)
        assertEquals(figures.unrealizedPercent!!, widget.percent!!, 1e-9)
        assertEquals(100.0 / 3.0, widget.percent!!, 1e-9)
        assertEquals(listOf("SOL"), widget.skipped)
        // Später, Kurs gestiegen: mit dem aktuellen Wert nachgerechnet (Einstand zum Kurs der Erwerbstage bleibt)
        val later = PortfolioCalculator.summarize(trades, mapOf("BTC" to 40_000.0, "SOL" to 150.0))
        assertEquals(40_000.0 * 0.8 - 18_000.0, PortfolioDisplayFigures.unrealized(later.open, "CHF", 0.8, memo)!!.amount, 1e-6)
        // Andere Währung: kein Wert
        assertNull(PortfolioDisplayFigures.unrealized(summary.open, "EUR", 0.8, memo))
        assertNull(PortfolioDisplayFigures.unrealized(summary.open, "CHF", 0.8, null))
    }

    @Test
    fun changedCoinIsNotRecomputedWithAnOldCost() {
        val summary = PortfolioCalculator.summarize(trades, prices)
        val figures = PortfolioCostFx.figures(summary, "CHF", BigDecimal("0.8"), today, rates, emptyMap())
        val memo = PortfolioDisplayFigures.memo("CHF", figures, summary.open, null, today)
        // Seither BTC nachgekauft: Einstand USDT anders → BTC fehlt; kein Coin übrig → «—»
        val more = PortfolioCalculator.summarize(trades + trade(3, "BTC", "1", "25000", d1), prices)
        assertNull(PortfolioDisplayFigures.unrealized(more.open, "CHF", 0.8, memo))
    }

    @Test
    fun weekFactorsOnlyWhenTheMemoCoversTheWeek() {
        val summary = PortfolioCalculator.summarize(trades, prices)
        val figures = PortfolioCostFx.figures(summary, "CHF", BigDecimal("0.8"), today, rates, emptyMap())
        val memo = PortfolioDisplayFigures.memo("CHF", figures, summary.open, { d -> 0.8 + (today - d) * 0.001 }, today)!!
        assertEquals(PortfolioDisplayFigures.MEMO_DAYS, memo.dayFactors.size)
        val fx = PortfolioDisplayFigures.periodFx(memo, "CHF", 0.85, today)
        assertNotNull(fx)
        assertEquals(0.807, fx!!(today - 7), 1e-12)
        assertEquals(0.85, fx(today), 1e-12)
        // Am nächsten UTC-Tag fehlt der Vortag: «7 T» in USDT
        assertNull(PortfolioDisplayFigures.periodFx(memo, "CHF", 0.85, today + 1))
        assertNull(PortfolioDisplayFigures.periodFx(memo, "EUR", 0.85, today))
        val gap = memo.copy(dayFactors = memo.dayFactors - (today - 3))
        assertNull(PortfolioDisplayFigures.periodFx(gap, "CHF", 0.85, today))
        // Ohne Tagesfaktoren der App (Kurse laden noch): keine Faktoren gemerkt
        assertTrue(PortfolioDisplayFigures.memo("CHF", figures, summary.open, null, today)!!.dayFactors.isEmpty())
        // Betrag aus der Rechnung mit Faktoren ist schon in der Anzeigewährung
        val change = PortfolioDisplayFigures.periodChange(TodayChange(amountUsd = 12.5, percent = 1.2, skipped = listOf("SOL")))!!
        assertEquals(PortfolioChange(12.5, 1.2, listOf("SOL")), change)
        assertNull(PortfolioDisplayFigures.periodChange(null))
    }
}
