package com.cryptochecker.app.widget

/**
 * Welche Anzeige und welcher Zeitraum je Ansicht angeboten werden und wirken — wie iOS `PortfolioWidgetOptions`
 * (Paritäts-Fixture `widget_portfolio_options.json`). Gespeichert bleibt die Wahl unverändert, damit ein Wechsel der
 * Ansicht nichts verliert; umgedeutet wird nur beim Zeichnen und in der Einrichtung.
 *  - [PortfolioWidgetView.LIST]: hat keine USDT-Zeile, darum nur «Nur USDT» und «Umrechnung»;
 *    «Umrechnung + USDT» wirkt wie «Umrechnung». Den Zeitraum zeigt die Einrichtung nicht.
 *  - [PortfolioWidgetView.OVERVIEW]: «Heute» und «7 T» stehen fest als Kennzahlen darunter, darum nur 30 T und 1 J;
 *    24 h und 7 T wirken wie 30 T ([OVERVIEW_DEFAULT_RANGE]).
 *  - [PortfolioWidgetView.CHART]: alle Anzeigen und Zeiträume.
 */
object PortfolioWidgetOptions {

    /** Übersicht ohne passende Wahl (auch neue Widgets mit 24 h als Standard). */
    val OVERVIEW_DEFAULT_RANGE = PortfolioChartRange.MONTH

    /** Angebotene Anzeigen der Ansicht [view], in dieser Reihenfolge. */
    fun displays(view: PortfolioWidgetView): List<PortfolioDisplay> = when (view) {
        PortfolioWidgetView.LIST -> listOf(PortfolioDisplay.USDT, PortfolioDisplay.CONVERSION)
        PortfolioWidgetView.OVERVIEW, PortfolioWidgetView.CHART -> PortfolioDisplay.entries
    }

    /** Angebotene Zeiträume der Ansicht [view]; leer = die Einrichtung zeigt keinen Zeitraum. */
    fun ranges(view: PortfolioWidgetView): List<PortfolioChartRange> = when (view) {
        PortfolioWidgetView.OVERVIEW -> listOf(PortfolioChartRange.MONTH, PortfolioChartRange.YEAR)
        PortfolioWidgetView.CHART -> PortfolioChartRange.entries
        PortfolioWidgetView.LIST -> emptyList()
    }

    /** Wirksame Anzeige der gespeicherten Wahl [chosen] in der Ansicht [view]. */
    fun effectiveDisplay(view: PortfolioWidgetView, chosen: PortfolioDisplay): PortfolioDisplay =
        if (chosen in displays(view)) chosen else PortfolioDisplay.CONVERSION

    /** Wirksamer Zeitraum der gespeicherten Wahl [chosen] in der Ansicht [view] (Liste: unverändert). */
    fun effectiveRange(view: PortfolioWidgetView, chosen: PortfolioChartRange): PortfolioChartRange = when (view) {
        PortfolioWidgetView.OVERVIEW -> if (chosen in ranges(view)) chosen else OVERVIEW_DEFAULT_RANGE
        PortfolioWidgetView.CHART, PortfolioWidgetView.LIST -> chosen
    }
}
