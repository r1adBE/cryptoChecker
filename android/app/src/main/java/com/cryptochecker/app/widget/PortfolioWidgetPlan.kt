package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot

// Typen des Portfolio-Widgets (Anzeige, Zeitraum, Balken, Plan) — wie iOS `Shared/Portfolio/PortfolioWidgetPlan.swift`.
// Die Regeln dazu stehen in [PortfolioWidgetChart].

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
