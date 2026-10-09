package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetChartTest {

    private fun candle(i: Int, open: Double, close: Double, high: Double = maxOf(open, close), low: Double = minOf(open, close)) =
        WidgetCandle(openTime = i * 3_600_000L, open = open, high = high, low = low, close = close)

    private val series = listOf(
        candle(0, 100.0, 102.0, high = 103.0, low = 99.0),
        candle(1, 102.0, 98.0, high = 104.0, low = 97.0),
        candle(2, 98.0, 110.0, high = 111.0, low = 98.0),
    )

    @Test
    fun `ranges - cache lifetimes and time templates`() {
        assertEquals(listOf("DAY", "WEEK", "MONTH", "YEAR"), SheetChartRange.entries.map { it.name })
        assertEquals(60 * 60_000L, SheetChartRange.YEAR.cacheMillis)
        // 1 Jahr: Datum mit Jahr, ohne Uhrzeit
        assertTrue(SheetChartRange.YEAR.timeTemplate.contains("y") && SheetChartRange.YEAR.timeTemplate.contains("MMM"))
        assertFalse(SheetChartRange.YEAR.timeTemplate.contains("j"))
        assertEquals(5 * 60_000L, SheetChartRange.DAY.cacheMillis)
        assertEquals(30 * 60_000L, SheetChartRange.WEEK.cacheMillis)
        assertEquals(30 * 60_000L, SheetChartRange.MONTH.cacheMillis)
        // 24 h: Wochentag und Uhrzeit; 7 Tage mit Datum und Uhrzeit; 30 Tage nur Datum
        assertTrue(SheetChartRange.DAY.timeTemplate.contains("j"))
        assertFalse(SheetChartRange.DAY.timeTemplate.contains("MMM"))
        assertTrue(SheetChartRange.WEEK.timeTemplate.contains("MMM") && SheetChartRange.WEEK.timeTemplate.contains("j"))
        assertTrue(SheetChartRange.MONTH.timeTemplate.contains("MMM"))
        assertFalse(SheetChartRange.MONTH.timeTemplate.contains("j"))
    }

    @Test
    fun `dex and odd symbols are hidden`() {
        assertTrue(SheetChart.isSupported("Binance", "BTC", "USDT"))
        assertFalse(SheetChart.isSupported("DexScreener", "PEPE", "WETH"))
        assertFalse(SheetChart.isSupported("dexscreener", "PEPE", "WETH"))
        assertFalse(SheetChart.isSupported("Kraken", "BTC-PERP", "USD"))
        assertFalse(SheetChart.isSupported("Kraken", "", "USD"))
    }

    @Test
    fun `requests - pair first, usdt fallback only for fiat`() {
        assertEquals(
            listOf(SheetCandleRequest("BTC", "USDT", convert = false)),
            SheetChart.requests("btc", "USDC", quoteIsFiat = false),
        )
        // USD ist Fiat, teilt aber die USDT-Reihe — keine zweite Abfrage
        assertEquals(
            listOf(SheetCandleRequest("BTC", "USDT", convert = false)),
            SheetChart.requests("BTC", "USD", quoteIsFiat = true),
        )
        assertEquals(
            listOf(SheetCandleRequest("BTC", "CHF", convert = false), SheetCandleRequest("BTC", "USDT", convert = true)),
            SheetChart.requests("BTC", "chf", quoteIsFiat = true),
        )
        assertEquals(
            listOf(SheetCandleRequest("ETH", "BTC", convert = false)),
            SheetChart.requests("ETH", "BTC", quoteIsFiat = false),
        )
    }

    @Test
    fun `accept pair candles - drop invalid, reject unrelated series`() {
        val pair = SheetCandleRequest("BTC", "USDT", convert = false)
        val withBad = series + WidgetCandle(9, Double.NaN, 1.0, 1.0, 1.0)
        assertEquals(series, SheetChart.accept(withBad, pair, price = 111.0))
        assertEquals(series, SheetChart.accept(series, pair, price = null))
        // Kurs 50 % neben dem letzten Schluss: anderer Coin mit gleichem Kürzel
        assertNull(SheetChart.accept(series, pair, price = 165.0))
        assertNull(SheetChart.accept(series.take(1), pair, price = 110.0))
        assertNull(SheetChart.accept(null, pair, price = 110.0))
    }

    @Test
    fun `accept usdt fallback - scaled to the pair price`() {
        val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
        val converted = SheetChart.accept(series, fallback, price = 55.0)!!
        assertEquals(55.0, converted.last().close, 1e-9)
        assertEquals(50.0, converted.first().open, 1e-9)
        assertEquals(series.map { it.openTime }, converted.map { it.openTime })
        // Verlauf in Prozent bleibt gleich
        assertEquals(
            SheetChart.rangeChange(series, WidgetChartType.CANDLES)!!,
            SheetChart.rangeChange(converted, WidgetChartType.CANDLES)!!,
            1e-9,
        )
        assertNull(SheetChart.accept(series, fallback, price = null))
        assertNull(SheetChart.accept(series, fallback, price = 0.0))
    }

    @Test
    fun `scrub index from x - clamped to the candles`() {
        assertEquals(0, SheetChart.scrubIndex(-20f, 0f, 10f, 3))
        assertEquals(0, SheetChart.scrubIndex(9.9f, 0f, 10f, 3))
        assertEquals(1, SheetChart.scrubIndex(10f, 0f, 10f, 3))
        assertEquals(2, SheetChart.scrubIndex(29f, 0f, 10f, 3))
        // Finger über der Beschriftungsspalte → letzte Kerze
        assertEquals(2, SheetChart.scrubIndex(300f, 0f, 10f, 3))
        assertEquals(1, SheetChart.scrubIndex(25f, 10f, 10f, 3))
        assertNull(SheetChart.scrubIndex(5f, 0f, 0f, 3))
        assertNull(SheetChart.scrubIndex(5f, 0f, 10f, 0))
        assertNull(SheetChart.scrubIndex(Float.NaN, 0f, 10f, 3))
    }

    @Test
    fun `change over the range and while scrubbing`() {
        // Kerzen: erste Eröffnung 100 → letzter Schluss 110
        assertEquals(10.0, SheetChart.rangeChange(series, WidgetChartType.CANDLES)!!, 1e-9)
        // Linie: erster Schluss 102 → 110
        assertEquals((110.0 - 102.0) / 102.0 * 100.0, SheetChart.rangeChange(series, WidgetChartType.LINE)!!, 1e-9)
        assertEquals(-2.0, SheetChart.scrubChange(series, WidgetChartType.CANDLES, 1)!!, 1e-9)
        assertEquals(0.0, SheetChart.scrubChange(series, WidgetChartType.LINE, 0)!!, 1e-9)
        assertNull(SheetChart.scrubChange(series, WidgetChartType.CANDLES, 5))
        assertNull(SheetChart.rangeChange(series.take(1), WidgetChartType.CANDLES))
        assertNull(SheetChart.changePercent(0.0, 10.0))
        assertNull(SheetChart.changePercent(10.0, Double.NaN))
    }

    @Test
    fun `scrub label stays inside the chart`() {
        assertEquals(40f, SheetChart.labelLeft(centerX = 60f, width = 40f, minX = 0f, maxX = 200f))
        assertEquals(0f, SheetChart.labelLeft(centerX = 5f, width = 40f, minX = 0f, maxX = 200f))
        assertEquals(160f, SheetChart.labelLeft(centerX = 195f, width = 40f, minX = 0f, maxX = 200f))
        // Breiter als die Fläche: links bündig
        assertEquals(0f, SheetChart.labelLeft(centerX = 50f, width = 300f, minX = 0f, maxX = 200f))
    }

    @Test
    fun `haptic tick only on a new candle`() {
        assertTrue(SheetChart.isNewCandle(null, 0))
        assertTrue(SheetChart.isNewCandle(0, 1))
        assertFalse(SheetChart.isNewCandle(1, 1))
        assertFalse(SheetChart.isNewCandle(1, null))
    }

    @Test
    fun `cache per pair and range with its own lifetime`() {
        var now = 1_000_000L
        val cache = SheetChartCache { now }
        val ready = SheetChartResult.Ready(series, converted = false)
        cache.put("btc", "usdt", SheetChartRange.DAY, ready)
        cache.put("BTC", "USDT", SheetChartRange.WEEK, ready)
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.DAY))
        assertNull(cache.get("BTC", "USDT", SheetChartRange.MONTH))
        assertNull(cache.get("ETH", "USDT", SheetChartRange.DAY))
        now += 5 * 60_000L - 1
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.DAY))
        now += 1
        assertNull(cache.get("BTC", "USDT", SheetChartRange.DAY))
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.WEEK))
        now += 25 * 60_000L
        assertNull(cache.get("BTC", "USDT", SheetChartRange.WEEK))
    }

    @Test
    fun `header - 24h shows the pill value, 7T and 30T the candles`() {
        val candles = SheetChart.rangeChange(series, WidgetChartType.CANDLES)!!
        // 24h mit Ticker-Wert: genau die Zahl der Pille (−3.66 statt −3.41 aus den Kerzen)
        assertEquals(-3.66, SheetChart.headerChange(SheetChartRange.DAY, series, WidgetChartType.CANDLES, -3.66)!!, 1e-12)
        // Ohne 24-h-Wert (nicht gehandelt, noch kein Bezug) oder ungültig: aus den Kerzen
        assertEquals(candles, SheetChart.headerChange(SheetChartRange.DAY, series, WidgetChartType.CANDLES, null)!!, 1e-12)
        assertEquals(candles, SheetChart.headerChange(SheetChartRange.DAY, series, WidgetChartType.CANDLES, Double.NaN)!!, 1e-12)
        // 7T/30T: immer die Veränderung über den Zeitraum
        assertEquals(candles, SheetChart.headerChange(SheetChartRange.WEEK, series, WidgetChartType.CANDLES, -3.66)!!, 1e-12)
        assertEquals(candles, SheetChart.headerChange(SheetChartRange.MONTH, series, WidgetChartType.CANDLES, -3.66)!!, 1e-12)
        assertEquals(candles, SheetChart.headerChange(SheetChartRange.YEAR, series, WidgetChartType.CANDLES, -3.66)!!, 1e-12)
        assertNull(SheetChart.headerChange(SheetChartRange.WEEK, series.take(1), WidgetChartType.CANDLES, 1.0))
    }
}
