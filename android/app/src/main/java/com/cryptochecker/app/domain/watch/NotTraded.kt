package com.cryptochecker.app.domain.watch

/**
 * «Wird an der Börse nicht mehr gehandelt»: Die vollständige Kursliste der Börse führt das
 * Paar nicht mehr. Das ist ein Zustand, kein Fehler — die Zeile bleibt mit ihrem Hinweis
 * stehen, aber alles, was auf Marktdaten beruht, schweigt: kein ⚡, kein «Warum?», keine
 * Aktivitäts-Meldungen, keine 24-h-Pille, kein Mini-Chart, nicht im Puls, keine Alarme
 * auf dem alten Kurs. Reines Kotlin (testbar, siehe NotTradedTest), gespiegelt in
 * `NotTraded.swift`.
 */
object NotTraded {
    /** Gespeicherter «Fehler» (`lastError`) für solche Paare — sprachunabhängig, die Anzeige übersetzt. */
    const val MARKER = "Wird an der Börse nicht mehr gehandelt"

    /** Wird das Paar mit diesem `lastError` nicht mehr gehandelt? */
    fun isMarker(lastError: String?): Boolean = lastError == MARKER

    /** 24-h-Veränderung zum Anzeigen (Pille, Puls, Widgets): null («—») bei nicht gehandelten Paaren. */
    fun shownChange24h(lastError: String?, change24h: Double?): Double? =
        if (isMarker(lastError)) null else change24h

    /** Ids der nicht gehandelten Einträge. */
    fun <T> ids(items: List<T>, id: (T) -> Long, lastError: (T) -> String?): Set<Long> =
        items.filter { isMarker(lastError(it)) }.mapTo(LinkedHashSet()) { id(it) }

    /** [map] ohne die Einträge nicht gehandelter Paare (z. B. gespeicherte Aktivitäts-Signale). */
    fun <V> withoutIds(map: Map<Long, V>, notTraded: Set<Long>): Map<Long, V> =
        if (notTraded.isEmpty() || map.keys.none { it in notTraded }) map
        else map.filterKeys { it !in notTraded }
}
