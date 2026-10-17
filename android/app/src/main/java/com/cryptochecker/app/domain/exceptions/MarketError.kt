package com.cryptochecker.app.domain.exceptions

import android.os.DeadSystemException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

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
 * Abbruch der Coroutine (auch von OkHttp gemeldet) und ein herunterfahrendes System dürfen nicht
 * als gewöhnlicher Börsenfehler enden: dann sofort weiterwerfen, sonst nichts tun.
 */
fun Exception.rethrowIfCritical() {
    val mustPropagate = when (this) {
        is CancellationException -> true
        is DeadSystemException -> true
        else -> false
    }
    if (mustPropagate) throw this
}

/** Ordnet einen beliebigen Fehler einer [MarketError]-Art zu (Zeitüberschreitung, Netz, unbekannt). */
fun parseMarketError(ex: Throwable): MarketError = when (ex) {
    is MarketError -> ex
    // Zeitüberschreitung beim Lesen/Verbinden (Unterklasse von IOException, darum zuerst)
    is InterruptedIOException -> TimeoutError(ex)
    // Übrige Ein-/Ausgabefehler: kein Netz, Host unbekannt, Verbindung getrennt …
    is IOException -> NetworkError(ex)
    else -> UnknownMarketError(ex)
}
