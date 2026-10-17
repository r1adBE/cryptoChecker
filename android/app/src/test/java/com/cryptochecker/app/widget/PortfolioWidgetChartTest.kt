package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Anzeige, Zeitraum und Chart des Portfolio-Widgets ([PortfolioWidgetChart], [PortfolioWidgetChartGeometry]). */
class PortfolioWidgetChartTest {

    private val d = 1e-9
    private val zone = ZoneId.of("Europe/Zurich")
    private val today = LocalDate.of(2026, 10, 11).toEpochDay()
    private val hour = 3_600_000L
    private val dayEnd: (Long) -> Long = { day ->
        LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    private fun start(day: Long) = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun snapshot(
        total: Double = 900.0,
        totalUsdt: Double? = 1000.0,
        currency: String = "CHF",
        change: Double? = 9.0,
        percent: Double? = 1.0,
        history: List<PortfolioValuePoint> = emptyList(),
        daily: List<PortfolioHistoryPoint> = emptyList(),
        periodChanges: Map<Int, PortfolioChange> = emptyMap(),
    ) = PortfolioSnapshot(
        total = total,
        changeAmount = change,
        changePercent = percent,
        currency = currency,
        time = start(today) + 12 * hour,
        empty = false,
        totalUsdt = totalUsdt,
        positions = listOf(PortfolioPosition("BTC", 900.0, 100.0, 1.0)),
        history = history,
        daily = daily,
        periodChanges = periodChanges,
    )

    /** Stundenwerte (Anzeigewährung), einer je Stunde bis Mittag. */
    private fun hourly(vararg values: Double): List<PortfolioValuePoint> {
        val end = start(today) + 12 * hour
        return values.mapIndexed { i, v -> PortfolioValuePoint(end - (values.size - 1 - i) * hour, v) }
    }

    /** Tagesverlauf (USDT) der letzten [count] Tage bis [last], Wert = f(Index). */
    private fun daily(count: Int, last: Long = today, f: (Int) -> Double = { 1000.0 + it }): List<PortfolioHistoryPoint> =
        (0 until count).map { i -> PortfolioHistoryPoint(last - (count - 1 - i), f(i)) }

    // --- Anzeige ---

    @Test
    fun defaultDisplayIsConversionUnlessUsd() {
        assertEquals(PortfolioDisplay.CONVERSION, PortfolioDisplay.defaultFor("CHF"))
        assertEquals(PortfolioDisplay.USDT, PortfolioDisplay.defaultFor("USD"))
        assertEquals(PortfolioDisplay.USDT, PortfolioDisplay.defaultFor("USDT"))
    }

    @Test
    fun effectiveDisplayCollapsesForUsdAndOldSnapshots() {
        assertEquals(PortfolioDisplay.USDT, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION_USDT, "USD", true))
        assertEquals(PortfolioDisplay.USDT, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION, "USD", true))
        assertEquals(PortfolioDisplay.CONVERSION, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.USDT, "CHF", false))
        assertEquals(PortfolioDisplay.CONVERSION_USDT, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION_USDT, "CHF", true))
    }

    @Test
    fun usdtDisplayDividesAmountsByTheSnapshotRate() {
        val s = PortfolioWidgetChart.displayed(snapshot(history = hourly(900.0, 891.0)), PortfolioDisplay.USDT)
        assertEquals("USDT", s.currency)
        assertEquals(1000.0, s.total, d)
        assertEquals(10.0, s.changeAmount!!, d)
        assertEquals(1.0, s.changePercent!!, d)
        assertEquals(1000.0, s.positions.single().value, d)
        assertEquals(listOf(1000.0, 990.0), s.history.map { it.value })
        assertEquals(1.0, PortfolioWidgetChart.fxFactor(s)!!, d)
        // Umrechnung bleibt unverändert
        assertEquals("CHF", PortfolioWidgetChart.displayed(snapshot(), PortfolioDisplay.CONVERSION).currency)
    }

    // --- Devisenkurs der Aufnahme und Umrechnung beim Zeichnen ---

    @Test
    fun fxRateFallsBackToTheRememberedRate() {
        val now = 1_000_000_000L
        val memo = PortfolioFxMemo("CHF", 0.9, now - hour)
        // Frischer Kurs geht vor
        assertEquals(0.91, PortfolioWidgetChart.fxRateFor("CHF", 0.91, memo, now) ?: 0.0, d)
        // Ohne frischen Kurs (kein Netz, frischer Prozess): der gemerkte statt USD
        assertEquals(0.9, PortfolioWidgetChart.fxRateFor("chf", null, memo, now) ?: 0.0, d)
        assertEquals(0.9, PortfolioWidgetChart.fxRateFor("CHF", Double.NaN, memo, now) ?: 0.0, d)
        // Andere Währung, zu alt, aus der Zukunft: keiner
        assertNull(PortfolioWidgetChart.fxRateFor("EUR", null, memo, now))
        assertNull(PortfolioWidgetChart.fxRateFor("CHF", null, memo.copy(fetchedAt = now - 25 * hour), now))
        assertNull(PortfolioWidgetChart.fxRateFor("CHF", null, memo.copy(fetchedAt = now + hour), now))
        assertNull(PortfolioWidgetChart.fxRateFor("CHF", null, null, now))
        // USD braucht keinen Kurs
        assertEquals(1.0, PortfolioWidgetChart.fxRateFor("USD", null, null, now) ?: 0.0, d)
    }

    @Test
    fun onlyARateOfTheCurrencyIsRemembered() {
        val now = 5_000L
        assertEquals(
            PortfolioFxMemo("CHF", 0.9, 4_000L),
            PortfolioWidgetChart.fxMemoToSave("chf", 0.9, 0.9, 4_000L, now),
        )
        assertEquals(now, PortfolioWidgetChart.fxMemoToSave("CHF", 0.9, 0.9, null, now)?.fetchedAt)
        // Kurs der vorigen Währung kurz nach dem Umstellen (FxRateSource kennt einen anderen bzw. keinen)
        assertNull(PortfolioWidgetChart.fxMemoToSave("CHF", 1.08, 0.9, 4_000L, now))
        assertNull(PortfolioWidgetChart.fxMemoToSave("CHF", 0.9, null, null, now))
        assertNull(PortfolioWidgetChart.fxMemoToSave("CHF", null, 0.9, null, now))
        assertNull(PortfolioWidgetChart.fxMemoToSave("USD", 1.0, 1.0, null, now))
    }

    @Test
    fun usdSnapshotIsRebasedToTheConversionCurrency() {
        // Ohne Kurs in USD aufgenommen: Gesamtwert = USDT-Wert
        val usd = snapshot(total = 1000.0, totalUsdt = 1000.0, currency = "USD", change = 10.0, history = hourly(1000.0, 990.0))
            .let { it.copy(positions = listOf(PortfolioPosition("BTC", 1000.0, 100.0, 1.0, pnlAmount = 10.0))) }
        // Vor dem Fix: «Umrechnung» fiel bei USD auf «Nur USDT» zurück
        assertEquals(PortfolioDisplay.USDT, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION, usd.currency, true))
        val chf = PortfolioWidgetChart.rebased(usd, "CHF", 0.9)
        assertEquals("CHF", chf.currency)
        assertEquals(900.0, chf.total, d)
        assertEquals(9.0, chf.changeAmount ?: 0.0, d)
        assertEquals(900.0, chf.positions.single().value, d)
        assertEquals(9.0, chf.positions.single().pnlAmount ?: 0.0, d)
        assertEquals(listOf(900.0, 891.0), chf.history.map { it.value })
        assertEquals(1000.0, chf.totalUsdt ?: 0.0, d)
        // Anzeige → Werte und Währung
        val conversion = PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION, chf.currency, true)
        assertEquals(PortfolioDisplay.CONVERSION, conversion)
        assertEquals("CHF", PortfolioWidgetChart.displayed(chf, conversion).currency)
        assertEquals(900.0, PortfolioWidgetChart.displayed(chf, conversion).total, d)
        val withUsdt = PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.CONVERSION_USDT, chf.currency, true)
        assertEquals(PortfolioDisplay.CONVERSION_USDT, withUsdt)
        assertEquals("CHF", PortfolioWidgetChart.displayed(chf, withUsdt).currency)
        val usdt = PortfolioWidgetChart.displayed(chf, PortfolioWidgetChart.effectiveDisplay(PortfolioDisplay.USDT, chf.currency, true))
        assertEquals("USDT", usdt.currency)
        assertEquals(1000.0, usdt.total, d)
        assertEquals(10.0, usdt.changeAmount ?: 0.0, d)
    }

    @Test
    fun rebaseSwitchesCurrencyAndKeepsMatchingSnapshots() {
        // Vor dem Währungswechsel aufgenommen (CHF, Faktor 0.9) → EUR 0.85
        val eur = PortfolioWidgetChart.rebased(snapshot(), "EUR", 0.85)
        assertEquals("EUR", eur.currency)
        assertEquals(850.0, eur.total, d)
        assertEquals(8.5, eur.changeAmount ?: 0.0, d)
        // Gleiche Währung, ohne Kurs oder ohne USDT-Wert: unverändert
        assertEquals(snapshot(), PortfolioWidgetChart.rebased(snapshot(), "chf", 0.5))
        assertEquals(snapshot(), PortfolioWidgetChart.rebased(snapshot(), "EUR", null))
        assertEquals(snapshot(totalUsdt = null), PortfolioWidgetChart.rebased(snapshot(totalUsdt = null), "EUR", 0.85))
        // Zurück auf USD: Kurs 1
        assertEquals(1000.0, PortfolioWidgetChart.rebased(snapshot(), "USD", 1.0).total, d)
    }

    // --- Tageskurse und Tagesverlauf ---

    @Test
    fun staleClosesAreNotUsable() {
        val todayUtc = 20_000L
        val closes = mapOf(
            "BTC" to mapOf(todayUtc - 1 to 100.0),
            "ETH" to mapOf(todayUtc - 2 to 10.0),
            "SOL" to mapOf(todayUtc to Double.NaN),
        )
        assertEquals(setOf("BTC"), PortfolioWidgetChart.usableCloses(closes, todayUtc).keys)
    }

    @Test
    fun dailyDataBuildsYearAndPeriodChangesOfTodaysHoldings() {
        val trades = listOf(PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 50.0, start(today - 500)))
        // Schluss 100 jeden Tag, vor 7 Tagen 90, vor 30 Tagen 80; bis gestern
        val closes = mapOf(
            "BTC" to (today - 400 until today).associateWith { day ->
                when (day) {
                    today - 7 -> 90.0
                    today - 30 -> 80.0
                    else -> 100.0
                }
            }
        )
        val data = PortfolioWidgetChart.dailyData(
            trades = trades,
            closes = closes,
            ranges = emptyMap(),
            holdings = mapOf("BTC" to 1.0),
            current = mapOf("BTC" to 110.0),
            todayEpochDay = today,
            todayUtcDay = today,
            dayEndMillis = dayEnd,
        )
        assertEquals(366, data.points.size)
        assertEquals(today, data.points.last().epochDay)
        assertEquals(110.0, data.points.last().value, d)
        assertEquals(20.0, data.changes.getValue(7).amount, d)
        assertEquals(20.0 / 90.0 * 100.0, data.changes.getValue(7).percent!!, 1e-6)
        assertEquals(30.0, data.changes.getValue(30).amount, d)
        assertEquals(10.0, data.changes.getValue(365).amount, d)
    }

    @Test
    fun coinWithoutUsableClosesMeansNoDailyData() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 50.0, start(today - 500)),
            PortfolioTrade(2, "ETH", PortfolioTxType.BUY, 1.0, 50.0, start(today - 500)),
        )
        val closes = mapOf(
            "BTC" to (today - 400 until today).associateWith { 100.0 },
            // ETH: nur alte Kurse (Portfolio-Tab länger nicht geöffnet)
            "ETH" to (today - 400 until today - 5).associateWith { 10.0 },
        )
        val data = PortfolioWidgetChart.dailyData(
            trades, closes, emptyMap(), mapOf("BTC" to 1.0, "ETH" to 1.0), mapOf("BTC" to 100.0, "ETH" to 10.0),
            today, today, dayEnd,
        )
        assertTrue(data.points.isEmpty())
        // Zahl wie «Gewinn 7 T» der App: die übrigen Coins zählen, ETH steht unter «ohne …» (M-3: dieselbe Zahl)
        assertEquals(0.0, data.changes.getValue(7).amount, d)
        assertEquals(listOf("ETH"), data.changes.getValue(7).skipped)
        assertEquals(PortfolioPeriodChange.gain(trades, mapOf("BTC" to 100.0, "ETH" to 10.0),
            PortfolioWidgetChart.usableCloses(closes, today), today - 7)!!.amountUsd, data.changes.getValue(7).amount, d)
    }

    @Test
    fun dailyBarsTakeTheRangeLikeThePortfolioTab() {
        val points = daily(366)
        val week = PortfolioWidgetChart.dailyBars(points, PortfolioChartRange.WEEK, WidgetChartType.LINE, today, zone)
        assertEquals(8, week.size)
        assertEquals(start(today - 7), week.first().start)
        assertEquals(week.first().start, week.first().end)
        val month = PortfolioWidgetChart.dailyBars(points, PortfolioChartRange.MONTH, WidgetChartType.CANDLES, today, zone, 0.5)
        assertEquals(31, month.size)
        // Erste gezeigte Kerze eröffnet beim Schluss des Vortags; Werte × Faktor
        assertEquals(points[points.size - 32].value * 0.5, month.first().open, d)
        assertEquals(points.last().value * 0.5, month.last().close, d)
        assertEquals(start(today + 1), month.last().end)
    }

    @Test
    fun yearCandlesAreWeekly() {
        val bars = PortfolioWidgetChart.dailyBars(daily(366), PortfolioChartRange.YEAR, WidgetChartType.CANDLES, today, zone)
        assertTrue(bars.size in 53..54)
        // lückenlos: jede Kerze beginnt, wo die vorige endet
        bars.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    }

    @Test
    fun outdatedDailySeriesGivesNoBars() {
        val stale = daily(366, last = today - 2)
        assertTrue(PortfolioWidgetChart.dailyBars(stale, PortfolioChartRange.WEEK, WidgetChartType.LINE, today, zone).isEmpty())
        val yesterday = daily(366, last = today - 1)
        assertEquals(8, PortfolioWidgetChart.dailyBars(yesterday, PortfolioChartRange.WEEK, WidgetChartType.LINE, today, zone).size)
    }

    // --- Stundenverlauf ---

    @Test
    fun hourlyCandlesPairNeighbours() {
        val bars = PortfolioWidgetChart.hourlyBars(hourly(10.0, 12.0, 11.0), WidgetChartType.CANDLES)
        assertEquals(2, bars.size)
        assertEquals(10.0, bars[0].open, d)
        assertEquals(12.0, bars[0].high, d)
        assertEquals(10.0, bars[0].low, d)
        assertEquals(12.0, bars[0].close, d)
        assertFalse(bars[1].up)
        assertEquals(3, PortfolioWidgetChart.hourlyBars(hourly(10.0, 12.0, 11.0), WidgetChartType.LINE).size)
        assertTrue(PortfolioWidgetChart.hourlyBars(hourly(10.0, 12.0), WidgetChartType.CANDLES).isEmpty())
    }

    // --- Plan ---

    @Test
    fun planUsesDailyDataAndPeriodChange() {
        val s = snapshot(daily = daily(366), periodChanges = mapOf(7 to PortfolioChange(20.0, 2.0)))
        val plan = PortfolioWidgetChart.plan(s, PortfolioChartRange.WEEK, WidgetChartType.CANDLES, today, zone)
        assertFalse(plan.fallback)
        assertEquals(PortfolioChartRange.WEEK, plan.range)
        assertEquals(WidgetChartType.CANDLES, plan.type)
        assertEquals(ChartGridUnit.DAY, plan.gridUnit)
        // Betrag in der Anzeigewährung (Faktor 0.9 = 900 CHF / 1000 USDT)
        assertEquals(18.0, plan.changeAmount!!, d)
        assertEquals(2.0, plan.changePercent!!, d)
        // Tageskerzen von heute − 7 bis heute
        assertEquals(8, plan.bars.size)
    }

    @Test
    fun planFallsBackToHourlyLineWithoutDailyData() {
        val points = hourly(10.0, 11.0, 12.0, 11.0, 13.0, 12.0, 14.0)
        val s = snapshot(history = points, periodChanges = mapOf(30 to PortfolioChange(5.0, 1.0)))
        val plan = PortfolioWidgetChart.plan(s, PortfolioChartRange.MONTH, WidgetChartType.CANDLES, today, zone)
        assertTrue(plan.fallback)
        assertEquals(PortfolioChartRange.DAY, plan.range)
        assertEquals(PortfolioChartRange.MONTH, plan.requested)
        assertEquals(WidgetChartType.LINE, plan.type)
        assertEquals(points.size, plan.bars.size)
        assertEquals(ChartGridUnit.HOUR, plan.gridUnit)
        assertEquals(9.0, plan.changeAmount!!, d)
    }

    @Test
    fun planDayNeedsEnoughHourlyPoints() {
        val few = snapshot(history = hourly(10.0, 11.0))
        assertFalse(PortfolioWidgetChart.plan(few, PortfolioChartRange.DAY, WidgetChartType.LINE, today, zone).hasChart)
        // Tages-Basis: schon ab zwei Punkten, mit fester Achse
        val axis = start(today) to start(today + 1)
        val plan = PortfolioWidgetChart.plan(few, PortfolioChartRange.DAY, WidgetChartType.LINE, today, zone, axis)
        assertTrue(plan.hasChart)
        assertEquals(axis, plan.axis)
    }

    @Test
    fun percentTextCarriesTheSignWithoutArrow() {
        val up = PortfolioWidgetChart.plan(snapshot(change = 9.0, percent = 2.314), PortfolioChartRange.DAY, WidgetChartType.LINE, today, zone)
        assertEquals("+2.31%", PortfolioWidgetChart.percentText(up, Locale.US))
        assertEquals(1, PortfolioWidgetChart.direction(up))
        val down = PortfolioWidgetChart.plan(snapshot(change = -9.0, percent = -0.5), PortfolioChartRange.DAY, WidgetChartType.LINE, today, zone)
        assertEquals("−0.50%", PortfolioWidgetChart.percentText(down, Locale.US))
        val none = PortfolioWidgetChart.plan(snapshot(change = null, percent = null), PortfolioChartRange.DAY, WidgetChartType.LINE, today, zone)
        assertNull(PortfolioWidgetChart.percentText(none))
    }

    // --- Geometrie ---

    private fun geometry(
        bars: List<ChartBar>,
        type: WidgetChartType = WidgetChartType.LINE,
        axis: Pair<Long, Long>? = null,
    ) = PortfolioWidgetChartGeometry(
        bars = bars,
        type = type,
        axis = axis,
        gridUnit = ChartGridUnit.HOUR,
        zone = zone,
        width = 100f,
        height = 50f,
        inset = 0f,
        topInset = 10f,
        bottomInset = 0f,
    )

    @Test
    fun lineFollowsTimeAndMarksTheHigh() {
        val geo = geometry(listOf(ChartBar.point(0, 1.0), ChartBar.point(10, 3.0), ChartBar.point(20, 2.0)))
        assertEquals(listOf(0f, 50f, 100f), geo.linePoints.map { it.x })
        assertEquals(1, geo.highIndex)
        assertEquals(10f, geo.highPoint.y, 1e-4f)
        assertEquals(50f, geo.y(1.0), 1e-4f)
        assertEquals(listOf(10f, 30f, 50f), geo.levelYs)
        assertTrue(geo.up)
    }

    @Test
    fun fixedAxisLeavesTheRestOfTheDayEmpty() {
        val geo = geometry(listOf(ChartBar.point(0, 1.0), ChartBar.point(20, 2.0)), axis = 0L to 40L)
        assertEquals(50f, geo.linePoints.last().x, 1e-4f)
    }

    @Test
    fun candlesSitInTheMiddleOfTheirTime() {
        val bars = listOf(ChartBar(0, 10, 1.0, 2.0, 0.5, 1.5), ChartBar(10, 20, 1.5, 4.0, 1.0, 1.2))
        val geo = geometry(bars, WidgetChartType.CANDLES)
        assertEquals(25f, geo.candleShapes[0].x, 1e-4f)
        assertEquals(50f * 0.65f, geo.candleShapes[0].bodyWidth, 1e-4f)
        assertEquals(1, geo.highIndex)
        assertEquals(75f, geo.highPoint.x, 1e-4f)
        assertFalse(geo.candleShapes[1].up)
    }

    @Test
    fun highLabelStaysBesideTheMarkerAndInside() {
        // Marker links: Beschriftung rechts davon
        assertEquals(15f, PortfolioWidgetChartGeometry.labelLeft(10f, 30f, 0f, 100f, 5f), 1e-4f)
        // Marker rechts: links davon
        assertEquals(55f, PortfolioWidgetChartGeometry.labelLeft(90f, 30f, 0f, 100f, 5f), 1e-4f)
        // Zu breit für den Platz: an den Rand geschoben
        assertEquals(10f, PortfolioWidgetChartGeometry.labelLeft(20f, 90f, 0f, 100f, 5f), 1e-4f)
    }

    @Test
    fun gridTimesFallOnFullHoursAndLocalMidnights() {
        assertEquals(listOf(hour, 2 * hour), PortfolioWidgetChartGeometry.gridTimes(hour / 2, 2 * hour + 1, ChartGridUnit.HOUR, zone))
        val days = PortfolioWidgetChartGeometry.gridTimes(start(today - 3) + hour, start(today) + hour, ChartGridUnit.DAY, zone)
        assertEquals(listOf(start(today - 2), start(today - 1), start(today)), days)
    }

    @Test
    fun calmPortfolioGetsMinimumAxisSpan() {
        // 1000 → 1000.5: Spanne unter 0.2 % der Mitte → Achse 0.2 % hoch, um die Mitte gelegt
        val geo = geometry(listOf(ChartBar.point(0, 1000.0), ChartBar.point(10, 1000.5)))
        assertEquals(1000.5, geo.high, 0.0)
        assertEquals(1000.25 * 0.002, geo.axisLevels.high - geo.axisLevels.low, 1e-9)
        listOf(10f, 30f, 50f).zip(geo.levelYs).forEach { (e, y) -> assertEquals(e, y, 1e-3f) }
        // Die Linie liegt nahe der Mitte statt von ganz oben nach ganz unten
        assertTrue(geo.y(1000.0) > 30f && geo.y(1000.0) < 50f)
        assertTrue(geo.y(1000.5) > 10f && geo.y(1000.5) < 30f)
    }
}
