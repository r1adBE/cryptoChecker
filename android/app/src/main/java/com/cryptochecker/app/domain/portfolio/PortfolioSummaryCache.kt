package com.cryptochecker.app.domain.portfolio

/**
 * Letzte Gesamtsicht ohne Tageskurse ([PortfolioCalculator.summarize] ohne `withdrawalPrice`) für Aufnahme, Frische-
 * Prüfung und Stundenkerzen des Portfolio-Widgets: Innerhalb einer Aktualisierung fragen mehrere Stellen mit denselben
 * Eingaben — gerechnet wird nur einmal. Schlüssel: Transaktionen (gleiche Liste), Kurse, Einstandsmethode und ob Ø
 * Herkunftsteile führt; jede andere Eingabe rechnet neu und ersetzt den Eintrag. Ergebnis immer gleich der direkten
 * Rechnung. Sicher aus mehreren Threads (ein unveränderlicher Eintrag; gleichzeitige Fehltreffer rechnen beide).
 */
class PortfolioSummaryCache(
    private val compute: (List<PortfolioTrade>, Map<String, Double>, CostMethod, Boolean) -> PortfolioSummary =
        { trades, prices, method, withParts -> PortfolioCalculator.summarize(trades, prices, method, withParts = withParts) },
) {
    private class Entry(
        val trades: List<PortfolioTrade>,
        val prices: Map<String, Double>,
        val method: CostMethod,
        val withParts: Boolean,
        val summary: PortfolioSummary,
    ) {
        fun matches(t: List<PortfolioTrade>, p: Map<String, Double>, m: CostMethod, parts: Boolean): Boolean =
            method == m && withParts == parts && (prices === p || prices == p) && (trades === t || trades == t)
    }

    @Volatile
    private var last: Entry? = null

    /** Gesamtsicht für diese Eingaben: aus dem Zwischenspeicher, wenn sie gleich sind, sonst neu gerechnet. */
    fun summarize(
        trades: List<PortfolioTrade>,
        prices: Map<String, Double>,
        method: CostMethod,
        withParts: Boolean = false,
    ): PortfolioSummary {
        last?.takeIf { it.matches(trades, prices, method, withParts) }?.let { return it.summary }
        val summary = compute(trades, prices, method, withParts)
        last = Entry(trades.toList(), prices.toMap(), method, withParts, summary)
        return summary
    }
}
