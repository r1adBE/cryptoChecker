package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic

/**
 * «Wird an der Börse nicht mehr gehandelt»: Die vollständige Kursliste der Börse führt das
 * Paar nicht mehr. Das ist ein Zustand, kein Fehler — die Zeile bleibt mit ihrem Hinweis
 * stehen, aber alles, was auf Marktdaten beruht, schweigt: kein ⚡, kein «Warum?», keine
 * Aktivitäts-Meldungen, keine 24-h-Änderung, kein Mini-Chart, nicht im Puls, keine Alarme
 * auf dem alten Kurs. Reines Kotlin (testbar, siehe NotTradedTest), gespiegelt in
 * `NotTraded.swift`.
 */
object NotTraded {
    /** Gespeicherter «Fehler» (`lastError`) für solche Paare — sprachunabhängig, die Anzeige übersetzt. */
    const val MARKER = "Wird an der Börse nicht mehr gehandelt"

    /** Wird das Paar mit diesem `lastError` nicht mehr gehandelt? */
    fun isMarker(lastError: String?): Boolean = lastError == MARKER

    /**
     * Fehlt ein Paar in der vollständigen Kursliste, wird es erst einmal einzeln abgefragt — eine
     * Liste kann ein Paar auch nur kurz auslassen. Ist es schon als nicht gehandelt gespeichert,
     * keine weitere Einzelabfrage: Es bleibt dabei, bis die Liste es wieder führt (sonst kostete
     * jeder Durchlauf je Paar eine Anfrage, die ohnehin scheitert — bei 131 Paaren rund 11 s).
     */
    fun askSingleWhenMissing(lastError: String?): Boolean = !isMarker(lastError)

    /**
     * Bestätigt die Einzelabfrage «nicht mehr gehandelt»? Ja, wenn die Börse geantwortet hat, aber
     * ohne Kurs: ihr Fehlertext (z. B. «Instrument ID does not exist»), eine Antwort ohne Kurs oder
     * HTTP 4xx (400, 404 …). Nein bei Kurs, Netz- und Zeitproblemen, Rate-Limit, Serverfehlern (5xx),
     * verweigertem Zugriff (401/403/451) oder fehlendem Adapter — das ist ein gewöhnlicher Fehler,
     * das Paar bleibt mit seinem letzten Kurs stehen.
     */
    fun confirmedBySingle(gotPrice: Boolean, error: String?): Boolean {
        if (gotPrice) return false
        return when (RefreshReportLogic.classify(error)) {
            RefreshFailure.NO_DATA, RefreshFailure.OTHER -> true
            RefreshFailure.SERVER -> RefreshReportLogic.httpStatus(error)
                ?.let { it in 400..499 && it !in NOT_CONFIRMING_4XX } ?: false
            else -> false
        }
    }

    /** 4xx ohne Aussage über das Paar: Anmeldung, Sperre, Proxy, Zeitgrenze, Rate-Limit, Rechtliches. */
    private val NOT_CONFIRMING_4XX = setOf(401, 403, 407, 408, 418, 429, 451)

    /** 24-h-Veränderung zum Anzeigen (Änderung, Puls, Widgets): null («—») bei nicht gehandelten Paaren. */
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
