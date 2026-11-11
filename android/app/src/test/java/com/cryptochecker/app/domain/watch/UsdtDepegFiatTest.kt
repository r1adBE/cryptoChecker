package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.portfolio.UsdtUsdRates
import com.cryptochecker.app.domain.widget.WidgetCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fiat-Werte aus der USDT-Reihe mit USDT/USD je Tag (Review 194): BTC in USDT flach, USDT/USD 1.00 → 0.90,
 * CHF je USD 0.90 — in CHF −10 %. Gegenstück: `Tests/UsdtDepegFiatTests.swift`.
 */
class UsdtDepegFiatTest {

    private val day = 86_400_000L
    private fun candle(atMillis: Long, price: Double) =
        WidgetCandle(openTime = atMillis, open = price, high = price, low = price, close = price)
    private val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
    private val flat = listOf(candle(20 * day, 100_000.0), candle(21 * day, 100_000.0))

    @Test
    fun dayChangeFromUsdtSeriesIncludesTheDepeg() {
        val ref = DayReference(100_000.0, 100_000.0)
        assertEquals(-10.0, DayChange.fromUsdtSeries(81_000.0, ref, 0.9, 0.9, usdtUsd = 0.9, usdtUsdAtOpen = 1.0)!!, 1e-9)
        assertFalse(DayChange.usdtSeriesApproximate(0.9, 1.0))
        // Ohne USDT/USD: 0 % (Näherung, markiert)
        assertEquals(0.0, DayChange.fromUsdtSeries(81_000.0, ref, 0.9, 0.9)!!, 1e-9)
        assertTrue(DayChange.usdtSeriesApproximate(null, 1.0))
        // Kein Schluss zur Eröffnung, live 0.90: beide Enden 0.90 → 0 % statt erfundener −10 %
        assertFalse(DayChange.usdtSeriesApproximate(0.9, null))
        assertTrue(DayChange.usdtSeriesHistoryMissing(0.9, null))
        assertEquals(0.0, DayChange.fromUsdtSeries(81_000.0, ref, 0.9, 0.9, usdtUsd = 0.9)!!, 1e-9)
        // Schluss da, aber kein Live-Kurs: beide Enden 1 → 0 % statt +11 %
        assertTrue(DayChange.usdtSeriesApproximate(null, 0.9))
        assertEquals(0.0, DayChange.fromUsdtSeries(90_000.0, ref, 0.9, 0.9, usdtUsdAtOpen = 0.9)!!, 1e-9)
        // Ohne Devisenkurs zur Eröffnung weiterhin kein Wert
        assertNull(DayChange.fromUsdtSeries(81_000.0, ref, 0.9, null, usdtUsd = 0.9, usdtUsdAtOpen = 1.0))
    }

    @Test
    fun chartUsesTheUsdtCloseOfEachDay() {
        val usdt = UsdtUsdRates(live = 0.9, daily = mapOf(20L to 1.0))
        val rates = mapOf(20L to 0.9, 21L to 0.9)
        val daily = SheetChart.accept(flat, fallback, 81_000.0, rates, usdt = usdt, todayEpochDay = 21)!!
        assertEquals(90_000.0, daily.first().close, 1e-6)
        assertEquals(81_000.0, daily.last().close, 1e-6)
        assertEquals(-10.0, SheetChart.changePercent(daily.first().close, daily.last().close)!!, 1e-9)
        // Ein Devisenkurs für alle Tage: USDT/USD trotzdem je Tag
        val current = SheetChart.accept(flat, fallback, 81_000.0, currentRate = 0.9, usdt = usdt, todayEpochDay = 21)!!
        assertEquals(90_000.0, current.first().close, 1e-6)
        assertEquals(81_000.0, current.last().close, 1e-6)
        assertFalse(SheetChart.usdtApproximate(daily, usdt, 21))
    }

    @Test
    fun missingHistoryNeverInventsAStep() {
        val rates = mapOf(20L to 0.9, 21L to 0.9)
        // Review 195: nur Merkliste (keine gespeicherten Schlüsse), live 0.90, BTC flach in USDT → keine Stufe
        val noClose = UsdtUsdRates(live = 0.9)
        val chart = SheetChart.accept(flat, fallback, 81_000.0, rates, usdt = noClose, todayEpochDay = 21)!!
        assertEquals(81_000.0, chart.first().close, 1e-6)
        assertEquals(81_000.0, chart.last().close, 1e-6)
        val series = SheetChart.usdtSeries(chart, noClose, 21)
        assertFalse(series.approximate)
        assertTrue(series.historyMissing)
        assertTrue(SheetChart.usdtHistoryNote(SheetChartFx.DAILY, "BTC/USDT", series.historyMissing))
        assertFalse(SheetChart.usdtParityNote(SheetChartFx.DAILY, "BTC/USDT", series.approximate))
        // Mit einem Devisenkurs ebenso
        val current = SheetChart.accept(flat, fallback, 81_000.0, currentRate = 0.9, usdt = noClose, todayEpochDay = 21)!!
        assertEquals(current.first().close, current.last().close, 1e-6)
        // Schluss für Tag 20, aber kein Live-Kurs: ganze Reihe 1, Vermerk «USDT = 1»
        val noLive = SheetChart.usdtSeries(flat, UsdtUsdRates(live = null, daily = mapOf(20L to 1.0)), 21)
        assertEquals(listOf(1.0, 1.0), noLive.rates)
        assertTrue(noLive.approximate)
        assertFalse(noLive.historyMissing)
        // Gar nichts: immer Vermerk «USDT = 1»
        assertTrue(SheetChart.usdtApproximate(flat, null, 21))
        // Echter Einbruch: Schluss 0.40 gespeichert und live 0.40 → beide Tage 0.40, keine Stufe
        val crash = UsdtUsdRates(live = 0.4, daily = mapOf(20L to 0.4))
        val crashed = SheetChart.accept(flat, fallback, 36_000.0, rates, usdt = crash, todayEpochDay = 21)!!
        assertEquals(36_000.0, crashed.first().close, 1e-6)
        assertEquals(36_000.0, crashed.last().close, 1e-6)
        assertFalse(SheetChart.usdtSeries(crashed, crash, 21).historyMissing)
    }

    @Test
    fun sparklineCheckDividesByUsdtUsd() {
        val closes = listOf(100.0, 100.0)
        assertNotNull(SheetChart.sparklineFor(closes, 56.0, 1.25, usdtUsd = 0.7))
        assertNull(SheetChart.sparklineFor(closes, 56.0, 1.25))
        // USDT-Quote: Faktor enthält USDT/USD schon
        assertNotNull(SheetChart.sparklineFor(closes, 100.0, 0.7, usdtUsd = 0.7))
    }
}
