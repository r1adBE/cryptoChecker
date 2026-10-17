package com.cryptochecker.app.domain.watch

/**
 * Vorübergehende Ansichten der Merkliste, zusätzlich zu «Alle», Favoriten und Gruppen
 * ([WatchFilter]): ⚡ (Paare, bei denen gerade etwas passiert; Chip bei den Gruppen), «nur
 * veraltete» (Tipp auf den Status «10 von 30 veraltet») und «nur deaktivierte» (Tipp auf
 * «3 deaktiviert» neben dem Status). Nicht gespeichert — sie zeigen Momentanes und enden von
 * selbst, wenn nichts mehr passt. Wie `QuickView.swift` (iOS).
 */
enum class QuickView { ACTIVITY, STALE, DISABLED }

/**
 * Deaktivierte Paare (`WatchEntity.disabled`): keine Abfragen, keine Meldungen, keine Alarme.
 * Sie zählen nirgends mit — weder als «veraltet» noch als gescheitert, nicht im Puls und nicht
 * für «Alle Kurse aktuell». Reines Kotlin (testbar, siehe QuickViewTest), gespiegelt in
 * `QuickView.swift` (`DisabledView`).
 */
object DisabledView {
    /** Nur die aktiven Einträge (alles, was abgefragt, gezählt und gemeldet wird). */
    fun <T> active(items: List<T>, disabled: (T) -> Boolean): List<T> =
        if (items.none(disabled)) items else items.filterNot(disabled)

    /** Nur die deaktivierten Einträge (Ansicht [QuickView.DISABLED]). */
    fun <T> only(items: List<T>, disabled: (T) -> Boolean): List<T> = items.filter(disabled)

    /** Anzahl deaktivierter Einträge (Hinweis «3 deaktiviert»; 0 = kein Hinweis). */
    fun <T> count(items: List<T>, disabled: (T) -> Boolean): Int = items.count(disabled)

    /**
     * Ohne frischen Kurs («veraltet»)? Nie bei deaktivierten oder nicht mehr gehandelten Paaren —
     * gleiche Regel für Zeile, Status und die Ansicht «nur veraltete».
     */
    fun isStale(disabled: Boolean, notTraded: Boolean, lastUpdate: Long, now: Long, staleAfter: Long): Boolean =
        !disabled && !notTraded && (lastUpdate <= 0 || now - lastUpdate > staleAfter)
}

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
