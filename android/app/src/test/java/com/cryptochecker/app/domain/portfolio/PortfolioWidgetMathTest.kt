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
        assertEquals(PortfolioWidgetSize.SMALL, PortfolioWidgetMath.size(150, 110))
        assertEquals(PortfolioWidgetSize.SMALL, PortfolioWidgetMath.size(300, 80))
        // Schmal und hoch (2 × 3): Wertverlauf unter den Werten
        assertEquals(PortfolioWidgetSize.TALL, PortfolioWidgetMath.size(150, 260))
        assertEquals(PortfolioWidgetSize.TALL, PortfolioWidgetMath.size(249, 400))
        // Breit (4 × 2): nebeneinander
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(300, 110))
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(250, 180))
        // Breit und hoch: gross
        assertEquals(PortfolioWidgetSize.LARGE, PortfolioWidgetMath.size(300, 260))
        // Grosse Schrift braucht mehr Höhe für dieselbe Stufe
        assertEquals(PortfolioWidgetSize.MEDIUM, PortfolioWidgetMath.size(300, 250, 1.3f))
        // Schwellen genau
        val tall = PortfolioWidgetMath.baseHeightDp(1f) + PortfolioWidgetMath.changeLineHeightDp(1f) +
            PortfolioWidgetMath.footerHeightDp(1f) + PortfolioWidgetMath.CHART_MIN_HEIGHT_DP + 6f
        assertEquals(PortfolioWidgetSize.TALL, PortfolioWidgetMath.size(150, kotlin.math.ceil(tall).toInt()))
        assertEquals(PortfolioWidgetSize.SMALL, PortfolioWidgetMath.size(150, kotlin.math.floor(tall).toInt() - 1))
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

    @Test
    fun layoutFollowsPriorityAndNeverOverflows() {
        // Unbekannte Höhe: alles Gewünschte
        assertEquals(
            PortfolioWidgetLayout(PortfolioWidgetSize.SMALL, true, true, true, 0),
            PortfolioWidgetMath.layout(0, 0, 1f, hasChange = true, wantUsdt = true, positions = 5),
        )
        // Nicht gewünscht oder unbekannt = nie gezeigt
        val none = PortfolioWidgetMath.layout(150, 400, 1f, hasChange = false, wantUsdt = false, positions = 5)
        assertFalse(none.changeLine)
        assertFalse(none.usdt)
        assertTrue(none.footer)
        // Gross: höchstens drei Zeilen, weniger bei weniger Positionen
        assertEquals(3, PortfolioWidgetMath.layout(320, 400, 1f, true, true, 5).rows)
        assertEquals(2, PortfolioWidgetMath.layout(320, 400, 1f, true, true, 2).rows)
        assertEquals(0, PortfolioWidgetMath.layout(320, 140, 1f, true, true, 5).rows)
        // Vorrang Betrag > Fusszeile > ≈ USDT, und was gezeigt wird, passt
        for (fs in listOf(1f, 1.3f)) {
            for (h in 60..420 step 3) {
                for (w in listOf(150, 320)) {
                    val l = PortfolioWidgetMath.layout(w, h, fs, hasChange = true, wantUsdt = true, positions = 5)
                    if (l.usdt) assertTrue(l.footer)
                    if (l.footer) assertTrue(l.changeLine)
                    val column = l.size == PortfolioWidgetSize.TALL || l.size == PortfolioWidgetSize.LARGE
                    val used = PortfolioWidgetMath.baseHeightDp(fs) +
                        (if (l.changeLine) PortfolioWidgetMath.changeLineHeightDp(fs) else 0f) +
                        (if (l.usdt) PortfolioWidgetMath.usdtHeightDp(fs) else 0f) +
                        (if (l.footer) PortfolioWidgetMath.footerHeightDp(fs) else 0f) +
                        PortfolioWidgetMath.rowsHeightDp(fs, l.rows) +
                        (if (column) PortfolioWidgetMath.CHART_MIN_HEIGHT_DP + 6f else 0f)
                    if (l.changeLine) assertTrue("h=$h w=$w fs=$fs", used <= h + 0.01f)
                }
            }
        }
    }

    @Test
    fun chartAreaPerSize() {
        val small = PortfolioWidgetMath.layout(150, 100, 1f, true, true, 3)
        assertEquals(0f to 0f, PortfolioWidgetMath.chartSizeDp(small, 150, 100, 1f))
        // Mittel: rechte Hälfte unter der Kopfzeile
        val medium = PortfolioWidgetMath.layout(300, 140, 1f, true, true, 3)
        assertEquals(PortfolioWidgetSize.MEDIUM, medium.size)
        val (mw, mh) = PortfolioWidgetMath.chartSizeDp(medium, 300, 140, 1f)
        assertEquals((300f - 24f - 12f) / 2f, mw, 0.01f)
        assertEquals(140f - 24f - PortfolioWidgetMath.headerHeightDp(1f) - 4f, mh, 0.01f)
        // Schmal-hoch: volle Breite, mindestens die Mindesthöhe, wächst mit der Höhe
        val t1 = PortfolioWidgetMath.layout(150, 220, 1f, true, true, 3)
        val t2 = PortfolioWidgetMath.layout(150, 320, 1f, true, true, 3)
        val h1 = PortfolioWidgetMath.chartSizeDp(t1, 150, 220, 1f).second
        val h2 = PortfolioWidgetMath.chartSizeDp(t2, 150, 320, 1f).second
        assertTrue(h1 >= PortfolioWidgetMath.CHART_MIN_HEIGHT_DP)
        assertEquals(100f, h2 - h1, 0.01f)
        assertEquals(126f, PortfolioWidgetMath.chartSizeDp(t1, 150, 220, 1f).first, 0.01f)
    }

    @Test
    fun titleOnlyWhenItFitsBesideThePill() {
        assertTrue(PortfolioWidgetMath.showsTitle(150, 60f, null))
        assertTrue(PortfolioWidgetMath.showsTitle(0, 60f, 50f))
        assertTrue(PortfolioWidgetMath.showsTitle(300, 60f, 50f))
        // 60 + 50 + 66 fest + 2 Reserve = 178
        assertTrue(PortfolioWidgetMath.showsTitle(178, 60f, 50f))
        assertFalse(PortfolioWidgetMath.showsTitle(177, 60f, 50f))
    }

    @Test
    fun valueYMatchesLineScale() {
        val points = listOf(PortfolioValuePoint(0L, 10.0), PortfolioValuePoint(10L, 20.0))
        assertEquals(45f, PortfolioWidgetMath.valueY(points, 10.0, 50f, 5f)!!, 1e-4f)
        assertEquals(25f, PortfolioWidgetMath.valueY(points, 15.0, 50f, 5f)!!, 1e-4f)
        // Ausserhalb geklemmt; ohne Linie nichts
        assertEquals(5f, PortfolioWidgetMath.valueY(points, 99.0, 50f, 5f)!!, 1e-4f)
        assertNull(PortfolioWidgetMath.valueY(points.take(1), 10.0, 50f, 5f))
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
    fun linePointsOnFixedDayAxis() {
        // Tag 0…100, bisher bis 40: die Linie füllt die ersten 40 % der Breite
        val points = listOf(PortfolioValuePoint(0L, 10.0), PortfolioValuePoint(40L, 20.0))
        val xy = PortfolioWidgetMath.linePoints(points, 100f, 50f, 5f, from = 0L, to = 100L)
        assertEquals(0f, xy[0].first, 1e-4f)
        assertEquals(40f, xy[1].first, 1e-4f)
        // Erster Wert erst nach Tagesbeginn: beginnt weiter rechts; ausserhalb wird geklemmt
        val late = PortfolioWidgetMath.linePoints(listOf(PortfolioValuePoint(20L, 1.0), PortfolioValuePoint(120L, 2.0)), 100f, 50f, 5f, 0L, 100L)
        assertEquals(20f, late[0].first, 1e-4f)
        assertEquals(100f, late[1].first, 1e-4f)
        // Ungültige Achse: wie ohne
        assertEquals(100f, PortfolioWidgetMath.linePoints(points, 100f, 50f, 5f, from = 50L, to = 50L)[1].first, 1e-4f)
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
