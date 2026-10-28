package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.Ticker
import timber.log.Timber

/**
 * Ausweg eines Einzelkurses, dessen Anfrage mit einem Serverfehler (HTTP 5xx) scheitert: Nennt die Börse eine
 * Ausweichadresse ([Exchange.tickerFallbackAddress], One Trading: die Sammelliste), kommt der Kurs von dort. Bringt
 * auch das keinen gültigen Kurs (Paar fehlt, Netz, unlesbar), bleibt der ursprüngliche Serverfehler — er bestätigt
 * nie «nicht mehr gehandelt» (NotTraded). 4xx (sagt etwas über das Paar) und Bremsen (429/418) nie.
 * iOS: `SingleTickerFallback.swift`.
 */
object SingleTickerFallback {

    /** Nur Serverfehler lösen den Ausweg aus. */
    fun applies(httpCode: Int): Boolean = httpCode in SERVER_ERRORS

    /**
     * Liest den Kurs über die Ausweichadresse in [ticker]; [load] holt die Adresse (GET). Ohne Ausweg oder ohne
     * gültigen Kurs wird [serverError] geworfen; ein eigener Abbruch geht durch.
     */
    suspend fun load(
        exchange: Exchange,
        call: Int,
        info: PairQuery,
        ticker: Ticker,
        serverError: HttpExchangeError,
        load: suspend (String) -> String,
    ) {
        val address = exchange.tickerFallbackAddress(call, info)
        if (!applies(serverError.httpCode) || address.isNullOrEmpty()) throw serverError
        try {
            exchange.readTickerFallback(call, load(address), ticker, info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            // Bremst die Börse den Ausweg (429/418), gilt die Bremse: sonst fragt die App ohne Pause weiter an. Ein
            // fremder Anbieter (DexScreener → GeckoTerminal) pausiert die Börse nicht: dann bleibt der Serverfehler
            val braked = ex is HttpExchangeError && ExchangeBackoff.isRateLimitCode(ex.httpCode)
            if (braked && sameHost(exchange, call, info, address)) throw ex
            Timber.w("Ausweg des Einzelkurses ohne Kurs (%s): %s", exchange.key, ex.message)
            throw serverError
        }
        // Wie der gewöhnliche Einzelkurs: ohne gültigen Kurs kein Erfolg
        if (!ticker.last.isFinite() || ticker.last <= 0.0) throw serverError
    }

    /**
     * Ausweg nach einem Lesefehler des Einzelkurses, den die Börse dafür freigibt
     * ([Exchange.tickerFallbackOnParseError], DexScreener «Pool nicht gefunden» → GeckoTerminal). Ohne
     * Ausweg oder ohne gültigen Kurs wird [missing]
     * geworfen — auch bei einer Bremse (429/418) der Ausweichadresse: sie gehört einem anderen Anbieter und darf die
     * Börse nicht pausieren (ExchangeBackoff). Ein eigener Abbruch geht durch.
     */
    suspend fun loadAfterMissing(
        exchange: Exchange,
        call: Int,
        info: PairQuery,
        ticker: Ticker,
        missing: Exception,
        load: suspend (String) -> String,
    ) {
        val address = exchange.tickerFallbackAddress(call, info)
        if (!exchange.tickerFallbackOnParseError(call, missing) || address.isNullOrEmpty()) throw missing
        try {
            exchange.readTickerFallback(call, load(address), ticker, info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            Timber.w("Ausweg nach Lesefehler ohne Kurs (%s): %s", exchange.key, ex.message)
            throw missing
        }
        if (!ticker.last.isFinite() || ticker.last <= 0.0) throw missing
    }

    /** Liegt die Ausweichadresse beim selben Anbieter wie die Einzelabfrage (One Trading ja, GeckoTerminal nein)? */
    fun sameHost(exchange: Exchange, call: Int, info: PairQuery, fallback: String): Boolean = try {
        java.net.URI(exchange.tickerAddress(call, info)).host.equals(java.net.URI(fallback).host, ignoreCase = true)
    } catch (_: Exception) {
        true
    }

    private val SERVER_ERRORS = 500..599
}
