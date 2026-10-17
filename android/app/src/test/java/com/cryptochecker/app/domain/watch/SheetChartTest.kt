package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Ohne Kurs des Paars nicht prüfbar: kein Chart (auch beim Paar selbst)
        assertNull(SheetChart.accept(series, pair, price = null))
        // Kurs 50 % neben dem letzten Schluss: anderer Coin mit gleichem Kürzel
        assertNull(SheetChart.accept(series, pair, price = 165.0))
        assertNull(SheetChart.accept(series.take(1), pair, price = 110.0))
        assertNull(SheetChart.accept(null, pair, price = 110.0))
    }

    @Test
    fun `accept usdt fallback - same token checked, then scaled to the pair price`() {
        val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
        // 110 USDT × 0.5 (Fiat je USD) = 55 → gleicher Token
        val converted = SheetChart.accept(series, fallback, price = 55.0, currentRate = 0.5)!!
        assertEquals(55.0, converted.last().close, 1e-9)
        assertEquals(50.0, converted.first().open, 1e-9)
        assertEquals(series.map { it.openTime }, converted.map { it.openTime })
        // Verlauf in Prozent bleibt gleich
        assertEquals(
            SheetChart.rangeChange(series, WidgetChartType.CANDLES)!!,
            SheetChart.rangeChange(converted, WidgetChartType.CANDLES)!!,
            1e-9,
        )
        // Leicht neben dem umgerechneten Schluss (11 %): gilt, letzter Schluss = Kurs des Paars
        assertEquals(55.0, SheetChart.accept(series, fallback, price = 55.0, currentRate = 0.45)!!.last().close, 1e-9)
        // Reihe endet bei ~110, Paar steht bei 55 (Kurs 1): 50 % daneben → anderer Token, kein Chart
        assertNull(SheetChart.accept(series, fallback, price = 55.0, currentRate = 1.0))
        assertNull(SheetChart.accept(series, fallback, price = 55.0, currentRate = 0.3))
        // Ohne Devisenkurs nicht prüfbar: nicht blind skalieren
        assertNull(SheetChart.accept(series, fallback, price = 55.0))
        assertNull(SheetChart.accept(series, fallback, price = 55.0, currentRate = 0.0))
        assertNull(SheetChart.accept(series, fallback, price = 55.0, currentRate = Double.NaN))
        // Ohne Kurs des Paars nicht prüfbar
        assertNull(SheetChart.accept(series, fallback, price = null, currentRate = 0.5))
        assertNull(SheetChart.accept(series, fallback, price = 0.0, currentRate = 0.5))
    }

    @Test
    fun `accept usdt fallback - gap rule at 25 percent like the pair`() {
        val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
        val candles = listOf(dayCandle(1, 0, 100.0), dayCandle(2, 0, 100.0))
        // Umgerechnet 100 × 0.8 = 80: 99 (+23.75 %) gilt, 101 (+26.25 %) nicht; 61 (−23.75 %) gilt, 59 nicht
        assertNotNull(SheetChart.accept(candles, fallback, price = 99.0, currentRate = 0.8))
        assertNull(SheetChart.accept(candles, fallback, price = 101.0, currentRate = 0.8))
        assertNotNull(SheetChart.accept(candles, fallback, price = 61.0, currentRate = 0.8))
        assertNull(SheetChart.accept(candles, fallback, price = 59.0, currentRate = 0.8))
        // Mit Tageskursen zählt der Kurs des Tags der letzten Kerze (0.8), nicht der aktuelle
        assertNotNull(SheetChart.accept(candles, fallback, price = 80.0, dailyRates = mapOf(1L to 0.5, 2L to 0.8), currentRate = 0.5))
        assertNull(SheetChart.accept(candles, fallback, price = 50.0, dailyRates = mapOf(1L to 0.5, 2L to 0.8), currentRate = 0.5))
    }

    @Test
    fun `sparkline - same token check`() {
        val closes = listOf(100.0, 102.0, 110.0)
        assertEquals(closes, SheetChart.sparklineFor(closes, price = 111.0, quoteUsd = 1.0))
        // EUR-Paar: 100 EUR × 1.1 USD je EUR = 110
        assertEquals(closes, SheetChart.sparklineFor(closes, price = 100.0, quoteUsd = 1.1))
        // Anderer Token unter gleichem Kürzel
        assertNull(SheetChart.sparklineFor(closes, price = 0.5, quoteUsd = 1.0))
        // Nicht prüfbar: kein Mini-Chart
        assertNull(SheetChart.sparklineFor(closes, price = null, quoteUsd = 1.0))
        assertNull(SheetChart.sparklineFor(closes, price = 110.0, quoteUsd = null))
        assertNull(SheetChart.sparklineFor(closes, price = 110.0, quoteUsd = Double.NaN))
        assertNull(SheetChart.sparklineFor(listOf(110.0), price = 110.0, quoteUsd = 1.0))
        assertNull(SheetChart.sparklineFor(null, price = 110.0, quoteUsd = 1.0))
        assertNull(SheetChart.sparklineFor(listOf(100.0, 0.0), price = 110.0, quoteUsd = 1.0))
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

    @Test
    fun `volume - quote volume from source, else base times close`() {
        assertEquals(1_234.5, SheetChart.quoteVolume(10.0, 1_234.5, 100.0)!!, 1e-9)
        // Coinbase: nur Menge → Menge × Schluss
        assertEquals(1_000.0, SheetChart.quoteVolume(10.0, null, 100.0)!!, 1e-9)
        // Ungültiger Quote-Umsatz fällt auf die Näherung zurück
        assertEquals(1_000.0, SheetChart.quoteVolume(10.0, Double.NaN, 100.0)!!, 1e-9)
        assertNull(SheetChart.quoteVolume(Double.NaN, null, 100.0))
        assertNull(SheetChart.quoteVolume(10.0, null, 0.0))
    }

    @Test
    fun `volume - fractions of the largest bar, hidden without data`() {
        val fractions = SheetChart.volumeFractions(listOf(50.0, 100.0, null, 25.0, Double.NaN, -1.0))!!
        assertEquals(listOf(0.5f, 1f, 0f, 0.25f, 0f, 0f), fractions)
        // Weniger als zwei Kerzen mit Umsatz: ausblenden
        assertNull(SheetChart.volumeFractions(listOf(null, null)))
        assertNull(SheetChart.volumeFractions(listOf(0.0, 10.0, 0.0)))
        assertNull(SheetChart.volumeFractions(emptyList()))
        assertTrue(SheetChart.VOLUME_HEIGHT_FRACTION in 0.1f..0.25f)
    }

    @Test
    fun `volume - kept through accept and scaled on conversion`() {
        val withVolume = series.mapIndexed { i, c -> c.copy(volume = 1_000.0 * (i + 1)) }
        val pair = SheetChart.accept(withVolume, SheetCandleRequest("BTC", "USDT", convert = false), price = 110.0)!!
        assertEquals(listOf(1_000.0, 2_000.0, 3_000.0), pair.map { it.volume })
        // Umrechnung USDT → Quote: gleicher Faktor wie der Kurs (55 / 110 = 0.5)
        val converted = SheetChart.accept(withVolume, SheetCandleRequest("BTC", "USDT", convert = true), price = 55.0, currentRate = 0.5)!!
        assertEquals(listOf(500.0, 1_000.0, 1_500.0), converted.map { it.volume })
        // Ohne Umsatz bleibt er unbekannt
        val plain = SheetChart.accept(series, SheetCandleRequest("BTC", "USDT", convert = true), price = 55.0, currentRate = 0.5)!!
        assertTrue(plain.all { it.volume == null })
    }

    // --- Tageskurse, Zwischenspeicher, Achse, Beschriftung, Volumen (Runde Chart v2) ---

    private val day = 86_400_000L

    /** Kerze am Tag [d] (UTC) zur Stunde [h], Schluss = Eröffnung = [price]. */
    private fun dayCandle(d: Long, h: Int, price: Double, volume: Double? = null, approx: Boolean = false) =
        WidgetCandle(d * day + h * 3_600_000L, price, price, price, price, volume, approx)

    @Test
    fun `fx - each candle with the rate of its day, gaps take the previous day`() {
        val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
        // Tag 10 (Fr) 0.9, Tag 13 (Mo) 0.8; Tage 11 und 12 (Wochenende) fehlen
        val rates = mapOf(10L to 0.9, 13L to 0.8)
        val candles = listOf(
            dayCandle(9, 0, 100.0),   // vor dem ersten Kurs → erster Kurs (0.9)
            dayCandle(10, 5, 100.0),  // 0.9
            dayCandle(10, 23, 100.0), // gleicher Tag, Stundenkerze → 0.9
            dayCandle(12, 12, 100.0), // Wochenende → Freitag (0.9)
            dayCandle(13, 1, 100.0),  // 0.8
        )
        // Kurs des Paars = umgerechneter Schluss: Tageskurse allein (Faktor 1)
        val plain = SheetChart.accept(candles, fallback, price = 80.0, dailyRates = rates)!!
        assertEquals(listOf(90.0, 90.0, 90.0, 90.0, 80.0), plain.map { it.close })
        // Ohne Kurs des Paars ist der gleiche Token nicht prüfbar: kein Chart
        assertNull(SheetChart.accept(candles, fallback, price = null, dailyRates = rates))
        // Umgerechnet 80, Paar bei 120 (+50 %): anderer Token
        assertNull(SheetChart.accept(candles, fallback, price = 120.0, dailyRates = rates))
        // Mit Kurs: gemeinsamer Faktor, letzter Schluss = Kurs des Paars, Tagesverhältnis bleibt
        val anchored = SheetChart.accept(candles, fallback, price = 84.0, dailyRates = rates)!!
        assertEquals(84.0, anchored.last().close, 1e-9)
        assertEquals(94.5, anchored.first().close, 1e-9)
        assertEquals(SheetChartFx.DAILY, SheetChart.fxMode(fallback, rates))
    }

    @Test
    fun `fx - without daily rates the current factor, flagged`() {
        val fallback = SheetCandleRequest("BTC", "USDT", convert = true)
        val candles = listOf(dayCandle(1, 0, 100.0), dayCandle(2, 0, 110.0))
        val converted = SheetChart.accept(candles, fallback, price = 55.0, dailyRates = emptyMap(), currentRate = 0.5)!!
        assertEquals(listOf(50.0, 55.0), converted.map { it.close })
        // Weder Tages- noch aktueller Devisenkurs: nicht prüfbar, kein Chart
        assertNull(SheetChart.accept(candles, fallback, price = 55.0, dailyRates = emptyMap()))
        // Ungültige Kurse zählen nicht
        assertEquals(SheetChartFx.CURRENT, SheetChart.fxMode(fallback, mapOf(1L to 0.0, 2L to Double.NaN)))
        assertEquals(SheetChartFx.CURRENT, SheetChart.fxMode(fallback, null))
        assertEquals(SheetChartFx.NONE, SheetChart.fxMode(SheetCandleRequest("BTC", "CHF", convert = false), mapOf(1L to 0.9)))
        // Weder Tageskurse noch Kurs: nicht umrechenbar
        assertNull(SheetChart.accept(candles, fallback, price = null, dailyRates = null))
        assertEquals(19L, SheetChart.utcEpochDay(19 * day + 5))
        assertEquals(-1L, SheetChart.utcEpochDay(-5))
    }

    @Test
    fun `cache - dropped on manual refresh and on a price jump`() {
        val now = 1_000_000L
        val cache = SheetChartCache { now }
        val ready = SheetChartResult.Ready(series, converted = false)
        cache.put("BTC", "USDT", SheetChartRange.DAY, ready, price = 100.0)
        // Bis 3 % Abweichung vom Kurs beim Speichern: gültig
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.DAY, price = 102.9))
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.DAY, price = null))
        // Mehr als 3 %: verworfen (auch danach weg)
        assertNull(cache.get("BTC", "USDT", SheetChartRange.DAY, price = 96.9))
        assertNull(cache.get("BTC", "USDT", SheetChartRange.DAY, price = 100.0))
        // Ohne Kurs beim Speichern: Bezug ist der letzte Schluss (110)
        cache.put("BTC", "USDT", SheetChartRange.DAY, ready)
        assertSame(ready, cache.get("BTC", "USDT", SheetChartRange.DAY, price = 112.0))
        assertNull(cache.get("BTC", "USDT", SheetChartRange.DAY, price = 114.0))
        // Von Hand: alle Zeiträume des Paars, andere Paare bleiben
        cache.put("BTC", "USDT", SheetChartRange.DAY, ready)
        cache.put("btc", "usdt", SheetChartRange.YEAR, ready)
        cache.put("ETH", "USDT", SheetChartRange.DAY, ready)
        cache.invalidate("BTC", "USDT")
        assertNull(cache.get("BTC", "USDT", SheetChartRange.DAY))
        assertNull(cache.get("BTC", "USDT", SheetChartRange.YEAR))
        assertSame(ready, cache.get("ETH", "USDT", SheetChartRange.DAY))
        cache.invalidateAll()
        assertNull(cache.get("ETH", "USDT", SheetChartRange.DAY))
    }

    @Test
    fun `price jump rule`() {
        assertFalse(SheetChart.priceJumped(100.0, 102.99))
        assertTrue(SheetChart.priceJumped(100.0, 103.01))
        assertTrue(SheetChart.priceJumped(100.0, 96.0))
        assertFalse(SheetChart.priceJumped(null, 50.0))
        assertFalse(SheetChart.priceJumped(100.0, null))
        assertFalse(SheetChart.priceJumped(100.0, 0.0))
    }

    @Test
    fun `scrub index - clamped at both edges, also for a year of candles`() {
        val slot = 300f / 365f
        assertEquals(0, SheetChart.scrubIndex(0f, 0f, slot, 365))
        assertEquals(0, SheetChart.scrubIndex(-0.01f, 0f, slot, 365))
        assertEquals(364, SheetChart.scrubIndex(300f, 0f, slot, 365))
        assertEquals(364, SheetChart.scrubIndex(Float.POSITIVE_INFINITY, 0f, slot, 365))
        assertEquals(2, SheetChart.scrubIndex(30f, 0f, 10f, 3))
    }

    @Test
    fun `labels per range - time skeletons and stamp`() {
        // 24h: Uhrzeit; 7T (4-h-Kerzen): Tag und Uhrzeit; 30T: Tag; 1J: Datum
        assertEquals("EEEHm", SheetChartRange.DAY.timeSkeleton(is24 = true))
        assertEquals("EEEhm", SheetChartRange.DAY.timeSkeleton(is24 = false))
        assertEquals("EEEdMMMHm", SheetChartRange.WEEK.timeSkeleton(is24 = true))
        assertEquals("EEEdMMM", SheetChartRange.MONTH.timeSkeleton(is24 = true))
        assertEquals("dMMMy", SheetChartRange.YEAR.timeSkeleton(is24 = false))
        // Stand: Abfragezeit, höchstens das Ende der letzten Kerze
        assertEquals(5_000L, SheetChart.stampMillis(lastOpenTime = 1_000L, intervalMillis = 10_000L, fetchedAt = 5_000L))
        assertEquals(11_000L, SheetChart.stampMillis(lastOpenTime = 1_000L, intervalMillis = 10_000L, fetchedAt = 50_000L))
        assertEquals(11_000L, SheetChart.stampMillis(lastOpenTime = 1_000L, intervalMillis = 10_000L, fetchedAt = 0L))
        val utc = java.time.ZoneOffset.UTC
        assertEquals("jm", SheetChart.stampTemplate(10 * day + 1_000, 10 * day + 80_000_000, utc))
        assertEquals("dMMMjm", SheetChart.stampTemplate(9 * day + 80_000_000, 10 * day + 1_000, utc))
    }

    @Test
    fun `axis - minimum span for stablecoins and flat series`() {
        val peg = listOf(candle(0, 1.0001, 0.9998), candle(1, 0.9998, 1.0002))
        val stable = SheetChart.axisLevels(peg, WidgetChartType.CANDLES, stable = true)
        assertEquals(1.0, stable.mid, 1e-9)
        assertEquals(0.995, stable.low, 1e-9)
        assertEquals(1.005, stable.high, 1e-9)
        // Kein Stablecoin: nur ±0.1 %
        val other = SheetChart.axisLevels(peg, WidgetChartType.CANDLES, stable = false)
        assertEquals(0.999, other.low, 1e-9)
        assertEquals(1.001, other.high, 1e-9)
        // Normale Spanne bleibt unverändert
        assertEquals(
            com.cryptochecker.app.widget.WidgetChartGeometry.levels(series, WidgetChartType.CANDLES),
            SheetChart.axisLevels(series, WidgetChartType.CANDLES, stable = true),
        )
    }

    @Test
    fun `volume approximated flag - passed through accept and conversion`() {
        assertTrue(SheetChart.isVolumeApproximated(null))
        assertTrue(SheetChart.isVolumeApproximated(Double.NaN))
        assertFalse(SheetChart.isVolumeApproximated(12.0))
        val candles = listOf(dayCandle(1, 0, 100.0, 1_000.0, approx = true), dayCandle(2, 0, 110.0, 2_000.0))
        val pair = SheetChart.accept(candles, SheetCandleRequest("BTC", "USDT", convert = false), price = 110.0)!!
        assertEquals(listOf(true, false), pair.map { it.volumeApproximated })
        val daily = SheetChart.accept(candles, SheetCandleRequest("BTC", "USDT", convert = true), price = 55.0,
            dailyRates = mapOf(1L to 0.5))!!
        assertEquals(listOf(true, false), daily.map { it.volumeApproximated })
        assertEquals(listOf(500.0, 1_000.0), daily.map { it.volume })
        val current = SheetChart.accept(candles, SheetCandleRequest("BTC", "USDT", convert = true), price = 55.0, currentRate = 0.5)!!
        assertEquals(listOf(true, false), current.map { it.volumeApproximated })
    }
}
