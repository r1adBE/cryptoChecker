package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntry
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetEntryFilter
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.util.PriceFormat
import java.util.Locale
import kotlin.math.floor

/**
 * Ansicht des Portfolio-Widgets, je Widget wählbar (gespeichert wird der Name, siehe
 * [WidgetPrefs.getPortfolioView]; gespiegelt in `PortfolioWidgetView`, iOS):
 *  - [OVERVIEW]: wie die erste Karte im Portfolio-Tab — Gesamtwert, Veränderung über den Zeitraum
 *    und die Kennzahlen Heute · 7 T · Gesamt,
 *  - [CHART]: Gesamtwert mit Wertverlauf (Standard),
 *  - [LIST]: die Positionen nach Wert wie die Liste im Portfolio-Tab.
 */
enum class PortfolioWidgetView {
    OVERVIEW,
    CHART,
    LIST,
    ;

    /** Chart-Art (Kerzen / Linie) gilt nur für den Chart. */
    val usesChartType: Boolean get() = this == CHART

    /** Zeitraum (24 h / 7 T / 30 T / 1 J) gilt nicht für die Liste (Einrichtung blendet ihn aus, die Wahl bleibt gespeichert). */
    val usesRange: Boolean get() = this != LIST

    companion object {
        /** Widgets ohne Wahl: Chart. */
        val DEFAULT = CHART

        fun fromName(name: String?): PortfolioWidgetView = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Kennzahl der Übersicht — wie die drei Zahlen der Gesamtkarte im Portfolio-Tab. */
enum class PortfolioFigureKind { TODAY, WEEK, TOTAL }

/**
 * Eine Kennzahl: Betrag in der Anzeige-Einheit und Prozent; null = unbekannt («—»). [unit] = abweichende Einheit des
 * Betrags («USDT» für «7 T» ohne Tageskurse der Anzeigewährung); null = die des Widgets. [incomplete] = ein Coin fehlt
 * (Wert mit «*», Screenreader «unvollständig»).
 */
data class PortfolioFigure(
    val kind: PortfolioFigureKind,
    val amount: Double?,
    val percent: Double?,
    val unit: String? = null,
    val incomplete: Boolean = false,
)

/**
 * Was die Übersicht in ihrer Höhe zeigt (Gesamtwert immer). Vorrang bei knapper Höhe:
 * Betrag > Kennzahlen > Fusszeile > ≈ USDT > Beträge der Kennzahlen — nie abgeschnitten.
 * [wide]: Kennzahlen als drei Spalten (sonst untereinander, ohne Beträge).
 */
data class PortfolioOverviewLayout(
    val wide: Boolean,
    val changeLine: Boolean,
    val figures: Boolean,
    val figureAmounts: Boolean,
    val footer: Boolean,
    val usdt: Boolean,
)

/**
 * Was die Liste zeigt: die Zeilen [shown] (nach Wert), wie viele Positionen nicht Platz haben
 * ([more], in der Fusszeile «+ 3 weitere»), ob die Fusszeile Platz hat und ob die Zeilen einzeilig
 * sind ([wide]: Logo, Kürzel, Anteil, Wert, Veränderung nebeneinander; sonst zweizeilig wie die Merkliste).
 */
data class PortfolioListLayout(
    val shown: List<PortfolioPosition>,
    val more: Int,
    val footer: Boolean,
    val wide: Boolean,
    /** Buchungen unter jedem gezeigten Coin (gleiche Reihenfolge wie [shown]; leer = keine). */
    val entries: List<List<PortfolioWidgetEntry>> = shown.map { emptyList() },
)

/**
 * MEIN Gewinn/Verlust einer Zeile bzw. des Totals über 24 h / seit Tagesbeginn: Betrag in der Anzeige-Einheit,
 * Prozent (null = Basis ~0) und Richtung nach dem Betrag (+1 / −1 / 0).
 */
data class PortfolioListTotalChange(val amount: Double, val percent: Double?, val direction: Int)

/**
 * Reine Regeln der Ansichten «Übersicht» und «Liste» (ohne Android, getestet in
 * PortfolioWidgetViewsTest; gespiegelt in `PortfolioWidgetViews`, iOS).
 */
object PortfolioWidgetViews {

    /** Rand 2 × 12 dp (widget_portfolio*.xml). */
    private const val PADDING_DP = 24f

    /** Abstand unter der Kopfzeile bis zur Liste bzw. über den Kennzahlen (dp). */
    const val LIST_GAP_DP = 4f
    const val FIGURES_GAP_DP = 4f

    /** Anteil der Total-Zeile: das ganze Portfolio. */
    const val LIST_TOTAL_SHARE = 100.0

    /** Zeilenhöhe ≈ 1.35 × Schriftgrösse (sp) — wie [PortfolioWidgetMath]. */
    private fun line(sp: Float, fontScale: Float): Float = sp * 1.35f * fontScale

    /**
     * Heute · 7 T · Gesamt aus dem gezeigten Stand ([PortfolioWidgetChart.displayed]): «Heute» ist die
     * Veränderung der Momentaufnahme (schon in der Anzeige-Einheit). In USDT («Nur USDT», ohne Devisenkurs) «7 T» und
     * «Gesamt» (± seit Kauf) aus den USDT-Werten. In einer Anzeigewährung wie die Gesamtkarte, nie USDT × heutiger
     * Kurs (der Einstand gilt zum Kurs der Erwerbstage): «Gesamt» = [PortfolioSnapshot.unrealizedDisplay], ohne ihn
     * «—»; «7 T» = [PortfolioSnapshot.weekDisplay], ohne ihn die USDT-Zahl mit Einheit «USDT».
     */
    fun figures(shown: PortfolioSnapshot): List<PortfolioFigure> {
        fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() }
        val today = PortfolioFigure(
            PortfolioFigureKind.TODAY, valid(shown.changeAmount), valid(shown.changePercent).takeIf { shown.changeAmount != null },
        )
        val weekUsdt = shown.periodChanges[PortfolioChartRange.WEEK.days]
        if (shown.currency == PortfolioWidgetChart.USDT_CODE) {
            val total = shown.unrealized
            return listOf(
                today,
                PortfolioFigure(PortfolioFigureKind.WEEK, valid(weekUsdt?.amount), valid(weekUsdt?.percent).takeIf { weekUsdt != null }),
                PortfolioFigure(PortfolioFigureKind.TOTAL, valid(total?.amount), valid(total?.percent).takeIf { total != null }),
            )
        }
        val weekShown = shown.weekDisplay
        val week = if (weekShown != null) {
            PortfolioFigure(PortfolioFigureKind.WEEK, valid(weekShown.amount), valid(weekShown.percent))
        } else {
            PortfolioFigure(
                PortfolioFigureKind.WEEK, valid(weekUsdt?.amount), valid(weekUsdt?.percent).takeIf { weekUsdt != null },
                unit = PortfolioWidgetChart.USDT_CODE.takeIf { weekUsdt != null },
            )
        }
        val total = shown.unrealizedDisplay
        return listOf(
            today,
            week,
            PortfolioFigure(
                PortfolioFigureKind.TOTAL, valid(total?.amount), valid(total?.percent).takeIf { total != null },
                incomplete = total?.skipped?.isNotEmpty() == true,
            ),
        )
    }

    /**
     * Coins, die in der sichtbaren «Heute»-Zahl fehlen (kein Vergleichskurs, Kauf ohne Preis —
     * [PortfolioSnapshot.todaySkipped]), alphabetisch — für den Hinweis «Heute ohne …» wie die
     * Gesamtkarte. Leer, wenn das Widget keine «Heute»-Zahl zeigt: Übersicht ([overview]) mit Betrag,
     * sonst nur beim Zeitraum 24 h (auch als Ersatz für 7 T ohne Tageskurse).
     */
    fun todaySkipped(shown: PortfolioSnapshot, plan: PortfolioChartPlan, overview: Boolean): List<String> {
        if (shown.changeAmount == null) return emptyList()
        val visible = overview || (plan.range == PortfolioChartRange.DAY && plan.changeAmount != null)
        return if (visible) shown.todaySkipped.filter { it.isNotBlank() }.distinct().sorted() else emptyList()
    }

    /**
     * Zeitraum der sichtbaren Tages-Zahl («7 T», «30 T», «1 J») und die Coins, die darin fehlen
     * ([PortfolioChange.skipped]) — für «7 T ohne …» wie «Gewinn 7 T» in der App (dieselbe Zahl, dieselbe Nennung).
     * Übersicht ([overview]): die «7 T»-Zahl; sonst die Zahl des gezeigten Tages-Zeitraums. null = keine solche Zahl
     * oder kein Coin fehlt.
     */
    fun periodSkipped(shown: PortfolioSnapshot, plan: PortfolioChartPlan, overview: Boolean): Pair<PortfolioChartRange, List<String>>? {
        val range = when {
            overview -> PortfolioChartRange.WEEK
            plan.range.daily && plan.changeAmount != null -> plan.range
            else -> return null
        }
        val coins = shown.periodChanges[range.days]?.skipped.orEmpty().filter { it.isNotBlank() }.distinct().sorted()
        return if (coins.isEmpty()) null else range to coins
    }

    /**
     * Zeitraum der Veränderung je Position in der Liste, so wie sie aufgenommen wurde
     * ([PortfolioSnapshot.stamp]): «seit 00:00» (Ortszeit/UTC) nur, wenn die Aufnahme ab Tagesbeginn
     * rechnete; sonst (auch «seit letztem Stand», Aufnahmen ohne Stempel) rollende 24 Stunden.
     */
    fun rowChangeBasis(snapshot: PortfolioSnapshot): ChangeBasis =
        snapshot.stamp?.basis?.takeIf { it.isDay } ?: ChangeBasis.ROLLING_24H

    /** Wie die Positionen im Portfolio-Tab (Standard): absteigend nach Wert, gleicher Wert nach Kürzel. */
    fun sortedByValue(positions: List<PortfolioPosition>): List<PortfolioPosition> =
        positions.filter { it.value.isFinite() }
            .sortedWith(compareByDescending<PortfolioPosition> { it.value }.thenBy { it.symbol })

    /**
     * Höhe einer Listenzeile (dp): einzeilig Logo 20 dp bzw. 12 sp; zweizeilig Logo 24 dp bzw.
     * 12 sp + 12 sp ([WidgetType.LABEL]); je 8 dp Abstand (Innenrand und Trennlinie).
     */
    fun rowHeightDp(wide: Boolean, fontScale: Float): Float =
        if (wide) maxOf(20f, WidgetType.LABEL * 1.3f * fontScale) + 8f
        else maxOf(24f, (WidgetType.LABEL + 12f) * 1.2f * fontScale + 2f) + 8f

    /**
     * Zeilen und Fusszeile in [available] (dp für Zeilen und Fusszeile; null = unbekannt): so viele
     * der [count] Positionen wie ganz passen; die Fusszeile nur, wenn sie keine Zeile kostet, die
     * sonst Platz hätte. Unbekannt: höchstens [PortfolioWidgetMath.LARGE_ROWS] Zeilen mit Fusszeile.
     * @return Zeilen und ob die Fusszeile erscheint
     */
    fun listRows(available: Float?, rowDp: Float, footerDp: Float, count: Int): Pair<Int, Boolean> {
        val wanted = count.coerceAtLeast(0)
        if (available == null) return minOf(wanted, PortfolioWidgetMath.LARGE_ROWS) to true
        if (rowDp <= 0f) return 0 to true
        val withoutFooter = minOf(wanted, floor(available / rowDp).toInt().coerceAtLeast(0))
        val withFooter = minOf(wanted, floor((available - footerDp) / rowDp).toInt().coerceAtLeast(0))
        return if (withFooter >= withoutFooter && available >= footerDp) withFooter to true else withoutFooter to false
    }

    /**
     * Total-Zeile der Liste, gestaltet wie die Listenzeilen ohne Logo (breit einzeilig 12 sp, sonst
     * zweizeilig 12 sp + 12 sp; je 8 dp Innenrand) und die Trennlinie darüber (1 dp).
     */
    fun listTotalHeightDp(fontScale: Float, wide: Boolean = false): Float =
        if (wide) WidgetType.LABEL * 1.3f * fontScale + 8f + 1f
        else (WidgetType.LABEL + 12f) * 1.2f * fontScale + 2f + 8f + 1f

    /**
     * Gewinn/Verlust «Heute» bzw. 24 h des Gesamtwerts ([PortfolioSnapshot.changeAmount], schon um Käufe/
     * Verkäufe berichtigt und nach der %-Basis geprüft) — die Summe der Zeilen ([rowPnl]); Richtung nach
     * dem Betrag. null ohne Betrag — dann bleibt die zweite Zeile rechts leer.
     */
    fun listTotalChange(shown: PortfolioSnapshot): PortfolioListTotalChange? {
        val amount = shown.changeAmount?.takeIf { it.isFinite() } ?: return null
        return PortfolioListTotalChange(amount, shown.changePercent?.takeIf { it.isFinite() }, PortfolioWidgetSeries.direction(amount))
    }

    /**
     * Gewinn/Verlust einer Position ([PortfolioPosition.pnlAmount]/[PortfolioPosition.pnlPercent]); null =
     * unbekannt (Kurs am Anfang oder Preis einer Transaktion fehlt) → «—».
     */
    fun rowPnl(position: PortfolioPosition): PortfolioListTotalChange? {
        val amount = position.pnlAmount?.takeIf { it.isFinite() } ?: return null
        return PortfolioListTotalChange(amount, position.pnlPercent?.takeIf { it.isFinite() }, PortfolioWidgetSeries.direction(amount))
    }

    /**
     * Texte für Gewinn/Verlust, lang → kurz (für [WidgetTextFit.firstFitting]; bei knapper Breite geht der
     * Betrag vor): «+1’250.00 CHF · +1.50%», «+1’250.00 CHF». «Beträge verbergen»: Betrag «•••», das
     * Prozent bleibt (wie bei den Gesamtzahlen). Ohne Prozent nur der Betrag.
     */
    fun pnlTexts(change: PortfolioListTotalChange, currency: String, hideAmounts: Boolean, locale: Locale = Locale.getDefault()): List<String> {
        val amount = PortfolioInsights.mask(PortfolioSnapshotMath.signedAmount(change.amount, currency, locale), hideAmounts)
        val percent = change.percent?.let { PortfolioSnapshotMath.signedPercent(it, locale) }
        return if (percent != null) listOf("$amount · $percent", amount) else listOf(amount)
    }

    /**
     * Liste in der Grösse [widthDp] × [heightDp] (0 = unbekannt): breit ab
     * [PortfolioWidgetMath.WIDE_MIN_WIDTH_DP] einzeilige Zeilen, sonst zweizeilige. Unter der
     * Kopfzeile [LIST_GAP_DP], dann die Zeilen, unten die Total-Zeile ([listTotalHeightDp]) und darunter
     * «Stand …» (samt «Heute ohne …»/«ohne 1 Coin») — beide IMMER: sie gehen den Zeilen vor, nie fällt
     * «Stand …» weg, um eine Zeile mehr zu zeigen.
     */
    fun listLayout(
        positions: List<PortfolioPosition>,
        others: Int,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        entryFilter: PortfolioWidgetEntryFilter = PortfolioWidgetEntryFilter.OFF,
    ): PortfolioListLayout {
        val sorted = sortedByValue(positions)
        val wide = widthDp >= PortfolioWidgetMath.WIDE_MIN_WIDTH_DP
        val rowDp = rowHeightDp(wide, fontScale)
        var available = 0f
        val rows = if (heightDp > 0) {
            available = (heightDp - PADDING_DP - PortfolioWidgetMath.headerHeightDp(fontScale) - LIST_GAP_DP -
                listTotalHeightDp(fontScale, wide) - PortfolioWidgetMath.footerHeightDp(fontScale)).coerceAtLeast(0f)
            minOf(sorted.size, floor(available / rowDp).toInt().coerceAtLeast(0))
        } else {
            minOf(sorted.size, PortfolioWidgetMath.LARGE_ROWS)
        }
        val shown = sorted.take(rows)
        // Buchungen nur im Platz, der unter den Coins frei bleibt (Grösse unbekannt: keine)
        val entries = PortfolioWidgetEntries.assign(
            shown.map { it.entries },
            entryFilter,
            available = if (heightDp > 0) (available - rows * rowDp).toDouble() else 0.0,
            rowHeight = entryHeightDp(fontScale).toDouble(),
        )
        return PortfolioListLayout(shown, sorted.size - shown.size + others.coerceAtLeast(0), footer = true, wide = wide, entries = entries)
    }

    /** Buchungszeile unter einem Coin: eine Zeile 12 sp und 2 × 1 dp Innenrand (widget_portfolio_list_entry.xml). */
    fun entryHeightDp(fontScale: Float): Float = line(ENTRY_SP, fontScale) + 2f

    /** Schriftgrösse der Buchungszeilen (sp). */
    const val ENTRY_SP = 12f

    /**
     * Kennzahlen (Text in der Kursfarbe, keine Pille), Abstand darüber: als Spalten Beschriftung 12 sp,
     * 2 dp, Wert 12 sp; als Zeilen je 2 dp und 12 sp (widget_portfolio_figure_*.xml).
     */
    fun figuresHeightDp(wide: Boolean, fontScale: Float): Float =
        if (wide) FIGURES_GAP_DP + line(12f, fontScale) + 2f + line(12f, fontScale)
        else FIGURES_GAP_DP + 3 * (line(12f, fontScale) + 2f)

    /**
     * Wert einer Kennzahl als Text (keine Pille): «↗ +1.50%» / «↘ −0.85%» — Pfeil nach der Richtung
     * [direction] (+1 / −1; nie nach dem Farbtausch), bei 0 ohne Pfeil («0.00%»).
     */
    fun figureText(direction: Int, percent: String): String = when {
        direction > 0 -> PriceFormat.ARROW_UP + " " + percent
        direction < 0 -> PriceFormat.ARROW_DOWN + " " + percent
        else -> percent
    }

    /** Betrag unter dem Wert (nur Spalten): 12 sp und 1 dp Abstand. */
    fun figureAmountHeightDp(fontScale: Float): Float = line(12f, fontScale) + 1f

    /**
     * Übersicht in der Grösse [widthDp] × [heightDp] (0 = unbekannt: alles Gewünschte). Ein Teil
     * erscheint nur, wenn er gewünscht ist, ganz passt und alle wichtigeren passen
     * (Vorrang siehe [PortfolioOverviewLayout]).
     */
    fun overviewLayout(widthDp: Int, heightDp: Int, fontScale: Float, hasChange: Boolean, wantUsdt: Boolean): PortfolioOverviewLayout {
        val wide = widthDp >= PortfolioWidgetMath.WIDE_MIN_WIDTH_DP
        if (heightDp <= 0) {
            return PortfolioOverviewLayout(wide, hasChange, figures = true, figureAmounts = wide, footer = true, usdt = wantUsdt)
        }
        var free = heightDp - PortfolioWidgetMath.baseHeightDp(fontScale)
        fun take(wanted: Boolean, need: Float): Boolean {
            val fits = wanted && need <= free
            if (fits) free -= need
            return fits
        }
        val change = take(hasChange, PortfolioWidgetMath.changeLineHeightDp(fontScale))
        val figures = (change || !hasChange) && take(true, figuresHeightDp(wide, fontScale))
        val footer = figures && take(true, PortfolioWidgetMath.footerHeightDp(fontScale))
        val usdt = footer && take(wantUsdt, PortfolioWidgetMath.usdtHeightDp(fontScale))
        val amounts = footer && take(wide, figureAmountHeightDp(fontScale))
        return PortfolioOverviewLayout(wide, change, figures, amounts, footer, usdt)
    }
}
