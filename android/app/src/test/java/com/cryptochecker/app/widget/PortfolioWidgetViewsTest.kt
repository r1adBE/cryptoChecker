package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ansichten «Übersicht» und «Liste» des Portfolio-Widgets ([PortfolioWidgetView], [PortfolioWidgetViews]). */
class PortfolioWidgetViewsTest {

    private val d = 1e-9

    private fun position(symbol: String, value: Double, change: Double? = null) =
        PortfolioPosition(symbol, value, value / 10.0, change)

    private fun snapshot(
        total: Double = 900.0,
        totalUsdt: Double? = 1000.0,
        change: Double? = 9.0,
        percent: Double? = 1.0,
        periodChanges: Map<Int, PortfolioChange> = emptyMap(),
        unrealized: PortfolioChange? = null,
        todaySkipped: List<String> = emptyList(),
    ) = PortfolioSnapshot(
        total = total,
        changeAmount = change,
        changePercent = percent,
        currency = "CHF",
        time = 1L,
        empty = false,
        totalUsdt = totalUsdt,
        periodChanges = periodChanges,
        unrealized = unrealized,
        todaySkipped = todaySkipped,
    )

    private fun plan(range: PortfolioChartRange, change: Double? = 9.0) = PortfolioChartPlan(
        range = range, requested = range, fallback = false, type = WidgetChartType.LINE, bars = emptyList(),
        gridUnit = ChartGridUnit.HOUR, axis = null, changeAmount = change, changePercent = 1.0,
    )

    // «Heute ohne …»

    @Test
    fun todaySkippedOnlyWhereTodayIsShown() {
        val s = snapshot(todaySkipped = listOf("SOL", "ADA", "SOL", ""))
        assertEquals(listOf("ADA", "SOL"), PortfolioWidgetViews.todaySkipped(s, plan(PortfolioChartRange.DAY), overview = false))
        // 7 T zeigt nicht «Heute»; die Übersicht hat «Heute» immer in den Kennzahlen
        assertEquals(emptyList<String>(), PortfolioWidgetViews.todaySkipped(s, plan(PortfolioChartRange.WEEK), overview = false))
        assertEquals(listOf("ADA", "SOL"), PortfolioWidgetViews.todaySkipped(s, plan(PortfolioChartRange.WEEK), overview = true))
    }

    /** M-3: «7 T ohne …» im Widget wie «Gewinn 7 T» der App. */
    @Test
    fun periodSkippedNamesTheCoinsOfTheVisiblePeriodNumber() {
        val s = snapshot(
            periodChanges = mapOf(
                7 to PortfolioChange(100.0, 2.0, listOf("SOL", "ADA", "SOL", "")),
                30 to PortfolioChange(50.0, 1.0),
            ),
        )
        assertEquals(PortfolioChartRange.WEEK to listOf("ADA", "SOL"), PortfolioWidgetViews.periodSkipped(s, plan(PortfolioChartRange.DAY), overview = true))
        assertEquals(PortfolioChartRange.WEEK to listOf("ADA", "SOL"), PortfolioWidgetViews.periodSkipped(s, plan(PortfolioChartRange.WEEK), overview = false))
        // 30 T ohne fehlende Coins, 24 h ohne Tages-Zahl, Zeitraum ohne Betrag: kein Hinweis
        assertEquals(null, PortfolioWidgetViews.periodSkipped(s, plan(PortfolioChartRange.MONTH), overview = false))
        assertEquals(null, PortfolioWidgetViews.periodSkipped(s, plan(PortfolioChartRange.DAY), overview = false))
        assertEquals(null, PortfolioWidgetViews.periodSkipped(s, plan(PortfolioChartRange.WEEK, null), overview = false))
    }

    @Test
    fun todaySkippedEmptyWithoutTodayNumber() {
        // %-Basis veraltet (Betrag «—») oder keine Coins ausgelassen
        val noChange = snapshot(change = null, todaySkipped = listOf("SOL"))
        assertEquals(emptyList<String>(), PortfolioWidgetViews.todaySkipped(noChange, plan(PortfolioChartRange.DAY, null), overview = true))
        assertEquals(emptyList<String>(), PortfolioWidgetViews.todaySkipped(snapshot(), plan(PortfolioChartRange.DAY), overview = true))
    }

    // Ansicht

    @Test
    fun viewDefaultsToChartForNewAndExistingWidgets() {
        assertEquals(PortfolioWidgetView.CHART, PortfolioWidgetView.DEFAULT)
        assertEquals(PortfolioWidgetView.CHART, PortfolioWidgetView.fromName(null))
        assertEquals(PortfolioWidgetView.CHART, PortfolioWidgetView.fromName("UNKNOWN"))
        assertEquals(PortfolioWidgetView.LIST, PortfolioWidgetView.fromName("LIST"))
        assertEquals(PortfolioWidgetView.OVERVIEW, PortfolioWidgetView.fromName("OVERVIEW"))
    }

