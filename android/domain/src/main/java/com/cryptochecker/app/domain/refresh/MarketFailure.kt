package com.cryptochecker.app.domain.refresh

/**
 * Art eines gescheiterten Börsenabrufs, festgestellt dort, wo der Fehler entsteht (HTTP-Schicht, Ausnahme;
 * siehe `toMarketFailure` in MarketError.kt). Wandert im Speicher neben dem Fehlertext mit
 * ([com.cryptochecker.app.domain.model.MarketTickerResult], [com.cryptochecker.app.domain.model.BulkTickers],
 * [Fetched]); gespeichert wird weiterhin nur der Text ([StoredError.sanitize]).
 *
 * Warum neben dem Text: Die Einordnung ([RefreshReportLogic.classify]) suchte bisher Stichwörter («ssl», «eof»,
 * «timeout» …) im Text — und Ausnahmetexte enthalten Hosts, Server-Antworten und Klassennamen. Mit der Art
 * entscheidet der Typ; die Stichwortsuche bleibt nur für Texte ohne Art (gespeicherte ältere Fehler, eigene
 * Marker, Meldungen der Börse). Reines Kotlin, gespiegelt in `MarketFailure.swift`.
 */
sealed interface MarketFailure {
    /** Zeitüberschreitung beim Verbinden oder Lesen (auch die eigene Wache). */
    data object Timeout : MarketFailure

    /** Keine Verbindung: Host unbekannt, Verbindung abgelehnt/abgebrochen, Netz weg. */
    data object Offline : MarketFailure

    /** TLS/SSL gescheitert (Zertifikat, Handschlag) — zählt wie bisher als «kein Netz». */
    data object Ssl : MarketFailure

    /** HTTP-Fehlercode ohne Bremse (z. B. 400, 404, 500). */
    data class HttpStatus(val code: Int) : MarketFailure

    /** Bremse der Börse: 429 (auch erkannt über [RateLimitSignal]) oder 418 «gesperrt». */
    data class RateLimited(val retryAfterSeconds: Long? = null, val code: Int = RateLimitSignal.HTTP_CODE) :
        MarketFailure

    /** Antwort unlesbar (JSON, Format). */
    data object Parse : MarketFailure

    /** Die Börse sagt ausdrücklich, dass sie das Paar nicht kennt ([com.cryptochecker.app.domain.watch.NotTraded]). */
    data object UnknownInstrument : MarketFailure

    /** Andere Fehlermeldung der Börse; [detail] ist ihr Text. */
    data class Exchange(val detail: String) : MarketFailure

    /** Nichts davon. */
    data object Other : MarketFailure
}

/**
 * Ursache für Bericht, Pause und Anzeige; null = die Art legt sie nicht fest, dann entscheidet der Text
 * ([RefreshReportLogic.classify] mit Stichwörtern). Parse, Exchange und Other tragen ihre Aussage im Text (die
 * Meldung der Börse, eine eigene Kennung wie «Parsed ticker has no data»): dort bleibt die Einordnung wie bisher.
 */
fun MarketFailure.refreshFailure(): RefreshFailure? = when (this) {
    MarketFailure.Timeout -> RefreshFailure.TIMEOUT
    MarketFailure.Offline, MarketFailure.Ssl -> RefreshFailure.OFFLINE
    is MarketFailure.HttpStatus ->
        if (ExchangeBackoff.isRateLimitCode(code)) RefreshFailure.RATE_LIMIT else RefreshFailure.SERVER
    is MarketFailure.RateLimited -> RefreshFailure.RATE_LIMIT
    MarketFailure.UnknownInstrument -> RefreshFailure.OTHER
    MarketFailure.Parse, is MarketFailure.Exchange, MarketFailure.Other -> null
}

/** HTTP-Status der Art (Fehlercode oder Bremse), sonst null. */
val MarketFailure.httpStatus: Int?
    get() = when (this) {
        is MarketFailure.HttpStatus -> code
        is MarketFailure.RateLimited -> code
        else -> null
    }
