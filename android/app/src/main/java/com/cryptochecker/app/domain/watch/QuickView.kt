package com.cryptochecker.app.domain.watch

/**
 * Vorübergehende Ansichten der Merkliste, zusätzlich zu «Alle», Favoriten und Gruppen
 * ([WatchFilter]): ⚡ (Paare, bei denen gerade etwas passiert; Chip bei den Gruppen) und «nur
 * veraltete» (Tipp auf den Status «10 von 30 veraltet»). Nicht gespeichert — sie zeigen
 * Momentanes und enden von selbst, wenn nichts mehr passt. Wie `QuickView.swift` (iOS).
 */
enum class QuickView { ACTIVITY, STALE }

object ActivityView {
    /**
     * Paare für den ⚡-Chip aus [hot] (stärkste Signale zuerst): mit [limit] (Empfindlichkeit
     * «Weniger») nur die Paare der [limit] stärksten Coins — ein Coin an mehreren Börsen zählt
     * einmal, bleibt aber mit allen seinen Paaren sichtbar.
     */
    fun <T> pick(hot: List<T>, baseAsset: (T) -> String, limit: Int?): List<T> {
        if (limit == null) return hot
        val coins = hot.map { baseAsset(it).uppercase() }.distinct().take(limit).toSet()
        return hot.filter { baseAsset(it).uppercase() in coins }
    }
}
