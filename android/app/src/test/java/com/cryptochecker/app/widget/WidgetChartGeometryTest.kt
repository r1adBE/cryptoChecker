package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class WidgetChartGeometryTest {

    private val hour = 3_600_000L
    private val zurich = ZoneId.of("Europe/Zurich")

    private fun candle(t: Long, o: Double, h: Double, l: Double, c: Double) = WidgetCandle(t, o, h, l, c)

    private fun series(start: Long, step: Long, n: Int) = (0 until n).map { i ->
        val o = 100.0 + i
        candle(start + i * step, o, o + 3, o - 2, o + 1)
    }

    private fun geometry(
        candles: List<WidgetCandle>,
        type: WidgetChartType = WidgetChartType.CANDLES,
        height: Float = 200f,
        compact: Boolean = false,
        currentPrice: Double? = null,
    ) = WidgetChartGeometry(
        candles = candles, type = type, gridUnit = ChartGridUnit.HOUR, intervalMillis = hour,
        zone = ZoneOffset.UTC, width = 300f, height = height, labelColumnWidth = 60f,
        labelHeight = 12f, tagHeight = 16f, compact = compact, currentPrice = currentPrice,
    )

    @Test
    fun `Stufen aus Hoch und Tief bei Kerzen, aus Schlusskursen bei der Linie`() {
        val c = listOf(candle(0, 10.0, 15.0, 8.0, 12.0), candle(hour, 12.0, 20.0, 11.0, 13.0))
        assertEquals(ChartLevels(8.0, 14.0, 20.0), WidgetChartGeometry.levels(c, WidgetChartType.CANDLES))
        assertEquals(ChartLevels(12.0, 12.5, 13.0), WidgetChartGeometry.levels(c, WidgetChartType.LINE))
    }

    @Test
    fun `Kerze steigt bei Schluss gleich oder über Eröffnung`() {
        assertTrue(candle(0, 10.0, 11.0, 9.0, 10.0).up)
        assertTrue(candle(0, 10.0, 11.0, 9.0, 10.5).up)
        assertFalse(candle(0, 10.0, 11.0, 9.0, 9.99).up)
    }

    @Test
    fun `Körper mindestens 1 px und Breite 65 Prozent des Fensters`() {
        val c = listOf(candle(0, 10.0, 12.0, 8.0, 10.0), candle(hour, 10.0, 12.0, 8.0, 11.0))
        val g = geometry(c)
        val doji = g.candleShapes[0]
        assertEquals(1f, doji.bodyBottom - doji.bodyTop, 1e-4f)
        assertEquals(g.slotWidth * 0.65f, doji.bodyWidth, 1e-4f)
        assertEquals(g.y(12.0), doji.wickTop, 1e-4f)
        assertEquals(g.y(8.0), doji.wickBottom, 1e-4f)
        assertEquals(g.plotTop, g.y(12.0), 1e-4f)
        assertEquals(g.plotBottom, g.y(8.0), 1e-4f)
    }

    @Test
    fun `24 h - Gitter an jeder Stundengrenze zwischen den Kerzen`() {
        val start = 1_760_000_400_000L - (1_760_000_400_000L % hour)
        val c = series(start, hour, 24)
        val times = WidgetChartGeometry.gridTimes(c, ChartGridUnit.HOUR, hour, zurich)
        assertEquals(23, times.size)
        assertEquals(c.drop(1).map { it.openTime }, times)
        // x liegt genau zwischen zwei Kerzen
        val g = geometry(c)
        assertEquals(23, g.gridXs.size)
        assertEquals(g.slotWidth, g.gridXs.first(), 1e-3f)
    }

    @Test
    fun `7 Tage - eine Linie je lokale Mitternacht`() {
        // 42 × 4 h ab Mo 6.10.2025 00:00 UTC (Zürich UTC+2 → Mitternacht = 22:00 UTC)
        val start = LocalDateTime.of(2025, 10, 6, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val c = series(start, 4 * hour, 42)
        val times = WidgetChartGeometry.gridTimes(c, ChartGridUnit.DAY, 4 * hour, zurich)
        assertEquals(7, times.size)
        val first = LocalDateTime.of(2025, 10, 6, 22, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(first, times.first())
        times.zipWithNext().forEach { (a, b) -> assertEquals(24 * hour, b - a) }
    }

    @Test
    fun `7 Tage - Mitternacht über die Zeitumstellung bleibt lokal`() {
        // 26.10.2025: Ende der Sommerzeit in Zürich, der Tag hat 25 Stunden
        val start = LocalDateTime.of(2025, 10, 24, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val c = series(start, 4 * hour, 42)
        val times = WidgetChartGeometry.gridTimes(c, ChartGridUnit.DAY, 4 * hour, zurich)
        assertTrue(times.zipWithNext().any { (a, b) -> b - a == 25 * hour })
        times.forEach {
            val local = java.time.Instant.ofEpochMilli(it).atZone(zurich)
            assertEquals(0, local.hour)
            assertEquals(0, local.minute)
        }
    }

    @Test
    fun `30 Tage - eine Linie je lokaler Montag`() {
        val start = LocalDateTime.of(2025, 9, 7, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() // Sonntag
        val c = series(start, 24 * hour, 30)
        val times = WidgetChartGeometry.gridTimes(c, ChartGridUnit.WEEK, 24 * hour, zurich)
        assertEquals(5, times.size) // 8., 15., 22., 29. Sept., 6. Okt.
        times.forEach {
            val local = java.time.Instant.ofEpochMilli(it).atZone(zurich)
            assertEquals(java.time.DayOfWeek.MONDAY, local.dayOfWeek)
            assertEquals(0, local.hour)
        }
    }

    @Test
    fun `Keine Gitterlinie am Rand der Zeichenfläche`() {
        val start = LocalDateTime.of(2025, 9, 8, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() // Montag 00:00 UTC
        val c = series(start, 24 * hour, 30)
        val times = WidgetChartGeometry.gridTimes(c, ChartGridUnit.WEEK, 24 * hour, ZoneOffset.UTC)
        assertFalse(times.contains(start))
        assertTrue(times.all { it > start && it < c.last().openTime + 24 * hour })
    }

    @Test
    fun `Etikett verdrängt die überlappende Beschriftung`() {
        val labels = listOf(
            PlacedLabel(LevelKind.HIGH, 3.0, 10f),
            PlacedLabel(LevelKind.LOW, 1.0, 190f),
            PlacedLabel(LevelKind.MID, 2.0, 100f),
        )
        val shown = WidgetChartGeometry.resolveLabels(labels, 12f, VSpan(2f, 18f))
        assertEquals(listOf(LevelKind.LOW, LevelKind.MID), shown.map { it.kind })
        val all = WidgetChartGeometry.resolveLabels(labels, 12f, VSpan(40f, 56f))
        assertEquals(3, all.size)
    }

    @Test
    fun `Beschriftungen überlappen sich nie gegenseitig`() {
        val labels = listOf(
            PlacedLabel(LevelKind.HIGH, 3.0, 10f),
            PlacedLabel(LevelKind.LOW, 1.0, 30f),
            PlacedLabel(LevelKind.MID, 2.0, 20f),
        )
        val shown = WidgetChartGeometry.resolveLabels(labels, 12f, null, gap = 1f)
        assertEquals(listOf(LevelKind.HIGH, LevelKind.LOW), shown.map { it.kind })
    }

    @Test
    fun `Kleine Fläche - nur Hoch und Tief`() {
        val c = series(0, hour, 24).map { it.copy(close = it.open - 0.5) } // letzter Schluss mitten im Bereich
        val g = geometry(c, compact = true)
        assertFalse(g.labels.any { it.kind == LevelKind.MID })
        assertTrue(WidgetChartGeometry.isCompact(200f, 79f))
        assertTrue(WidgetChartGeometry.isCompact(120f, 120f))
        assertFalse(WidgetChartGeometry.isCompact(200f, 100f))
    }

    @Test
    fun `Etikett auf Höhe des letzten Schlusses, in der Bitmap gehalten`() {
        val c = series(0, hour, 24) // letzter Schluss 124, Hoch 126
        val g = geometry(c)
        assertEquals(g.y(124.0), g.currentY, 1e-4f)
        assertTrue(g.tagSpan.top >= 0f && g.tagSpan.bottom <= g.height)
        // Steigende Reihe: Schluss nahe am Hoch → Hoch-Beschriftung weicht
        assertFalse(g.labels.any { it.kind == LevelKind.HIGH })
        assertTrue(g.labels.any { it.kind == LevelKind.LOW })
    }

    @Test
    fun `Linie - Punkte mittig im Fenster, Richtung nach Schlusskursen`() {
        val c = listOf(candle(0, 10.0, 12.0, 9.0, 11.0), candle(hour, 11.0, 12.0, 9.0, 10.0))
        val g = geometry(c, type = WidgetChartType.LINE)
        assertFalse(g.up)
        assertEquals(g.slotWidth / 2f, g.linePoints.first().first, 1e-4f)
        assertEquals(g.plotTop, g.linePoints.first().second, 1e-4f)
    }

    @Test
    fun `Gleichbleibender Kurs - alles mittig, nur eine Beschriftung`() {
        val c = listOf(candle(0, 5.0, 5.0, 5.0, 5.0), candle(hour, 5.0, 5.0, 5.0, 5.0))
        val g = geometry(c)
        assertEquals((g.plotTop + g.plotBottom) / 2f, g.y(5.0), 1e-4f)
        assertTrue(g.labels.size <= 1)
    }

    @Test
    fun `Screenreader-Kennzahlen aus Kerzen bzw Schlusskursen`() {
        val c = listOf(candle(0, 10.0, 15.0, 8.0, 12.0), candle(hour, 12.0, 20.0, 11.0, 13.0))
        val s = WidgetChartGeometry.summary(c, WidgetChartType.CANDLES)!!
        assertEquals(10.0, s.start, 0.0)
        assertEquals(13.0, s.end, 0.0)
        assertEquals(20.0, s.high, 0.0)
        assertEquals(8.0, s.low, 0.0)
        val l = WidgetChartGeometry.summary(c, WidgetChartType.LINE)!!
        assertEquals(12.0, l.start, 0.0)
        assertEquals(13.0, l.high, 0.0)
    }

    @Test
    fun `Etikett zeigt den aktuellen Kurs auf seiner Höhe`() {
        val c = series(0, hour, 24) // Tief 98, Hoch 126, letzter Schluss 124
        val g = geometry(c, currentPrice = 110.0)
        assertEquals(110.0, g.currentPrice, 0.0)
        assertEquals(g.y(110.0), g.currentY, 1e-4f)
        // ohne Kurs (oder ≤ 0): letzter Schluss
        assertEquals(124.0, geometry(c, currentPrice = 0.0).currentPrice, 0.0)
        assertEquals(124.0, geometry(c).currentPrice, 0.0)
    }

    @Test
    fun `Kurs ausserhalb von Tief bis Hoch - Etikett am Rand, Beschriftung weicht`() {
        val c = series(0, hour, 24)
        val above = geometry(c, currentPrice = 500.0)
        assertEquals(500.0, above.currentPrice, 0.0)
        assertEquals(above.plotTop, above.currentY, 1e-4f)
        assertTrue(above.tagSpan.top >= 0f)
        assertFalse(above.labels.any { it.kind == LevelKind.HIGH })
        val below = geometry(c, currentPrice = 1.0)
        assertEquals(below.plotBottom, below.currentY, 1e-4f)
        assertTrue(below.tagSpan.bottom <= below.height)
        assertFalse(below.labels.any { it.kind == LevelKind.LOW })
        assertTrue(below.labels.any { it.kind == LevelKind.HIGH })
    }
}
