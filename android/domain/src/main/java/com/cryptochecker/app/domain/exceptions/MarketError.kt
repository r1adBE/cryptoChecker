package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.exceptions.MarketParseException
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
open class MarketError : Exception {
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

/** Ordnet einen beliebigen Fehler einer [MarketError]-Art zu (Zeitüberschreitung, Netz, unbekannt). */
fun parseMarketError(ex: Throwable): MarketError = when (ex) {
    is MarketError -> ex
    // Zeitüberschreitung beim Lesen/Verbinden (Unterklasse von IOException, darum zuerst)
    is InterruptedIOException -> TimeoutError(ex)
    // Übrige Ein-/Ausgabefehler: kein Netz, Host unbekannt, Verbindung getrennt …
    is IOException -> NetworkError(ex)
    else -> UnknownMarketError(ex)
}

/**
 * Art des Fehlers für die Einordnung ([MarketFailure]), am Ort des Fehlers bestimmt statt später aus dem Text.
 * Nur Typen mit eindeutiger Aussage bekommen eine feste Art; der Rest wird [MarketFailure.Other] (oder
 * [MarketFailure.Exchange] mit der Meldung der Börse) und bleibt bei der Einordnung über den Text — so ändert
 * sich für Fehler ohne klaren Typ nichts.
 */
fun Throwable.toMarketFailure(): MarketFailure = when (this) {
    is HttpMarketError ->
        if (ExchangeBackoff.isRateLimitCode(httpCode)) MarketFailure.RateLimited(retryAfterSeconds, httpCode)
        else MarketFailure.HttpStatus(httpCode)
    // Hülle: die Ursache sagt, was geschah (Host unbekannt, TLS, Zeit …)
    is TimeoutError -> MarketFailure.Timeout
    is NetworkError -> cause?.toMarketFailure()?.takeUnless { it == MarketFailure.Other } ?: MarketFailure.Other
    is UnknownMarketError -> cause?.toMarketFailure() ?: MarketFailure.Other
    is ParseError -> MarketFailure.Parse
    is UserFriendlyMarketError -> exchangeFailure(message)
    // Zeitüberschreitung (SocketTimeoutException, Wache von callMarket) vor den übrigen Ein-/Ausgabefehlern
    is InterruptedIOException -> MarketFailure.Timeout
    is SSLException -> MarketFailure.Ssl
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is EOFException -> MarketFailure.Offline
    // «Kein Eintrag für das Paar» in gültiger Antwort: Aussage über das Paar (NotTraded.isMissingEntry)
    is MarketParseException ->
        if (NotTraded.isMissingEntry(message)) MarketFailure.UnknownInstrument else MarketFailure.Parse
    // Eigenes JSON in :domain und org.json der Parser in :data (nach Namen, ohne Android-Typ in :domain)
    is JsonException -> MarketFailure.Parse
    else -> if (javaClass.name == ORG_JSON_EXCEPTION) MarketFailure.Parse else MarketFailure.Other
}

private const val ORG_JSON_EXCEPTION = "org.json.JSONException"

/**
 * Meldung der Börse ([UserFriendlyMarketError]): eigene Kennungen («Response data is empty» …) bleiben
 * [MarketFailure.Other] (der Text ordnet sie ein); sagt die Börse, dass sie das Paar nicht kennt,
 * [MarketFailure.UnknownInstrument], sonst [MarketFailure.Exchange].
 */
private fun exchangeFailure(message: String?): MarketFailure = when {
    message.isNullOrBlank() || message.trim() in OWN_MARKERS -> MarketFailure.Other
    RefreshReportLogic.classify(message) == RefreshFailure.OTHER && NotTraded.isUnknownInstrument(message) ->
        MarketFailure.UnknownInstrument
    else -> MarketFailure.Exchange(message)
}

private val OWN_MARKERS = setOf(
    UserFriendlyMarketError.EMPTY_RESPONSE,
    UserFriendlyMarketError.NO_TICKER_DATA,
    UserFriendlyMarketError.UNKNOWN_EMPTY,
    UserFriendlyMarketError.MARKET_UNAVAILABLE,
)
