package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.app.util.json.JsonException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Oberklasse aller Fehler beim Abruf einer Börse. Die vier Konstruktoren entsprechen denen von
 * [Exception] — wichtig: Nur mit Ursache übernimmt [message] deren Text (Fehler werden als Text
 * gespeichert und angezeigt).
 */
open class ExchangeError : Exception {
    constructor() : super()
    constructor(cause: Throwable?) : super(cause)
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}

/**
 * Abbruch der eigenen Coroutine (auch äusseres withTimeout) und ein herunterfahrendes System dürfen
 * nicht als gewöhnlicher Börsenfehler enden: dann sofort weiterwerfen, sonst nichts tun. Eine
 * abgelaufene INNERE Frist (TimeoutCancellationException, während die eigene Coroutine noch läuft)
 * ist dagegen ein gewöhnlicher Fehler — sonst risse eine einzelne Teilanfrage, die scheitern darf,
 * den ganzen Durchlauf mit (gleich wie [com.cryptochecker.app.data.remote.util.runCatchingCancellable]).
 */
suspend fun Exception.rethrowIfCritical() {
    // android.os.DeadSystemException (System-Server tot) nach Namen, ohne Android-Typ in :domain
    if (javaClass.name == DEAD_SYSTEM) throw this
    if (this is CancellationException) {
        // Selbst abgebrochen: wirft hier
        currentCoroutineContext().ensureActive()
        if (this !is TimeoutCancellationException) throw this
    }
}

private const val DEAD_SYSTEM = "android.os.DeadSystemException"

/** Ordnet einen beliebigen Fehler einer [ExchangeError]-Art zu (Zeitüberschreitung, Netz, unbekannt). */
fun parseExchangeError(ex: Throwable): ExchangeError = when (ex) {
    is ExchangeError -> ex
    // Zeitüberschreitung beim Lesen/Verbinden (Unterklasse von IOException, darum zuerst)
    is InterruptedIOException -> TimeoutError(ex)
    // Übrige Ein-/Ausgabefehler: kein Netz, Host unbekannt, Verbindung getrennt …
    is IOException -> NetworkError(ex)
    else -> UnknownExchangeError(ex)
}

/**
 * Art des Fehlers für die Einordnung ([ExchangeFailure]), am Ort des Fehlers bestimmt statt später aus dem Text.
 * Nur Typen mit eindeutiger Aussage bekommen eine feste Art; der Rest wird [ExchangeFailure.Other] (oder
 * [ExchangeFailure.Message] mit der Meldung der Börse) und bleibt bei der Einordnung über den Text — so ändert
 * sich für Fehler ohne klaren Typ nichts.
 */
fun Throwable.toExchangeFailure(): ExchangeFailure = when (this) {
    is HttpExchangeError ->
        if (ExchangeBackoff.isRateLimitCode(httpCode)) ExchangeFailure.RateLimited(retryAfterSeconds, httpCode)
        else ExchangeFailure.HttpStatus(httpCode)
    // Hülle: die Ursache sagt, was geschah (Host unbekannt, TLS, Zeit …)
    is TimeoutError -> ExchangeFailure.Timeout
    is NetworkError -> cause?.toExchangeFailure()?.takeUnless { it == ExchangeFailure.Other } ?: ExchangeFailure.Other
    is UnknownExchangeError -> cause?.toExchangeFailure() ?: ExchangeFailure.Other
    is ParseError -> ExchangeFailure.Parse
    is UserFriendlyExchangeError -> exchangeFailure(message)
    // Zeitüberschreitung (SocketTimeoutException, Wache von callExchange) vor den übrigen Ein-/Ausgabefehlern
    is InterruptedIOException -> ExchangeFailure.Timeout
    is SSLException -> ExchangeFailure.Ssl
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is EOFException -> ExchangeFailure.Offline
    // «Kein Eintrag für das Paar» in gültiger Antwort: Aussage über das Paar (NotTraded.isMissingEntry)
    is ExchangeParseException ->
        if (NotTraded.isMissingEntry(message)) ExchangeFailure.UnknownInstrument else ExchangeFailure.Parse
    // Eigenes JSON in :domain und org.json der Parser in :data (nach Namen, ohne Android-Typ in :domain)
    is JsonException -> ExchangeFailure.Parse
    else -> if (javaClass.name == ORG_JSON_EXCEPTION) ExchangeFailure.Parse else ExchangeFailure.Other
}

private const val ORG_JSON_EXCEPTION = "org.json.JSONException"

/**
 * Meldung der Börse ([UserFriendlyExchangeError]): eigene Kennungen («Response data is empty» …) bleiben
 * [ExchangeFailure.Other] (der Text ordnet sie ein); sagt die Börse, dass sie das Paar nicht kennt,
 * [ExchangeFailure.UnknownInstrument], sonst [ExchangeFailure.Message].
 */
private fun exchangeFailure(message: String?): ExchangeFailure = when {
    message.isNullOrBlank() || message.trim() in OWN_MARKERS -> ExchangeFailure.Other
    RefreshReportLogic.classify(message) == RefreshFailure.OTHER && NotTraded.isUnknownInstrument(message) ->
        ExchangeFailure.UnknownInstrument
    else -> ExchangeFailure.Message(message)
}

private val OWN_MARKERS = setOf(
    UserFriendlyExchangeError.EMPTY_RESPONSE,
    UserFriendlyExchangeError.NO_TICKER_DATA,
    UserFriendlyExchangeError.UNKNOWN_EMPTY,
    UserFriendlyExchangeError.EXCHANGE_UNAVAILABLE,
)
