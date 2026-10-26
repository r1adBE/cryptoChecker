package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
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
            // Bremst die Börse den Ausweg (429/418), gilt die Bremse: sonst fragt die App ohne Pause weiter an. Ein
            // fremder Anbieter (DexScreener → GeckoTerminal) pausiert die Börse nicht: dann bleibt der Serverfehler
            val braked = ex is HttpMarketError && ExchangeBackoff.isRateLimitCode(ex.httpCode)
            if (braked && sameHost(market, call, info, address)) throw ex
            Timber.w("Ausweg des Einzelkurses ohne Kurs (%s): %s", market.key, ex.message)
            throw serverError
        }
        // Wie der gewöhnliche Einzelkurs: ohne gültigen Kurs kein Erfolg
        if (!ticker.last.isFinite() || ticker.last <= 0.0) throw serverError
    }

    /**
     * Ausweg nach einem Lesefehler des Einzelkurses, den die Börse dafür freigibt
     * ([Market.tickerFallbackOnParseError], DexScreener «Pool nicht gefunden» → GeckoTerminal, Build 133). Ohne
     * Ausweg oder ohne gültigen Kurs wird [missing]
     * geworfen — auch bei einer Bremse (429/418) der Ausweichadresse: sie gehört einem anderen Anbieter und darf die
     * Börse nicht pausieren (ExchangeBackoff). Ein eigener Abbruch geht durch.
     */
    suspend fun loadAfterMissing(
        market: Market,
        call: Int,
        info: PairQuery,
        ticker: Ticker,
        missing: Exception,
        load: suspend (String) -> String,
    ) {
        val address = market.tickerFallbackAddress(call, info)
        if (!market.tickerFallbackOnParseError(call, missing) || address.isNullOrEmpty()) throw missing
        try {
            market.readTickerFallback(call, load(address), ticker, info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            Timber.w("Ausweg nach Lesefehler ohne Kurs (%s): %s", market.key, ex.message)
            throw missing
        }
        if (!ticker.last.isFinite() || ticker.last <= 0.0) throw missing
    }

    /** Liegt die Ausweichadresse beim selben Anbieter wie die Einzelabfrage (One Trading ja, GeckoTerminal nein)? */
    internal fun sameHost(market: Market, call: Int, info: PairQuery, fallback: String): Boolean = try {
        java.net.URI(market.tickerAddress(call, info)).host.equals(java.net.URI(fallback).host, ignoreCase = true)
    } catch (_: Exception) {
        true
    }

    private val SERVER_ERRORS = 500..599
}
