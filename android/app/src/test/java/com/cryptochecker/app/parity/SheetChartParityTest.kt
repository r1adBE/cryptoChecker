package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.UsdtUsdRates
import com.cryptochecker.app.domain.watch.SheetCandleRequest
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.widget.WidgetCandle
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Gemeinsame Fälle für Chart im Aktionsblatt und Mini-Chart (`testdata/parity/sheet_chart.json`):
 * gleicher Token der Ausweich-Reihe (25 %, mit Devisenkurs). Gegenstück: `Tests/SheetChartParityTests.swift`.
 */
class SheetChartParityTest {

    private val data = ParityJson.load("sheet_chart.json")

    private fun near(label: String, expected: Double, actual: Double?) {
        assertNotNull("$label: expected $expected, got null", actual)
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual!!) <= max(abs(expected) * 1e-9, 1e-9))
    }

    @Test
    fun accept() {
        val cases = data["accept"].list()
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            // Tag darf gebrochen sein (Stundenkerzen: 20.5 = 12:00 UTC am Tag 20)
            val candles = c["candles"].list().map { pair ->
                val (day, price) = pair.list().map { it.num()!! }
                WidgetCandle(openTime = (day * 86_400_000.0).toLong(), open = price, high = price, low = price, close = price)
            }
            val rates = byDay(c["dailyRates"])
            val request = SheetCandleRequest("BTC", "USDT", convert = c["convert"] as Boolean)
            val live = c["usdtLive"].num()
            val daily = byDay(c["usdtDaily"])
            val usdt = if (live != null || daily != null) UsdtUsdRates(live, daily.orEmpty()) else null
            val today = c["today"].num()?.toLong() ?: Math.floorDiv(candles.last().openTime, 86_400_000L)
            val result = SheetChart.accept(candles, request, c["price"].num(), rates, c["currentRate"].num(), usdt, today)
            val assumed = c["usdtAssumed"] as Boolean?
            if (assumed != null) {
                assertNotNull("$name: note needs a chart", result)
                val note = SheetChart.usdtParityNote(
                    SheetChart.fxMode(request, rates), "BTC/USDT", SheetChart.usdtApproximate(result!!, usdt, today),
                )
                assertEquals("$name: note «USDT = 1 assumed»", assumed, note)
            }
            (c["usdtHistoryMissing"] as Boolean?)?.let { missing ->
                assertNotNull("$name: note needs a chart", result)
                val note = SheetChart.usdtHistoryNote(
                    SheetChart.fxMode(request, rates), "BTC/USDT", SheetChart.usdtSeries(result!!, usdt, today).historyMissing,
                )
                assertEquals("$name: note «USDT/USD history not available»", missing, note)
            }
            val e = c["expected"]
            if (e == null) {
                assertNull("$name: expected no chart", result)
                continue
            }
            assertNotNull("$name: expected a chart", result)
            near("$name first", e.obj()["first"].num()!!, result!!.first().close)
            near("$name last", e.obj()["last"].num()!!, result.last().close)
        }
    }

    private fun byDay(raw: Any?): Map<Long, Double>? =
        (raw as Map<*, *>?)?.entries?.associate { (k, v) -> (k as String).toLong() to v.num()!! }

    @Test
    fun widget() {
        val cases = data["widget"].list()
        assertTrue(cases.size >= 6)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val candles = c["candles"].list().map { pair ->
                val (day, price) = pair.list().map { it.num()!! }
                WidgetCandle(openTime = day.toLong() * 86_400_000L, open = price, high = price, low = price, close = price)
            }
            val result = WidgetChartGeometry.pairCandles(candles, c["price"].num())
            assertEquals(name, c["shown"] as Boolean, result != null)
        }
    }

    @Test
    fun sparkline() {
        val cases = data["sparkline"].list()
        assertTrue(cases.size >= 6)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val closes = c["closes"].list().map { it.num()!! }
            val result = SheetChart.sparklineFor(closes, c["price"].num(), c["quoteUsd"].num(), c["usdtUsd"].num())
            assertEquals(name, c["shown"] as Boolean, result != null)
            if (result != null) assertEquals(name, closes, result)
        }
    }
}
