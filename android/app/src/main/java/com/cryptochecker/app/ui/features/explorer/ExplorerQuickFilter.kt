package com.cryptochecker.app.ui.features.explorer

import com.cryptochecker.marketdata.model.Ticker

/** Schnellfilter der Paarliste einer Börse: «Gewinner», «Verlierer», «Volumen». */
enum class QuickFilter { GAINERS, LOSERS, VOLUME }

/** Kennzahlen eines Paars aus der Sammelabfrage der Börse; null = nicht geliefert. */
data class PairStats(val change24h: Double?, val quoteVolume: Double?)

/**
 * Sortierhilfen der Schnellfilter — wie `ExplorerQuickFilters` in iOS (`ExplorerPairSearch.swift`).
 * Ohne Filter bleibt die Reihenfolge unverändert; Paare ohne Wert stehen hinten (in ihrer
 * bisherigen Reihenfolge).
 */
object QuickFilters {

    /** Kennzahlen aus einem Ticker: 24-h-Veränderung; Volumen in der Gegenwährung (sonst Menge × Kurs). */
    fun statsOf(ticker: Ticker): PairStats {
        val change = ticker.change24hPercent?.takeIf { it.isFinite() }
        val volume = when {
            ticker.volQuote.isFinite() && ticker.volQuote > 0.0 -> ticker.volQuote
            ticker.vol.isFinite() && ticker.vol > 0.0 && ticker.last.isFinite() && ticker.last > 0.0 ->
                ticker.vol * ticker.last
            else -> null
        }
        return PairStats(change, volume)
    }

    /** Welche Filter die Daten tragen: «Gewinner»/«Verlierer» nur mit Veränderung, «Volumen» nur mit Volumen. */
    fun available(stats: Collection<PairStats?>): Set<QuickFilter> {
        val result = LinkedHashSet<QuickFilter>()
        if (stats.any { it?.change24h != null }) {
            result += QuickFilter.GAINERS
            result += QuickFilter.LOSERS
        }
        if (stats.any { it?.quoteVolume != null }) result += QuickFilter.VOLUME
        return result
    }

    /** Wert, nach dem [filter] sortiert. */
    fun value(stats: PairStats?, filter: QuickFilter): Double? = when (filter) {
        QuickFilter.GAINERS, QuickFilter.LOSERS -> stats?.change24h
        QuickFilter.VOLUME -> stats?.quoteVolume
    }

    /** Sortiert stabil; [filter] null = unveränderte Reihenfolge. */
    fun <T> sort(items: List<T>, filter: QuickFilter?, stats: (T) -> PairStats?): List<T> {
        if (filter == null) return items
        val withValue = items.map { it to value(stats(it), filter) }
        // Nur Paare mit Wert sortieren (ohne «!!»: der Wert ist hier schon nicht-null)
        val known = withValue.mapNotNull { (item, v) -> v?.let { item to it } }
        val sorted = when (filter) {
            QuickFilter.LOSERS -> known.sortedBy { it.second }
            else -> known.sortedByDescending { it.second }
        }
        return sorted.map { it.first } + withValue.filter { it.second == null }.map { it.first }
    }

    /** Antippen: anderer Filter wird gewählt, derselbe noch einmal hebt ihn auf. */
    fun toggle(current: QuickFilter?, tapped: QuickFilter): QuickFilter? =
        if (current == tapped) null else tapped
}
