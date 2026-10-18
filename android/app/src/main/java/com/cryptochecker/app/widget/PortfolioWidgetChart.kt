package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCandles
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Anzeige des Portfolio-Widgets, je Widget wählbar (gespeichert wird der Name, siehe
 * [WidgetPrefs.getPortfolioDisplay]; gespiegelt in `PortfolioWidgetDisplay`, iOS):
 *  - [USDT]: nur USDT (Gesamtwert, Betrag, Chart und Positionen in USDT),
 *  - [CONVERSION]: nur die Umrechnungswährung,
 *  - [CONVERSION_USDT]: Umrechnungswährung gross, darunter klein «≈ … USDT».
 */
enum class PortfolioDisplay {
    USDT,
    CONVERSION,
    CONVERSION_USDT,
    ;

    companion object {
        fun fromName(name: String?): PortfolioDisplay? = entries.firstOrNull { it.name == name }

        /**
         * Ohne Wahl: «Umrechnung» — auch für USD (USDT zum Marktkurs USDT/USD); ist die Umrechnungswährung ein
         * USD-Stablecoin, «Nur USDT». (Beim Zeichnen fallen dort ohnehin alle drei auf «Nur USDT» zusammen, siehe
         * [PortfolioWidgetChart.effectiveDisplay].)
         */
        fun defaultFor(currency: String): PortfolioDisplay =
            if (PortfolioWidgetChart.collapsesToUsdt(currency)) USDT else CONVERSION
    }
}

/**
 * Zeitraum für Veränderung und Chart im Portfolio-Widget (gespeichert wird der Name; iOS
 * `PortfolioWidgetRange`): [DAY] = Stundenverlauf und Veränderung der Momentaufnahme (24 h bzw.
 * die %-Basis), sonst Tagesverlauf und Veränderung des heutigen Bestands über [days] Tage.
 */
enum class PortfolioChartRange(val days: Int) {
    DAY(1),
    WEEK(7),
    MONTH(30),
    YEAR(365),
    ;

    /** Aus dem Tagesverlauf (statt aus den Stundenwerten)? */
    val daily: Boolean get() = this != DAY