    @Test
    fun chartTypeOnlyForChart() {
        assertTrue(PortfolioWidgetView.CHART.usesChartType)
        assertFalse(PortfolioWidgetView.OVERVIEW.usesChartType)
        assertFalse(PortfolioWidgetView.LIST.usesChartType)
    }

    @Test
    fun rangeNotForList() {
        assertTrue(PortfolioWidgetView.CHART.usesRange)
        assertTrue(PortfolioWidgetView.OVERVIEW.usesRange)
        assertFalse(PortfolioWidgetView.LIST.usesRange)
    }

    // Übersicht

    @Test
    fun figuresConvertUsdtValuesWithSnapshotRate() {
        val s = snapshot(
            periodChanges = mapOf(7 to PortfolioChange(100.0, 2.0)),
            unrealized = PortfolioChange(-50.0, -5.0),
        )
        val figures = PortfolioWidgetViews.figures(s)
        assertEquals(listOf(PortfolioFigureKind.TODAY, PortfolioFigureKind.WEEK, PortfolioFigureKind.TOTAL), figures.map { it.kind })
        // Heute schon in der Anzeigewährung
        assertEquals(9.0, figures[0].amount!!, d)
        assertEquals(1.0, figures[0].percent!!, d)
        // 7 T und Gesamt in USDT × 0.9
        assertEquals(90.0, figures[1].amount!!, d)
        assertEquals(2.0, figures[1].percent!!, d)
        assertEquals(-45.0, figures[2].amount!!, d)
        assertEquals(-5.0, figures[2].percent!!, d)
    }

    @Test
    fun figuresInUsdtDisplayKeepUsdtValues() {
        val s = PortfolioWidgetChart.inUsdt(snapshot(periodChanges = mapOf(7 to PortfolioChange(100.0, 2.0))))
        val figures = PortfolioWidgetViews.figures(s)
        assertEquals(10.0, figures[0].amount!!, d)
        assertEquals(100.0, figures[1].amount!!, d)
    }

    @Test
    fun missingFiguresAreUnknown() {
        val figures = PortfolioWidgetViews.figures(snapshot(change = null, percent = 3.0))
        figures.forEach { assertNull(it.amount) }
        // Prozent ohne Betrag gilt nicht (wie die Pille)
        assertNull(figures[0].percent)
        assertNull(figures[1].percent)
        assertNull(figures[2].percent)
    }

    @Test
    fun overviewPrioritisesChangeThenFiguresThenFooter() {
        val fs = 1f
        val base = PortfolioWidgetMath.baseHeightDp(fs)
        val change = PortfolioWidgetMath.changeLineHeightDp(fs)
        val figures = PortfolioWidgetViews.figuresHeightDp(false, fs)
        val footer = PortfolioWidgetMath.footerHeightDp(fs)
        // Nur Wert und Betrag
        val tight = PortfolioWidgetViews.overviewLayout(150, (base + change + 1).toInt(), fs, hasChange = true, wantUsdt = true)
        assertTrue(tight.changeLine)
        assertFalse(tight.figures)
        assertFalse(tight.footer)
        assertFalse(tight.wide)
        // Dazu Kennzahlen und Fusszeile, aber kein ≈ USDT
        val mid = PortfolioWidgetViews.overviewLayout(150, (base + change + figures + footer + 1).toInt(), fs, hasChange = true, wantUsdt = true)
        assertTrue(mid.figures)
        assertTrue(mid.footer)
        assertFalse(mid.usdt)
        assertFalse(mid.figureAmounts)
        // Unbekannte Grösse: alles Gewünschte
        val unknown = PortfolioWidgetViews.overviewLayout(0, 0, fs, hasChange = true, wantUsdt = false)
        assertTrue(unknown.figures && unknown.footer && unknown.changeLine)
        assertFalse(unknown.usdt)
    }

    @Test
    fun wideOverviewShowsAmountsWhenRoom() {
        val l = PortfolioWidgetViews.overviewLayout(320, 400, 1f, hasChange = true, wantUsdt = true)
        assertTrue(l.wide)
        assertTrue(l.figures && l.footer && l.usdt && l.figureAmounts)
    }

    // Liste

    @Test
    fun listSortsByValueThenSymbol() {
        val sorted = PortfolioWidgetViews.sortedByValue(
            listOf(position("ETH", 200.0), position("BTC", 600.0), position("ADA", 200.0), position("BAD", Double.NaN))
        )
        assertEquals(listOf("BTC", "ADA", "ETH"), sorted.map { it.symbol })
    }

