package com.cryptochecker.app.domain.live

/**
 * Wer den Live-Strom gerade braucht: die Merkliste (Paare der gezeigten Gruppe) und die Paarseite (ihr eines Paar, auch
 * wenn es in der gezeigten Gruppe fehlt — Seite aus Markt, Mitteilung oder Widget). Der Strom läuft, solange einer der
 * beiden zu sehen ist; beim Wechsel Merkliste → Paarseite überschneiden sich Start der einen und Stopp der anderen, ein
 * einzelner Schalter fiele dabei auf «aus». Paare: die der Merkliste, das der Seite dazu (dasselbe Paar nur einmal) —
 * so bleiben bestehende Verbindungen beim Öffnen und Schliessen der Seite stehen. Nicht threadsicher (Hauptthread).
 */
class LiveDemand {
    /** Wer den Strom braucht. */
    enum class Owner { WATCHLIST, PAIR_PAGE }

    private var listPairs: List<LivePair> = emptyList()
    private var pagePair: LivePair? = null
    private val visible = HashSet<Owner>()

    /** Paare der gezeigten Merkliste; Rückgabe: alle Paare für den Strom. */
    fun setListPairs(pairs: List<LivePair>): List<LivePair> {
        listPairs = pairs
        return pairs()
    }

    /** Paar der offenen Paarseite (null = keine bzw. deaktiviert); Rückgabe: alle Paare für den Strom. */
    fun setPagePair(pair: LivePair?): List<LivePair> {
        pagePair = pair
        return pairs()
    }

    /** [owner] zu sehen bzw. nicht mehr; Rückgabe: braucht noch jemand den Strom? */
    fun setVisible(owner: Owner, on: Boolean): Boolean {
        if (on) visible += owner else visible -= owner
        return visible.isNotEmpty()
    }

    /** Paare für den Strom: die der Merkliste, das der Seite dazu (nur, wenn es dort fehlt). */
    fun pairs(): List<LivePair> {
        val page = pagePair ?: return listPairs
        return if (listPairs.any { it.watchId == page.watchId }) listPairs else listPairs + page
    }
}
