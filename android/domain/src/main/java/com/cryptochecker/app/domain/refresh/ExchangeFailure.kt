package com.cryptochecker.app.domain.refresh

/**
 * Art eines gescheiterten Börsenabrufs, festgestellt dort, wo der Fehler entsteht (HTTP-Schicht, Ausnahme;
 * siehe `toExchangeFailure` in ExchangeError.kt). Wandert im Speicher neben dem Fehlertext mit
 * ([com.cryptochecker.app.domain.model.ExchangeTickerResult], [com.cryptochecker.app.domain.model.BulkTickers],
 * [Fetched]); gespeichert wird nur der Text ([StoredError.sanitize]).
 *
 * Warum neben dem Text: Die Einordnung ([RefreshReportLogic.classify]) nur über Stichwörter («ssl», «eof»,
 * «timeout» …) im Text wäre unsicher — Ausnahmetexte enthalten Hosts, Server-Antworten und Klassennamen. Mit der Art
 * entscheidet der Typ; die Stichwortsuche bleibt nur für Texte ohne Art (gespeicherte Fehlertexte, eigene
 * Marker, Meldungen der Börse). Reines Kotlin, gespiegelt in `ExchangeFailure.swift`.
 */
sealed interface ExchangeFailure {
    /** Zeitüberschreitung beim Verbinden oder Lesen (auch die eigene Wache). */
    data object Timeout : ExchangeFailure

    /** Keine Verbindung: Host unbekannt, Verbindung abgelehnt/abgebrochen, Netz weg. */
    data object Offline : ExchangeFailure

    /** TLS/SSL gescheitert (Zertifikat, Handschlag) — zählt als «kein Netz». */
    data object Ssl : ExchangeFailure

    /** HTTP-Fehlercode ohne Bremse (z. B. 400, 404, 500). */
    data class HttpStatus(val code: Int) : ExchangeFailure

    /** Bremse der Börse: 429 (auch erkannt über [RateLimitSignal]) oder 418 «gesperrt». */
    data class RateLimited(val retryAfterSeconds: Long? = null, val code: Int = RateLimitSignal.HTTP_CODE) :
        ExchangeFailure

    /** Antwort unlesbar (JSON, Format). */
    data object Parse : ExchangeFailure

    /** Die Börse sagt ausdrücklich, dass sie das Paar nicht kennt ([com.cryptochecker.app.domain.watch.NotTraded]). */
    data object UnknownInstrument : ExchangeFailure

    /** Andere Fehlermeldung der Börse; [detail] ist ihr Text. */
    data class Message(val detail: String) : ExchangeFailure

    /** Nichts davon. */
    data object Other : ExchangeFailure
}

/**
 * Ursache für Bericht, Pause und Anzeige; null = die Art legt sie nicht fest, dann entscheidet der Text
 * ([RefreshReportLogic.classify] mit Stichwörtern). Parse, Message und Other tragen ihre Aussage im Text (die
 * Meldung der Börse, eine eigene Kennung wie «Parsed ticker has no data»): dort entscheidet der Text.
 */
fun ExchangeFailure.refreshFailure(): RefreshFailure? = when (this) {
    ExchangeFailure.Timeout -> RefreshFailure.TIMEOUT
    ExchangeFailure.Offline, ExchangeFailure.Ssl -> RefreshFailure.OFFLINE
    is ExchangeFailure.HttpStatus ->
        if (ExchangeBackoff.isRateLimitCode(code)) RefreshFailure.RATE_LIMIT else RefreshFailure.SERVER
    is ExchangeFailure.RateLimited -> RefreshFailure.RATE_LIMIT
    ExchangeFailure.UnknownInstrument -> RefreshFailure.OTHER
    ExchangeFailure.Parse, is ExchangeFailure.Message, ExchangeFailure.Other -> null
}

/** HTTP-Status der Art (Fehlercode oder Bremse), sonst null. */
val ExchangeFailure.httpStatus: Int?
    get() = when (this) {
        is ExchangeFailure.HttpStatus -> code
        is ExchangeFailure.RateLimited -> code
        else -> null
    }
