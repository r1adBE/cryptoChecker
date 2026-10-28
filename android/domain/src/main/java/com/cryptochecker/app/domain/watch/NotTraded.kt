package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.refresh.ExchangeFailure
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
     * Bestätigt die Einzelabfrage «nicht mehr gehandelt»? Nur bei einer klaren Aussage der Börse über
     * das Paar: Antwort ohne Kurs (auch Kurs 0), ausdrücklich unbekanntes Instrument
     * (z. B. «Instrument ID does not exist», «EQuery:Unknown asset pair», «Invalid symbol») oder
     * HTTP 400/404. Jeder andere Fehler — unlesbare Antwort, unbekannte Ausnahme, Netz, Zeit,
     * Rate-Limit, übrige 4xx/5xx, fehlender Adapter, interner Fehler ohne Text — ist ein gewöhnlicher
     * Fehler: Das Paar bleibt mit seinem letzten Kurs stehen, statt wegen einer kurzen Störung als
     * «nicht gehandelt» zu verstummen.
     *
     * [failure]: beim Abruf festgestellte Art. Sagt sie etwas aus, entscheidet sie; bei Lesefehler und Meldung der
     * Börse der Text wie bisher. [ExchangeFailure.Other] (interner Fehler ohne klaren Typ, z. B. eine Ausnahme ohne
     * Text) bestätigt nie — sonst verstummte ein Paar wegen eines eigenen Fehlers für Stunden (BulkDelisted). Ein
     * leerer Text bestätigt nur ohne Art (gespeicherte ältere Ergebnisse «ohne Kurs»); die Einzelabfrage selbst meldet
     * «ohne Kurs» als Lesefehler («Parsed ticker has no data»).
     *
     * Meldet der Leser in einer sonst gültigen Antwort «kein Eintrag für das Paar» ([isMissingEntry]), bestätigt das
     * ebenfalls — gleichwertig zu «Antwort ohne Kurs». Sicher, weil diese Prüfung nur nach einer vollständigen
     * Kursliste ohne das Paar läuft (PriceFetcher.confirmMissing): Zwei unabhängige Aussagen statt einer kurz
     * leeren Antwort — oder ohne Sammelabfrage, wenn die Einzelabfrage selbst diese Liste liest ([confirmWithoutBulk]).
     */
    fun confirmedBySingle(gotPrice: Boolean, error: String?, failure: ExchangeFailure? = null): Boolean {
        if (gotPrice) return false
        when (failure) {
            ExchangeFailure.UnknownInstrument -> return true
            is ExchangeFailure.HttpStatus -> return failure.code in CONFIRMING_HTTP
            ExchangeFailure.Timeout, ExchangeFailure.Offline, ExchangeFailure.Ssl, is ExchangeFailure.RateLimited,
            ExchangeFailure.Other -> return false
            ExchangeFailure.Parse, is ExchangeFailure.Message, null -> Unit
        }
        if (error.isNullOrBlank()) return failure == null
        return when (RefreshReportLogic.classify(error)) {
            RefreshFailure.NO_DATA -> true
            RefreshFailure.OTHER -> isUnknownInstrument(error) || isMissingEntry(error)
            RefreshFailure.SERVER -> RefreshReportLogic.httpStatus(error)?.let { it in CONFIRMING_HTTP } ?: false
            else -> false
        }
    }

    /**
     * Einzelabfrage ohne Sammelabfrage (zu wenige Paare der Börse): wie nach dem Fehlen in der vollständigen Liste mit
     * [confirmedBySingle] werten? Nur, wenn die Einzelabfrage selbst die vollständige Liste liest
     * (`Exchange.singleTickerReadsFullList`, z. B. Lighter) — dann ist ihr «kein Eintrag» dieselbe Aussage wie das
     * Fehlen in der Liste. Sonst fehlte die zweite, unabhängige Aussage: gewöhnlicher Fehler wie bisher.
     */
    fun confirmWithoutBulk(bulkTried: Boolean, singleReadsFullList: Boolean): Boolean = !bulkTried && singleReadsFullList

    /** Sagt der Fehlertext der Börse ausdrücklich, dass sie das Paar nicht kennt? */
    fun isUnknownInstrument(error: String?): Boolean {
        val lower = error?.lowercase() ?: return false
        return UNKNOWN_INSTRUMENT_HINTS.any { it in lower }
    }

    /**
     * Meldung des eigenen Lesers: Antwort gültig, aber ohne Eintrag für das Paar — `ExchangeParseException.NO_DATA`
     * («No data», leere Liste bzw. Paar fehlt im Objekt) oder Hyperliquid «Unknown coin: …». Genau dieser Text, nicht
     * jedes «no data» irgendwo in einer Meldung.
     */
    fun isMissingEntry(message: String?): Boolean {
        val text = message?.trim() ?: return false
        return text == NO_ENTRY || text.lowercase().startsWith(UNKNOWN_COIN)
    }

    /** = ExchangeParseException.NO_DATA (hier ohne Abhängigkeit vom Marktdaten-Modul); iOS `JSONError.noData`. */
    private const val NO_ENTRY = "No data"

    /** Anfang der Hyperliquid-Meldung für einen Coin, den die Antwort nicht führt (klein geschrieben). */
    private const val UNKNOWN_COIN = "unknown coin"

    /** HTTP-Status, die über das Paar etwas sagen (Anfrage abgelehnt bzw. nicht gefunden). */
    private val CONFIRMING_HTTP = setOf(400, 404)

    /** Fehlertexte der Börsen für ein unbekanntes Paar (klein geschrieben); iOS gleich. */
    private val UNKNOWN_INSTRUMENT_HINTS = listOf(
        "does not exist", "not exist", "unknown asset pair", "unknown pair", "invalid pair", "pair not found",
        "invalid symbol", "symbol invalid", "unknown symbol", "symbol not found", "not supported symbol",
        "invalid instrument", "unknown instrument", "instrument not found", "invalid currency pair",
        "invalid_currency_pair", "invalid market", "market not found", "delisted",
    )

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
