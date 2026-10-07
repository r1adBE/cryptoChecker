package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PortfolioWidgetMathTest {

    private val h = 60 * 60_000L
    private val now = 1_000L * h

    @Test
    fun sizeBuckets() {
        // Unbekannt und flach: klein
        assertEquals(PortfolioWidgetSize.SMALL, PortfolioWidgetMath.size(0, 0))
        assertEquals(PortfolioWidgetSize.SMALL, PortfolioWidgetMath.size(180, 109))
        // 2 × 2 (z. B. 150 × 160 dp): mittel
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(150, 110))
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(150, 220))
        // Breit genug und 180 hoch: gross; schmal braucht 250 Höhe
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(249, 200))
        assertEquals(PortfolioWidgetSize.LARGE, PortfolioWidgetMath.size(250, 180))
        // Schmal und hoch bleibt mittel (Liste hätte keinen Platz in der Breite)
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(150, 250))
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(180, 400))
    }

    @Test
    fun usdtLineOnlyForNonUsdCurrencies() {
        assertTrue(PortfolioWidgetMath.showsUsdt(true, "CHF"))
        assertFalse(PortfolioWidgetMath.showsUsdt(false, "CHF"))
        assertFalse(PortfolioWidgetMath.showsUsdt(true, "USD"))
        assertFalse(PortfolioWidgetMath.showsUsdt(true, "usdt"))
        assertFalse(PortfolioWidgetMath.showsUsdt(true, "USDC"))
    }

    @Test
    fun topPositionsSortedWithShareAndRest() {
        val values = mapOf(
            "BTC" to 600.0,
            "ETH" to 200.0,
            "SOL" to 100.0,
            "ADA" to 50.0,
            "DOT" to 30.0,
            "XRP" to 20.0,
            "NOPRICE" to null,
        )
        val top = PortfolioWidgetMath.topPositions(values, 1_000.0, 2.0, mapOf("BTC" to 1.5))
        assertEquals(listOf("BTC", "ETH", "SOL", "ADA", "DOT"), top.positions.map { it.symbol })
        // XRP und der Coin ohne Kurs bleiben übrig
        assertEquals(2, top.others)
        assertEquals(1_200.0, top.positions[0].value, 1e-9)
        assertEquals(60.0, top.positions[0].sharePercent, 1e-9)
        assertEquals(1.5, top.positions[0].change24hPercent!!, 1e-9)
        assertNull(top.positions[1].change24hPercent)
    }

    @Test
    fun equalValuesSortBySymbol() {
        val top = PortfolioWidgetMath.topPositions(mapOf("ZZZ" to 1.0, "AAA" to 1.0), 2.0, 1.0, emptyMap())
        assertEquals(listOf("AAA", "ZZZ"), top.positions.map { it.symbol })
        assertEquals(0, top.others)
    }

    @Test
    fun shareWithoutTotalIsZero() {
        assertEquals(0.0, PortfolioWidgetMath.sharePercent(10.0, 0.0), 0.0)
        assertEquals(100.0, PortfolioWidgetMath.sharePercent(10.0, 5.0), 0.0)
        assertEquals("62.3%", PortfolioWidgetMath.shareText(62.34, Locale.US))
        assertEquals("62,3%", PortfolioWidgetMath.shareText(62.34, Locale.GERMANY))
    }

    @Test
    fun coinChangesAgainstBaseline() {
        val changes = PortfolioWidgetMath.coinChanges(
            current = mapOf("BTC" to 110.0, "ETH" to 50.0, "NEW" to 1.0),
            baseline = mapOf("BTC" to 100.0, "ETH" to 100.0),
        )
        assertEquals(10.0, changes.getValue("BTC"), 1e-9)
        assertEquals(-50.0, changes.getValue("ETH"), 1e-9)
        assertFalse("NEW" in changes)
        assertTrue(PortfolioWidgetMath.coinChanges(mapOf("BTC" to 1.0), null).isEmpty())
    }

    private fun positions(n: Int) = (1..n).map { PortfolioPosition("C$it", 1.0, 1.0, null) }

    @Test
    fun rowsFitWithMoreLine() {
        // Alles passt: keine «weitere»-Zeile
        assertEquals(PositionRows(positions(3), 0), PortfolioWidgetMath.rows(TopPositions(positions(3), 0), 6))
        // 5 + 2 weitere, 6 Zeilen: 5 Positionen und «+ 2 weitere»
        val r1 = PortfolioWidgetMath.rows(TopPositions(positions(5), 2), 6)
        assertEquals(5, r1.shown.size)
        assertEquals(2, r1.more)
        // 5 Positionen, aber nur 3 Zeilen: 2 Positionen und «+ 3 weitere»
        val r2 = PortfolioWidgetMath.rows(TopPositions(positions(5), 0), 3)
        assertEquals(2, r2.shown.size)
        assertEquals(3, r2.more)
        // Nur eine Zeile, aber mehrere Positionen: keine Liste (nur «weitere» wäre nutzlos)
        assertEquals(PositionRows(emptyList(), 0), PortfolioWidgetMath.rows(TopPositions(positions(3), 0), 1))
        // Eine Position, eine Zeile: passt
        assertEquals(1, PortfolioWidgetMath.rows(TopPositions(positions(1), 0), 1).shown.size)
        assertEquals(PositionRows(emptyList(), 0), PortfolioWidgetMath.rows(TopPositions(positions(2), 0), 0))
    }

    /** Höhe der gezeigten Teile ohne Wertverlauf und Liste. */
    private fun fixed(p: PortfolioWidgetParts, fs: Float): Float =
        PortfolioWidgetMath.baseHeightDp(fs) +
            (if (p.today) PortfolioWidgetMath.todayHeightDp(fs) else 0f) +
            (if (p.usdt) PortfolioWidgetMath.usdtHeightDp(fs) else 0f) +
            (if (p.time) PortfolioWidgetMath.timeHeightDp(fs) else 0f)

    @Test
    fun listLinesGrowWithHeight() {
        fun lines(h: Int, fs: Float = 1f) = PortfolioWidgetMath.parts(h, fs, true, true, true, list = true).listLines
        assertEquals(0, lines(0))
        assertEquals(0, lines(150))
        assertEquals(PortfolioWidgetMath.MAX_POSITIONS + 1, lines(400))
        val mid = lines(250)
        assertTrue(mid in 1..PortfolioWidgetMath.MAX_POSITIONS)
        // Grosse Schrift: weniger Zeilen
        assertTrue(lines(250, 1.3f) <= mid)
        // Ohne Liste (klein/mittel) keine Zeilen
        assertEquals(0, PortfolioWidgetMath.parts(400, 1f, true, true, true, list = false).listLines)
    }

    @Test
    fun partsFollowPriorityAndNeverOverflow() {
        // Unbekannte Höhe: alles Gewünschte, keine Liste
        assertEquals(PortfolioWidgetParts(true, true, true, true, 0), PortfolioWidgetMath.parts(0, 1f, true, true, true, true))
        // Nicht gewünscht = nie gezeigt
        val none = PortfolioWidgetMath.parts(500, 1f, today = false, usdt = false, chart = false, list = false)
        assertEquals(PortfolioWidgetParts(false, false, false, true, 0), none)
        // Reichlich Platz: alles
        assertEquals(PortfolioWidgetParts(true, true, true, true, 0), PortfolioWidgetMath.parts(300, 1f, true, true, true, false))
        for (fs in listOf(0.85f, 1f, 1.3f, 2f)) {
            var previous: PortfolioWidgetParts? = null
            for (h in 60..400) {
                val p = PortfolioWidgetMath.parts(h, fs, true, true, true, true)
                // Was gezeigt wird, passt immer (Wertverlauf mit Mindesthöhe, Liste mit ihren Zeilen)
                val chart = if (p.chart) PortfolioWidgetMath.CHART_MIN_HEIGHT_DP + 6f else 0f
                val list = if (p.listLines > 0) 4f + p.listLines * PortfolioWidgetMath.rowHeightDp(fs) else 0f
                if (h >= PortfolioWidgetMath.baseHeightDp(fs)) assertTrue("h=$h fs=$fs", fixed(p, fs) + chart + list <= h)
                // Gesamtwert, «heute» und ≈ USDT zuerst: gezeigt, sobald zusammen genug Höhe da ist
                if (h >= PortfolioWidgetMath.baseHeightDp(fs) + PortfolioWidgetMath.todayHeightDp(fs) + 0.01f) assertTrue(p.today)
                // Mehr Höhe nimmt «heute» nie weg (kleinere Teile dürfen höheren Platz machen)
                previous?.let { q -> assertTrue(!q.today || p.today) }
                previous = p
            }
        }
    }

    @Test
    fun partsDropLowerPriorityFirst() {
        val fs = 1f
        val base = PortfolioWidgetMath.baseHeightDp(fs)
        val today = PortfolioWidgetMath.todayHeightDp(fs)
        val usdt = PortfolioWidgetMath.usdtHeightDp(fs)
        // Nur Platz für «heute»: ≈ USDT, Verlauf, Uhrzeit entfallen
        val p1 = PortfolioWidgetMath.parts(kotlin.math.ceil(base + today).toInt(), fs, true, true, true, false)
        assertEquals(PortfolioWidgetParts(true, false, false, false, 0), p1)
        // Ohne «heute» (keine Basis) rückt ≈ USDT nach
        val p2 = PortfolioWidgetMath.parts(kotlin.math.ceil(base + usdt).toInt(), fs, false, true, true, false)
        assertEquals(PortfolioWidgetParts(false, true, false, false, 0), p2)
        // Zu niedrig für den Wertverlauf, aber Platz für die Uhrzeit: Uhrzeit ohne Verlauf
        val time = PortfolioWidgetMath.timeHeightDp(fs)
        val p3 = PortfolioWidgetMath.parts((base + today + usdt + time + 1f).toInt(), fs, true, true, true, false)
        assertEquals(PortfolioWidgetParts(true, true, false, true, 0), p3)
    }

    @Test
    fun compactSmallFitsTotalAndUsdtAt92dp() {
        // Klein mit bekannter Höhe: kompakt; mittel, gross und unbekannt nicht
        assertTrue(PortfolioWidgetMath.isCompact(PortfolioWidgetSize.SMALL, 92))
        assertFalse(PortfolioWidgetMath.isCompact(PortfolioWidgetSize.SMALL, 0))
        assertFalse(PortfolioWidgetMath.isCompact(PortfolioWidgetSize.MEDIUM, 160))
        // 2 × 1 (152 × 92 dp): bisher fiel ≈ USDT weg, kompakt passt es (auch mit grösserer Schrift)
        assertFalse(PortfolioWidgetMath.parts(92, 1f, false, usdt = true, chart = false, list = false).usdt)
        for (fs in listOf(0.85f, 1f, 1.15f)) {
            val p = PortfolioWidgetMath.parts(92, fs, false, usdt = true, chart = false, list = false, compact = true)
            assertTrue("fs=$fs", p.usdt)
            val used = PortfolioWidgetMath.compactBaseHeightDp(fs) + PortfolioWidgetMath.usdtHeightDp(fs) +
                (if (p.time) PortfolioWidgetMath.timeHeightDp(fs) else 0f)
            assertTrue("fs=$fs", used <= 92f)
        }
        // Ausgeschaltet bleibt es weg; mehr Höhe bringt die Uhrzeit dazu
        assertFalse(PortfolioWidgetMath.parts(92, 1f, false, usdt = false, chart = false, list = false, compact = true).usdt)
        assertTrue(PortfolioWidgetMath.parts(105, 1f, false, usdt = true, chart = false, list = false, compact = true).time)
        assertTrue(PortfolioWidgetMath.compactBaseHeightDp(1f) < PortfolioWidgetMath.baseHeightDp(1f))
    }

    @Test
    fun todayTextWidth() {
        // 2 × 2 (152 dp): Rand 24, Innenrand der Pille 14
        assertEquals(114f, PortfolioWidgetMath.todayTextWidthDp(152)!!, 0.001f)
        assertNull(PortfolioWidgetMath.todayTextWidthDp(0))
    }

    @Test
    fun chartHeightTakesTheRest() {
        val fs = 1f
        val p = PortfolioWidgetMath.parts(300, fs, true, true, true, list = true)
        val h0 = PortfolioWidgetMath.chartHeightDp(300, fs, p, 0)
        val h2 = PortfolioWidgetMath.chartHeightDp(300, fs, p, 2)
        assertTrue(h0 > h2)
        assertEquals(2 * PortfolioWidgetMath.rowHeightDp(fs) + 4f, h0 - h2, 0.001f)
        assertTrue(PortfolioWidgetMath.chartHeightDp(300, fs, p, p.listLines) >= PortfolioWidgetMath.CHART_MIN_HEIGHT_DP)
        assertEquals(0f, PortfolioWidgetMath.chartHeightDp(0, fs, p, 0), 0f)
    }

    @Test
    fun valueHistoryUsesTodaysHoldings() {
        val history = listOf(
            PriceSample(now - 60 * h, mapOf("BTC" to 1.0)), // zu alt
            PriceSample(now - 10 * h, mapOf("BTC" to 100.0, "ETH" to 10.0)),
            PriceSample(now - 5 * h, mapOf("BTC" to 120.0)), // ETH fehlt: aktueller Kurs
        )
        val points = PortfolioWidgetMath.valueHistory(
            holdings = mapOf("BTC" to 2.0, "ETH" to 1.0, "GONE" to 0.0),
            history = history,
            current = mapOf("BTC" to 150.0, "ETH" to 20.0),
            now = now,
            fxRate = 0.5,
        )
        assertEquals(listOf(now - 10 * h, now - 5 * h, now), points.map { it.time })
        assertEquals(listOf(105.0, 130.0, 160.0), points.map { it.value })
        assertTrue(PortfolioWidgetMath.isUp(points))
        assertTrue(PortfolioWidgetMath.valueHistory(emptyMap(), history, emptyMap(), now, 1.0).isEmpty())
    }

    @Test
    fun linePointsSpanAreaByTime() {
        val points = listOf(
            PortfolioValuePoint(0L, 10.0),
            PortfolioValuePoint(30L, 20.0),
            PortfolioValuePoint(40L, 15.0),
        )
        val xy = PortfolioWidgetMath.linePoints(points, 100f, 50f, 5f)
        assertEquals(0f, xy[0].first, 1e-4f)
        assertEquals(75f, xy[1].first, 1e-4f)
        assertEquals(100f, xy[2].first, 1e-4f)
        // Tiefster Wert unten, höchster oben
        assertEquals(45f, xy[0].second, 1e-4f)
        assertEquals(5f, xy[1].second, 1e-4f)
        assertEquals(25f, xy[2].second, 1e-4f)
        assertFalse(PortfolioWidgetMath.isUp(listOf(PortfolioValuePoint(0, 2.0), PortfolioValuePoint(1, 1.0))))
        // Flach: mittig; ein Punkt: nichts
        assertTrue(PortfolioWidgetMath.linePoints(listOf(PortfolioValuePoint(0, 1.0), PortfolioValuePoint(1, 1.0)), 10f, 20f, 0f).all { it.second == 10f })
        assertTrue(PortfolioWidgetMath.linePoints(points.take(1), 10f, 10f, 0f).isEmpty())
    }

    @Test
    fun snapshotCarriesWidgetDetails() {
        val history = listOf(PriceSample(now - 24 * h, mapOf("BTC" to 100.0, "ETH" to 10.0)))
        val s = PortfolioSnapshotMath.snapshot(
            holdings = mapOf("BTC" to 1.0, "ETH" to 2.0, "DOGE" to 5.0),
            totalUsd = 130.0,
            current = mapOf("BTC" to 110.0, "ETH" to 10.0),
            history = history,
            now = now,
            fxRate = 2.0,
            currency = "CHF",
        )
        assertEquals(130.0, s.totalUsdt!!, 1e-9)
        assertEquals(listOf("BTC", "ETH"), s.positions.map { it.symbol })
        assertEquals(220.0, s.positions[0].value, 1e-9)
        assertEquals(110.0 / 130.0 * 100.0, s.positions[0].sharePercent, 1e-9)
        assertEquals(10.0, s.positions[0].change24hPercent!!, 1e-9)
        assertEquals(0.0, s.positions[1].change24hPercent!!, 1e-9)
        // DOGE ohne Kurs: nicht in der Liste, aber gezählt
        assertEquals(1, s.otherPositions)
        assertEquals(listOf(now - 24 * h, now), s.history.map { it.time })
        assertEquals(listOf(240.0, 260.0), s.history.map { it.value })
    }
}