    @Test
    fun listRowsKeepFooterOnlyWhenItCostsNoRow() {
        // 100 dp, Zeile 30, Fusszeile 18: mit Fusszeile 2 Zeilen, ohne 3 → ohne Fusszeile
        assertEquals(3 to false, PortfolioWidgetViews.listRows(100f, 30f, 18f, 5))
        // Nur zwei Positionen: Fusszeile kostet nichts
        assertEquals(2 to true, PortfolioWidgetViews.listRows(100f, 30f, 18f, 2))
        // 110 dp: 3 Zeilen auch mit Fusszeile
        assertEquals(3 to true, PortfolioWidgetViews.listRows(110f, 30f, 18f, 5))
        // Unbekannt: höchstens drei Zeilen
        assertEquals(3 to true, PortfolioWidgetViews.listRows(null, 30f, 18f, 7))
        assertEquals(0 to true, PortfolioWidgetViews.listRows(50f, 30f, 18f, 0))
        assertEquals(0 to false, PortfolioWidgetViews.listRows(10f, 30f, 18f, 4))
    }

    @Test
    fun listLayoutCountsHiddenAndOtherPositions() {
        val positions = (1..8).map { position("C$it", 1000.0 - it) }
        val fs = 1f
        val row = PortfolioWidgetViews.rowHeightDp(true, fs)
        val fixed = 24f + PortfolioWidgetMath.headerHeightDp(fs) + PortfolioWidgetViews.LIST_GAP_DP +
            PortfolioWidgetViews.listTotalHeightDp(fs, wide = true) + PortfolioWidgetMath.footerHeightDp(fs)
        val height = (fixed + 4 * row + 1).toInt()
        val l = PortfolioWidgetViews.listLayout(positions, others = 2, widthDp = 320, heightDp = height, fontScale = fs)
        assertTrue(l.wide)
        assertTrue(l.footer)
        assertEquals(listOf("C1", "C2", "C3", "C4"), l.shown.map { it.symbol })
        // 4 nicht gezeigte + 2 nicht gespeicherte
        assertEquals(6, l.more)
        val narrow = PortfolioWidgetViews.listLayout(positions, 0, widthDp = 150, heightDp = height, fontScale = fs)
        assertFalse(narrow.wide)
        assertTrue(narrow.shown.size < 4)
    }

    @Test
    fun listTotalFooterHasPriorityOverRows() {
        val positions = (1..8).map { position("C$it", 1000.0 - it) }
        val fs = 1f
        val base = 24f + PortfolioWidgetMath.headerHeightDp(fs) + PortfolioWidgetViews.LIST_GAP_DP
        // Platz für Kopf und Total-Fusszeile, aber für keine Zeile: keine Zeilen, alle als «weitere»
        val tight = PortfolioWidgetViews.listLayout(positions, 0, 320, (base + PortfolioWidgetViews.listTotalHeightDp(fs, wide = true) + 2f).toInt(), fs)
        assertTrue(tight.shown.isEmpty())
        assertEquals(8, tight.more)
        // Noch kleiner (Total passt nicht ganz): weiterhin keine Zeilen, nie negativ
        val tiny = PortfolioWidgetViews.listLayout(positions, 0, 320, base.toInt(), fs)
        assertTrue(tiny.shown.isEmpty())
        // Eine Zeile mehr Höhe als nötig ohne Total kostet mit Total genau diese Zeile
        val row = PortfolioWidgetViews.rowHeightDp(true, fs)
        val withoutTotal = (base + 2 * row + 1f).toInt()
        assertTrue(PortfolioWidgetViews.listLayout(positions, 0, 320, withoutTotal, fs).shown.size < 2)
        // «Stand …» bleibt immer: auch knapp unter Platz für Zeile + Fusszeile gibt es keine Zeile statt der Fusszeile
        val footer = PortfolioWidgetMath.footerHeightDp(fs)
        val total = PortfolioWidgetViews.listTotalHeightDp(fs, wide = true)
        val almost = PortfolioWidgetViews.listLayout(positions, 0, 320, (base + total + row + footer - 2f).toInt(), fs)
        assertTrue(almost.footer)
        assertTrue(almost.shown.isEmpty())
        val one = PortfolioWidgetViews.listLayout(positions, 0, 320, (base + total + row + footer + 1f).toInt(), fs)
        assertTrue(one.footer)
        assertEquals(1, one.shown.size)
        assertTrue(tight.footer && tiny.footer)
    }

