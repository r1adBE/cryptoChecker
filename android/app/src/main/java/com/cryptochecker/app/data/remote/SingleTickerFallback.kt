package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import timber.log.Timber

/**
 * Ausweg eines Einzelkurses, dessen Anfrage mit einem Serverfehler (HTTP 5xx) scheitert: Nennt die Börse eine
 * Ausweichadresse ([Market.tickerFallbackAddress], One Trading: die Sammelliste), kommt der Kurs von dort. Bringt
 * auch das keinen gültigen Kurs (Paar fehlt, Netz, unlesbar), bleibt der ursprüngliche Serverfehler — er bestätigt
 * wie bisher nie «nicht mehr gehandelt» (NotTraded). 4xx (sagt etwas über das Paar) und Bremsen (429/418) nie.
 * iOS: `SingleTickerFallback.swift`.
 */
internal object SingleTickerFallback {

    /** Nur Serverfehler lösen den Ausweg aus. */
    fun applies(httpCode: Int): Boolean = httpCode in SERVER_ERRORS

    /**
     * Liest den Kurs über die Ausweichadresse in [ticker]; [load] holt die Adresse (GET). Ohne Ausweg oder ohne
     * gültigen Kurs wird [serverError] geworfen; ein eigener Abbruch geht durch.
     */
    suspend fun load(
        market: Market,
        call: Int,
        info: PairQuery,
        ticker: Ticker,
        serverError: HttpMarketError,
        load: suspend (String) -> String,
    ) {
        val address = market.tickerFallbackAddress(call, info)
        if (!applies(serverError.httpCode) || address.isNullOrEmpty()) throw serverError
        try {
            market.readTickerFallback(call, load(address), ticker, info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            Timber.w("Ausweg des Einzelkurses ohne Kurs (%s): %s", market.key, ex.message)
            throw serverError
        }
        // Wie der gewöhnliche Einzelkurs: ohne gültigen Kurs kein Erfolg
        if (!ticker.last.isFinite() || ticker.last <= 0.0) throw serverError
    }

    private val SERVER_ERRORS = 500..599
}