    companion object {
        /** Neue und bestehende Widgets ohne Wahl: 24 h wie bisher. */
        val DEFAULT = DAY

        fun fromName(name: String?): PortfolioChartRange = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Ein Balken des Portfolio-Charts über die Zeit [start]..[end] (Epoch-ms): Kerze mit Eröffnung,
 * Hoch, Tief und Schluss; ein Punkt der Linie hat [start] = [end] und vier gleiche Werte.
 */
data class ChartBar(
    val start: Long,
    val end: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
) {
    /** Steigend (Schluss ≥ Eröffnung) = Kursfarbe «steigend». */
    val up: Boolean get() = close >= open

    companion object {
        fun point(time: Long, value: Double) = ChartBar(time, time, value, value, value, value)
    }
}

/**
 * Was das Portfolio-Widget zeigt: wirksamer Zeitraum ([range], bei [fallback] 24 h statt des
 * gewählten [requested]), Chart-Art, Balken (leer = «Verlauf folgt»), senkrechtes Gitter, feste
 * Zeitachse (nur Tages-Basis über 24 h) und Veränderung in der Anzeige-Einheit (null = unbekannt).
 */
data class PortfolioChartPlan(
    val range: PortfolioChartRange,
    val requested: PortfolioChartRange,
    val fallback: Boolean,
    val type: WidgetChartType,
    val bars: List<ChartBar>,
    val gridUnit: ChartGridUnit,
    val axis: Pair<Long, Long>?,
    val changeAmount: Double?,
    val changePercent: Double?,
) {
    val hasChart: Boolean get() = bars.size >= 2
}

/** Zuletzt bekannter Devisenkurs USD → [currency] mit Abrufzeit (Epoch-ms), gemerkt neben der Momentaufnahme. */
data class PortfolioFxMemo(
    val currency: String,
    val rate: Double,
    val fetchedAt: Long,
)

/** Tagesverlauf (USDT) und Veränderung je Zeitraum für die Momentaufnahme ([PortfolioSnapshot.daily]). */
data class PortfolioDailyData(
    val points: List<PortfolioHistoryPoint>,
    val changes: Map<Int, PortfolioChange>,
) {
    companion object {
        val EMPTY = PortfolioDailyData(emptyList(), emptyMap())
    }
}

/**
 * Reine Regeln für Anzeige, Zeitraum und Chart des Portfolio-Widgets (ohne Android, getestet in
 * PortfolioWidgetChartTest; gespiegelt in `Shared/Portfolio/PortfolioWidgetPlan.swift`).
 *
 *  - 24 h: Stundenverlauf der Momentaufnahme. Kerze i: Eröffnung = Wert i, Schluss = Wert i + 1,
 *    Hoch/Tief = grösserer/kleinerer der beiden (Stundenwerte kennen kein Hoch/Tief dazwischen).
 *  - 7 T / 30 T / 1 J: Tagesverlauf in USDT ([PortfolioHistory.build], wie der Wertverlauf im
 *    Portfolio-Tab) — Kerzen wie dort ([PortfolioCandles]: Eröffnung = Schluss des Vortags, Hoch/Tief
 *    aus den Tageskerzen der Coins; 1 J als Wochenkerzen). Veränderung wie «Gewinn 7 T» im Portfolio-Tab
 *    ([PortfolioPeriodChange.gain]: Wert jetzt − Wert am Stichtag − Käufe + Verkäufe im Zeitraum).
 *  - Es wird nichts zusätzlich geladen: Die Tageskurse stammen aus dem Zwischenspeicher des
 *    Wertverlaufs (PortfolioHistorySource), sie entstehen beim Öffnen des Portfolio-Tabs. Taugen sie
 *    nicht ([usableCloses]; fehlt einem Coin der Verlauf), fällt das Widget ruhig auf 24 h als Linie
 *    zurück ([PortfolioChartPlan.fallback]) und sagt es in der Fusszeile.
 *  - Umgerechnet wird mit dem Kurs der Momentaufnahme ([fxFactor]) — wie der 24-h-Verlauf; die
 *    EZB-Tageskurse des Portfolio-Tabs kennt das Widget nicht.
 */
object PortfolioWidgetChart {

    const val HOUR_MILLIS = 3_600_000L

    /** Stundenverlauf über mehr als diese Spanne (ältere Aufnahmen bis 48 h): Gitter je Tag statt je Stunde. */
    const val HOURLY_GRID_MAX_SPAN_MILLIS = 26 * HOUR_MILLIS

    /** Tageskurse eines Coins zählen nur, wenn der jüngste Schluss höchstens so viele UTC-Tage alt ist. */
    const val MAX_CLOSE_AGE_DAYS = 1

    /** Zeiträume mit Tagesverlauf (Tage). */
    val PERIOD_DAYS: List<Int> = PortfolioChartRange.entries.filter { it.daily }.map { it.days }

    private const val USDT_CODE = "USDT"

    /**
     * Anzeigewährung ein USD-Stablecoin (USDT, USDC …)? Dann fallen die drei Anzeigen auf «Nur USDT» zusammen. USD
     * selbst nicht mehr: USDT gilt dort seinen Marktkurs USDT/USD ([usdtFactor]) wie in jeder anderen Fiat-Währung.
     */
    fun collapsesToUsdt(currency: String): Boolean =
        CurrencyConversion.normalize(currency).let { it in CurrencyConversion.USD_STABLES && it != "USD" }

    /**
     * Wirksame Anzeige: Ist die Anzeigewährung ein USD-Stablecoin ([collapsesToUsdt]), fallen die drei Möglichkeiten
     * auf «Nur USDT» zusammen (USD nicht: dort wie jede Fiat-Währung); ohne USDT-Wert (ältere Aufnahme) bleibt nur
     * die Umrechnung.
     */
    fun effectiveDisplay(chosen: PortfolioDisplay, currency: String, hasUsdt: Boolean): PortfolioDisplay = when {
        !hasUsdt -> PortfolioDisplay.CONVERSION
        collapsesToUsdt(currency) -> PortfolioDisplay.USDT
        else -> chosen
    }

    /** Faktor USDT → Anzeigewährung der Aufnahme (Gesamtwert ÷ Gesamtwert in USDT); null ohne gültigen USDT-Wert. */
    fun fxFactor(snapshot: PortfolioSnapshot): Double? {
        val usdt = snapshot.totalUsdt ?: return null
        if (!(usdt > 0.0) || !usdt.isFinite() || !snapshot.total.isFinite()) return null
        return (snapshot.total / usdt).takeIf { it > 0.0 && it.isFinite() }
    }

    /**
     * Die Aufnahme in USDT («Nur USDT»): Gesamtwert, Betrag, Positionen (Wert, Gewinn/Verlust) und Stundenverlauf geteilt
     * durch [fxFactor], Währung «USDT». Prozente bleiben (gleiche Rechnung); Tagesverlauf und
     * Veränderung je Zeitraum sind schon in USDT. Ohne Faktor (leeres Portfolio) unverändert mit «USDT».
     */
    fun inUsdt(snapshot: PortfolioSnapshot): PortfolioSnapshot {
        val f = fxFactor(snapshot) ?: return snapshot.copy(currency = USDT_CODE)
        return snapshot.copy(
            total = snapshot.totalUsdt ?: (snapshot.total / f),
            changeAmount = snapshot.changeAmount?.div(f),
            currency = USDT_CODE,
            positions = snapshot.positions.map { it.copy(value = it.value / f, pnlAmount = it.pnlAmount?.div(f)) },
            history = snapshot.history.map { it.copy(value = it.value / f) },
        )
    }

    /** Die Aufnahme, wie das Widget sie zeigt ([effectiveDisplay]): bei «Nur USDT» in USDT, sonst wie gespeichert. */
    fun displayed(snapshot: PortfolioSnapshot, display: PortfolioDisplay): PortfolioSnapshot =
        if (display == PortfolioDisplay.USDT) inUsdt(snapshot) else snapshot

    /** Gemerkter Devisenkurs höchstens so lange gültig (wie FxRateSource: älter als ein Tag = keiner). */
    const val FX_MEMO_MAX_AGE_MILLIS = 24 * HOUR_MILLIS

    private fun validRate(rate: Double?): Double? = rate?.takeIf { it > 0.0 && it.isFinite() }

    private fun isUsd(currency: String): Boolean = CurrencyConversion.normalize(currency) in CurrencyConversion.USD_STABLES

    /**
     * Devisenkurs USD → [currency] für die Aufnahme: der frische ([fresh]), sonst der gemerkte ([memo], gleiche
     * Währung, höchstens [FX_MEMO_MAX_AGE_MILLIS] alt), bei USD 1; sonst null (dann in USD). Ohne den gemerkten Kurs
     * fiel jede Aufnahme ohne Netz bzw. in einem frischen Prozess (Hintergrund, Widget) auf USD zurück — und das
     * Widget zeigte trotz «Umrechnung» USDT ([effectiveDisplay]).
     */
    fun fxRateFor(currency: String, fresh: Double?, memo: PortfolioFxMemo?, now: Long): Double? {
        validRate(fresh)?.let { return it }
        if (isUsd(currency)) return 1.0
        val m = memo ?: return null
        if (CurrencyConversion.normalize(m.currency) != CurrencyConversion.normalize(currency)) return null
        if (now - m.fetchedAt !in 0 until FX_MEMO_MAX_AGE_MILLIS) return null
        return validRate(m.rate)
    }

    /**
     * Faktor USDT → [currency] für die Aufnahme: Devisenkurs [fx] (USD → Währung) × Live-Kurs USDT/USD [usdtLive]
     * ([PortfolioStables.usdtTo], USDT zum Marktkurs; ohne Messung × 1) — auch für USD ([fx] = 1). USD-Stablecoins:
     * [fx] unverändert — das Widget zeigt dort USDT ([effectiveDisplay]).
     */
    fun usdtFactor(currency: String, fx: Double, usdtLive: Double?): Double {
        if (collapsesToUsdt(currency)) return fx
        return com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtTo(
            fx, com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsdLive(usdtLive),
        ) ?: fx
    }

    /**
     * Zu merkender Kurs: nur ein gültiger [fresh], der zur Währung gehört ([cachedForCurrency] = derselbe Kurs aus
     * FxRateSource — schützt vor dem Kurs der vorigen Währung kurz nach dem Umstellen); nicht für USD.
     * @param fetchedAt Abrufzeit des Kurses (FxRateSource), sonst [now]
     */
    fun fxMemoToSave(currency: String, fresh: Double?, cachedForCurrency: Double?, fetchedAt: Long?, now: Long): PortfolioFxMemo? {
        val rate = validRate(fresh) ?: return null
        if (isUsd(currency) || cachedForCurrency != rate) return null
        return PortfolioFxMemo(CurrencyConversion.normalize(currency), rate, fetchedAt ?: now)
    }

    /**
     * Die Aufnahme in der eingestellten Umrechnungswährung [currency]: Lautet sie anders (ohne Kurs in USD
     * aufgenommen oder vor dem Wechsel der Währung), werden Gesamtwert, Betrag, Positionen und Stundenverlauf
     * mit [rate] (USD → [currency]) neu umgerechnet — sofort beim Zeichnen, nicht erst nach der nächsten
     * Aufnahme. Ohne Kurs oder ohne USDT-Wert unverändert. USDT-Teile (Tagesverlauf, ± seit Kauf) bleiben.
     */
    fun rebased(snapshot: PortfolioSnapshot, currency: String, rate: Double?): PortfolioSnapshot {
        if (CurrencyConversion.normalize(snapshot.currency) == CurrencyConversion.normalize(currency)) return snapshot
        val target = validRate(rate) ?: return snapshot
        val f = fxFactor(snapshot) ?: return snapshot
        val k = target / f
        return snapshot.copy(
            total = snapshot.total * k,
            changeAmount = snapshot.changeAmount?.times(k),
            currency = CurrencyConversion.normalize(currency),
            positions = snapshot.positions.map { it.copy(value = it.value * k, pnlAmount = it.pnlAmount?.times(k)) },
            history = snapshot.history.map { it.copy(value = it.value * k) },
        )
    }

    /**
     * Tageskurse, die für den Tagesverlauf taugen: nur Coins, deren jüngster Schluss höchstens
     * [MAX_CLOSE_AGE_DAYS] UTC-Tage vor [todayUtcDay] liegt — ältere Reihen würden mit dem letzten
     * bekannten Schluss fortgeschrieben und zeigten eine falsche Kurve.
     */
    fun usableCloses(closes: Map<String, Map<Long, Double>>, todayUtcDay: Long): Map<String, Map<Long, Double>> =
        closes.filterValues { byDay ->
            val latest = byDay.entries.filter { it.value > 0.0 && it.value.isFinite() }.maxOfOrNull { it.key }
            latest != null && latest >= todayUtcDay - MAX_CLOSE_AGE_DAYS
        }

    /**
     * Tagesverlauf in USDT über höchstens ein Jahr ([PortfolioHistory.build] mit «1 J», Kurs 1) und
     * der Gewinn je Zeitraum ([PERIOD_DAYS], [PortfolioPeriodChange.gain]) aus den schon geladenen
     * Tageskursen ([usableCloses]); heute mit den aktuellen Kursen [current]. Fehlt einem Coin mit
     * Bestand im Jahr der Verlauf, gibt es keinen Tagesverlauf (lieber keiner als ein falscher).
     * @param holdings Menge je offenem Coin; [current] USDT-Kurse der offenen Coins
     * @param addresses beobachtete Adress-Guthaben: zählen ab ihrer ersten Beobachtung (nie rückwirkend)
     */
    fun dailyData(
        trades: List<PortfolioTrade>,
        closes: Map<String, Map<Long, Double>>,
        ranges: Map<String, Map<Long, PortfolioDayRange>>,
        holdings: Map<String, Double>,
        current: Map<String, Double>,
        todayEpochDay: Long,
        todayUtcDay: Long,
        dayEndMillis: (Long) -> Long,
        addresses: List<AddressBalanceHistory> = emptyList(),
        /** Einstandsmethode: Bestände wie Karte und App ([PortfolioHoldingsBook]). */
        method: CostMethod = CostMethod.DEFAULT,
    ): PortfolioDailyData {
        if (holdings.isEmpty()) return PortfolioDailyData.EMPTY
        val usable = usableCloses(closes, todayUtcDay)
        val series = PortfolioHistory.build(
            trades = trades,
            closes = usable,
            livePrices = current,
            range = PortfolioHistoryRange.YEAR,
            todayEpochDay = todayEpochDay,
            dayEndMillis = dayEndMillis,
            fxRate = 1.0,
            ranges = ranges.filterKeys { it in usable },
            addresses = addresses,
            method = method,
        )
        val points = if (series.skipped.isEmpty() && series.hasChart) series.points else emptyList()
        val changes = PERIOD_DAYS.mapNotNull { days ->
            // Dieselbe Zahl wie die App («7 T»/«1 J»), auch wenn ein Coin fehlt — dann mit «ohne …» ([PortfolioChange.skipped])
            PortfolioPeriodChange.gain(trades, current, usable, todayEpochDay - days, addresses = addresses, method = method)
                ?.let { days to PortfolioChange(it.amountUsd, it.percent, it.skipped) }
        }.toMap()
        return PortfolioDailyData(points, changes)
    }

    /**
     * Balken aus dem Stundenverlauf: Linie = je Punkt einer, Kerzen = je zwei aufeinander folgende
     * Punkte einer (Eröffnung = früherer, Schluss = späterer Wert). Weniger als zwei Balken: leer.
     */
    fun hourlyBars(points: List<PortfolioValuePoint>, type: WidgetChartType): List<ChartBar> {
        val clean = points.filter { it.value.isFinite() }.sortedBy { it.time }
        val out = when (type) {
            WidgetChartType.LINE -> clean.map { ChartBar.point(it.time, it.value) }
            WidgetChartType.CANDLES -> clean.zipWithNext { a, b ->
                ChartBar(a.time, b.time, a.value, max(a.value, b.value), min(a.value, b.value), b.value)
            }
        }
        return if (out.size >= 2) out else emptyList()
    }

    /**
     * Balken des Tagesverlaufs [points] (USDT, aufsteigend) für [range]: die Tage ab letzter Tag −
     * [PortfolioChartRange.days] (wie der Zeitraum im Portfolio-Tab). Der letzte Tag muss heute oder
     * gestern sein ([todayEpochDay]), sonst leer (veraltet). Kerzen nach [PortfolioCandles] aus der
     * ganzen Reihe (die erste gezeigte eröffnet beim Schluss des Vortags), über 92 Tage zu Wochen
     * zusammengefasst; eine Kerze reicht vom Beginn ihres ersten bis zum Beginn des Tags nach ihrem
     * letzten Tag (lokal, [zone]), ein Punkt der Linie liegt am Tagesbeginn. Werte × [factor]
     * (USDT → Anzeige-Einheit). Weniger als zwei Balken: leer.
     */
    fun dailyBars(
        points: List<PortfolioHistoryPoint>,
        range: PortfolioChartRange,
        type: WidgetChartType,
        todayEpochDay: Long,
        zone: ZoneId,
        factor: Double = 1.0,
    ): List<ChartBar> {
        if (!range.daily || !(factor > 0.0) || !factor.isFinite()) return emptyList()
        val sorted = points.filter { it.value.isFinite() }.sortedBy { it.epochDay }
        val last = sorted.lastOrNull()?.epochDay ?: return emptyList()
        if (last > todayEpochDay || last < todayEpochDay - 1) return emptyList()
        val first = last - range.days
        fun start(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = when (type) {
            WidgetChartType.LINE -> sorted.filter { it.epochDay >= first }.map { p ->
                val t = start(p.epochDay)
                ChartBar.point(t, p.value * factor)
            }
            WidgetChartType.CANDLES -> {
                val shown = PortfolioCandles.daily(sorted).filter { it.firstDay >= first }
                val dayCount = if (shown.isEmpty()) 0 else (shown.last().lastDay - shown.first().firstDay + 1).toInt()
                PortfolioCandles.aggregate(shown, PortfolioCandles.bucketFor(dayCount)).map { c ->
                    ChartBar(
                        start(c.firstDay),
                        start(c.lastDay + 1),
                        c.open * factor,
                        c.high * factor,
                        c.low * factor,
                        c.close * factor,
                    )
                }
            }
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** Senkrechtes Gitter: 24 h je Stunde (über 26 h je Tag), 7 T je Tag, 30 T je Woche, 1 J je Monat. */
    fun gridUnit(range: PortfolioChartRange, bars: List<ChartBar>): ChartGridUnit = when (range) {
        PortfolioChartRange.DAY -> {
            val span = if (bars.isEmpty()) 0L else bars.maxOf { it.end } - bars.first().start
            if (span > HOURLY_GRID_MAX_SPAN_MILLIS) ChartGridUnit.DAY else ChartGridUnit.HOUR
        }
        PortfolioChartRange.WEEK -> ChartGridUnit.DAY
        PortfolioChartRange.MONTH -> ChartGridUnit.WEEK
        PortfolioChartRange.YEAR -> ChartGridUnit.MONTH
    }

    /**
     * Was das Widget für [requested] zeigt, aus der gezeigten Aufnahme [snapshot] ([displayed]):
     * 7 T / 30 T / 1 J mit Tagesverlauf UND Veränderung des Zeitraums; fehlt eines, 24 h als Linie
     * ([PortfolioChartPlan.fallback]). 24 h: Stundenverlauf ab genug Punkten (Tages-Basis: ab zwei,
     * mit fester Achse [dayAxis]), Veränderung der Aufnahme.
     */
    fun plan(
        snapshot: PortfolioSnapshot,
        requested: PortfolioChartRange,
        type: WidgetChartType,
        todayEpochDay: Long,
        zone: ZoneId,
        dayAxis: Pair<Long, Long>? = null,
    ): PortfolioChartPlan {
        if (requested.daily) {
            val factor = fxFactor(snapshot) ?: 1.0
            val change = snapshot.periodChanges[requested.days]?.takeIf { it.amount.isFinite() }
            val bars = dailyBars(snapshot.daily, requested, type, todayEpochDay, zone, factor)
            if (change != null && bars.size >= 2) {
                return PortfolioChartPlan(
                    range = requested,
                    requested = requested,
                    fallback = false,
                    type = type,
                    bars = bars,
                    gridUnit = gridUnit(requested, bars),
                    axis = null,
                    changeAmount = change.amount * factor,
                    changePercent = change.percent?.takeIf { it.isFinite() },
                )
            }
        }
        val fallback = requested.daily
        val shownType = if (fallback) WidgetChartType.LINE else type
        val points = snapshot.history.filter { it.value.isFinite() }
        val drawable = if (dayAxis != null) PortfolioWidgetSeries.drawableDay(points) else PortfolioWidgetSeries.drawable(points)
        val bars = if (drawable) hourlyBars(points, shownType) else emptyList()
        return PortfolioChartPlan(
            range = PortfolioChartRange.DAY,
            requested = requested,
            fallback = fallback,
            type = shownType,
            bars = bars,
            gridUnit = gridUnit(PortfolioChartRange.DAY, bars),
            axis = dayAxis,
            changeAmount = snapshot.changeAmount?.takeIf { it.isFinite() },
            changePercent = snapshot.changePercent?.takeIf { it.isFinite() },
        )
    }

    /**
     * Richtung der Veränderung für Farbe und Pfeil: nach dem Vorzeichen des Betrags (nie nach dem
     * Farbtausch); null ohne Betrag.
     */
    fun direction(plan: PortfolioChartPlan): Int? = plan.changeAmount?.let { PortfolioWidgetSeries.direction(it) }

    /**
     * Prozent der Betragszeile: «+2.31%» / «−0.50%» / «0.00%» — Text in der Kursfarbe hinter dem Betrag
     * (keine Pille; der Pfeil steht vor dem Betrag); null ohne Prozent.
     */
    fun percentText(plan: PortfolioChartPlan, locale: Locale = Locale.getDefault()): String? {
        val percent = plan.changePercent ?: return null
        if (plan.changeAmount == null) return null
        return PortfolioSnapshotMath.signedPercent(percent, locale)
    }
}