    @Test
    fun listTotalChangeIsTheSnapshotGainWithAmount() {
        val up = PortfolioWidgetViews.listTotalChange(snapshot(change = 9.0, percent = 1.61))!!
        assertEquals(9.0, up.amount, d)
        assertEquals(1.61, up.percent!!, d)
        assertEquals(1, up.direction)
        assertEquals(-1, PortfolioWidgetViews.listTotalChange(snapshot(change = -3.0, percent = -0.4))!!.direction)
        // Richtung nach dem Betrag (0 = grau)
        assertEquals(0, PortfolioWidgetViews.listTotalChange(snapshot(change = 0.001, percent = 0.001))!!.direction)
        // Schmale Total-Zeile ist höher als eine einzeilige Zeile (zweizeilig wie die schmalen Listenzeilen)
        assertTrue(PortfolioWidgetViews.listTotalHeightDp(1f) > PortfolioWidgetViews.rowHeightDp(true, 1f))
        // Breit einzeilig wie die breiten Zeilen (Build 46): niedriger als zweizeilig
        assertTrue(PortfolioWidgetViews.listTotalHeightDp(1f, wide = true) < PortfolioWidgetViews.listTotalHeightDp(1f))
        // Ohne Prozent (Basis ~0): nur der Betrag
        val noPercent = PortfolioWidgetViews.listTotalChange(snapshot(change = 9.0, percent = null))!!
        assertNull(noPercent.percent)
        // Unbekannt (kein Betrag, nicht endlich): nur der Wert
        assertNull(PortfolioWidgetViews.listTotalChange(snapshot(change = null, percent = 1.0)))
        assertNull(PortfolioWidgetViews.listTotalChange(snapshot(change = Double.NaN, percent = 1.0)))
    }

    @Test
    fun rowPnlAndTextsPutAmountFirst() {
        val p = PortfolioPosition("BTC", 83_385.24, 88.9, 1.0, "1.3", pnlAmount = 1_250.0, pnlPercent = 1.52)
        val pnl = PortfolioWidgetViews.rowPnl(p)!!
        assertEquals(1, pnl.direction)
        val texts = PortfolioWidgetViews.pnlTexts(pnl, "CHF", hideAmounts = false, locale = java.util.Locale.US)
        assertEquals(listOf("+1,250.00 CHF · +1.52%", "+1,250.00 CHF"), texts.map { it.filter { c -> c.code >= 32 && c !in "\u200E\u200F\u2066\u2067\u2068\u2069" } })
        // Beträge verborgen: Betrag «•••», Prozent bleibt
        val hidden = PortfolioWidgetViews.pnlTexts(pnl, "CHF", hideAmounts = true, locale = java.util.Locale.US)
        assertEquals("•••", hidden.last())
        assertTrue(hidden.first().startsWith("••• · "))
        // Verlust: Richtung −1; unbekannt (ältere Aufnahme, kein Betrag): null → «—»
        assertEquals(-1, PortfolioWidgetViews.rowPnl(p.copy(pnlAmount = -5.0))!!.direction)
        assertNull(PortfolioWidgetViews.rowPnl(p.copy(pnlAmount = null)))
        assertNull(PortfolioWidgetViews.rowPnl(p.copy(pnlAmount = Double.NaN)))
        // Ohne Prozent nur der Betrag
        assertEquals(1, PortfolioWidgetViews.pnlTexts(PortfolioWidgetViews.rowPnl(p.copy(pnlPercent = null))!!, "CHF", false, java.util.Locale.US).size)
    }

    @Test
    fun listWithoutStoredPositionsIsEmpty() {
        val l = PortfolioWidgetViews.listLayout(emptyList(), 0, 320, 300, 1f)
        assertTrue(l.shown.isEmpty())
        assertEquals(0, l.more)
    }

    @Test
    fun narrowRowsAreTaller() {
        assertTrue(PortfolioWidgetViews.rowHeightDp(false, 1f) > PortfolioWidgetViews.rowHeightDp(true, 1f))
        assertTrue(PortfolioWidgetViews.rowHeightDp(true, 1.3f) >= PortfolioWidgetViews.rowHeightDp(true, 1f))
    }

    @Test
    fun figureTextIsArrowAndSignedPercentWithoutPill() {
        assertEquals("↗ +424.47%", PortfolioWidgetViews.figureText(1, "+424.47%"))
        assertEquals("↘ −0.85%", PortfolioWidgetViews.figureText(-1, "−0.85%"))
        // 0: ohne Pfeil (grau setzt der Renderer)
        assertEquals("0.00%", PortfolioWidgetViews.figureText(0, "0.00%"))
    }

    @Test
    fun figuresWithoutPillNeedLessHeight() {
        // Zeilen: 8 Abstand + 3 × (11 sp × 1.35 + 2); Spalten: 8 + 10 sp × 1.35 + 2 + 11 sp × 1.35
        assertEquals(8f + 3 * (11f * 1.35f + 2f), PortfolioWidgetViews.figuresHeightDp(false, 1f), 0.01f)
        assertEquals(8f + 11f * 1.35f + 2f + 11f * 1.35f, PortfolioWidgetViews.figuresHeightDp(true, 1f), 0.01f)
    }
}
