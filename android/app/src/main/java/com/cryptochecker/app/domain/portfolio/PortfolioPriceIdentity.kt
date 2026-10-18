package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.TokenIdentity

/**
 * Gleicher Token auch beim Binance-Kurs des Portfolios (K-8, wie Merkliste und Kerzen-Kette): Der Sammel-Ticker
 * `SYMBOLUSDT` nennt nur das Kürzel. Heisst der Coin laut den Paaren der Merkliste klar anders als bei Binance (LUNA
 * Terra Classic gegen Terra 2.0, TON Tokamak gegen Toncoin, GMT GoMining gegen STEPN, HOT Hydro gegen Holo), gilt
 * Binances Kurs nicht — dann Merklisten-Paar bzw. Kerzen-Kette (die prüft den Namen ebenso). Unbekannte Namen:
 * Binance wie bisher. Reine Logik — wie `PortfolioPriceIdentity.swift`.
 */
object PortfolioPriceIdentity {

    /**
     * Name des Coins je Kürzel laut den aktiven Paaren der Merkliste ([watches]: Kürzel, Name aus der Paarliste der
     * Börse, abgeschaltet); widersprechen sich die Paare, null ([TokenIdentity.consensus]).
     */
    fun watchNames(coins: Collection<String>, watches: List<WatchName>): Map<String, String?> =
        coins.associateWith { coin ->
            val key = TokenIdentity.key(coin)
            TokenIdentity.consensus(key, watches.filter { !it.disabled && TokenIdentity.key(it.base) == key }.map { it.name })
        }

    /** Ein Paar der Merkliste für [watchNames]. */
    data class WatchName(val base: String, val name: String?, val disabled: Boolean = false)

    /** Darf Binances Ticker für [coin] gelten? Nein nur bei klar anderem Namen ([TokenIdentity.compatible]). */
    fun binanceTickerAllowed(coin: String, watchName: String?, binanceName: String?): Boolean =
        watchName == null || TokenIdentity.compatible(coin, watchName, binanceName)

    /** Die Coins aus [coins], deren Binance-Ticker gelten darf ([binanceTickerAllowed]). */
    fun binanceTickerCoins(
        coins: List<String>,
        watchNames: Map<String, String?>,
        binanceName: (String) -> String?,
    ): List<String> = coins.filter { binanceTickerAllowed(it, watchNames[it], binanceName(it)) }
}
