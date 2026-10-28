package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.TokenIdentity

/**
 * Gleicher Token auch beim Binance-Kurs des Portfolios (wie Merkliste und Kerzen-Kette): Der Sammel-Ticker
 * `SYMBOLUSDT` nennt nur das Kürzel. Heisst der Coin laut den Paaren der Merkliste klar anders als bei Binance (LUNA
 * Terra Classic gegen Terra 2.0, TON Tokamak gegen Toncoin, GMT GoMining gegen STEPN, HOT Hydro gegen Holo), gilt
 * Binances Kurs nicht — dann Merklisten-Paar bzw. Kerzen-Kette (die prüft den Namen ebenso). Ist kein Name bekannt,
 * gilt Binance nur, wenn Binance dem Kürzel einen Namen gibt und es kein bekanntes Doppel-Kürzel ist ([binanceCheck]);
 * sonst bleibt der Kurs «ungeprüft». Führen die Paarlisten anderer Börsen unter dem Kürzel klar verschiedene Token
 * zählt Binances Kurs weiter, aber als «Kurs ungeprüft» ([BinanceCheck.UNVERIFIED]).
 * Reine Logik — wie `PortfolioPriceIdentity.swift`.
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

    /** Wie Binances Ticker für einen Coin gilt ([binanceCheck]). */
    enum class BinanceCheck {
        /** Gleicher Token oder (ohne bekannten Namen) eindeutiges Kürzel: Binances Kurs gilt. */
        USE,

        /** Klar anderer Name: Binance nicht, sondern Merklisten-Paar bzw. Kerzen-Kette. */
        SKIP,

        /** Kein Name bekannt und Kürzel bei Binance ohne Namen oder mehrdeutig: nicht Binance, sonst «ungeprüft». */
        UNCHECKED,

        /**
         * Kein Name bekannt, Binance nennt das Kürzel, aber andere Listen führen darunter klar verschiedene Token
         * ([ambiguous]): Binances Kurs zählt (lieber ein markierter Wert als 0), ist aber nicht bestätigt — die Zeile
         * zeigt «Kurs ungeprüft», Portfolio-Alarme behandeln ihn wie einen alten Kurs (wesentlich → Pause).
         */
        UNVERIFIED,
    }

    /**
     * Kürzel, unter dem mehrere Token bekannt sind ([TokenIdentity.KNOWN] mit mehr als einer Gruppe: LUNA, LUNC, TON,
     * GMT, HOT, CAT, NVDA) — ohne Namen ist dort nicht zu sagen, welchen Token Binance meint.
     */
    fun knownCollision(coin: String): Boolean = (TokenIdentity.KNOWN[TokenIdentity.key(coin)]?.size ?: 0) > 1

    /**
     * Mehrere Token unter dem Kürzel? Ja, wenn sich Binances Name [binanceName] und die Namen anderer Paarlisten
     * [otherNames] (je Börse einer) klar widersprechen ([TokenIdentity.conflicting]) — etwa ein Kürzel, das an einer
     * anderen Börse für einen ganz anderen Token steht. Ein Bestand ohne Namen kann dann jeder davon sein.
     */
    fun ambiguous(coin: String, binanceName: String?, otherNames: List<String?>): Boolean =
        TokenIdentity.conflicting(TokenIdentity.key(coin), listOf(binanceName) + otherNames)

    /**
     * Prüfung für Binances Ticker: Mit bekanntem Namen ([knownName], aus der Merkliste) zählt nur, ob er zu Binances
     * Namen passt ([binanceTickerAllowed]). Ohne bekannten Namen gilt Binance nur, wenn Binances Paarliste dem Kürzel
     * einen Namen gibt ([binanceName]) und es keine bekannte Kollision ist ([knownCollision]); sonst [BinanceCheck.UNCHECKED].
     * Kennen andere Listen ([otherNames]) unter dem Kürzel einen klar anderen Token ([ambiguous]): [BinanceCheck.UNVERIFIED]
     * — eindeutiges Kürzel = bestätigt, mehrdeutiges = ungeprüft.
     */
    fun binanceCheck(
        coin: String,
        knownName: String?,
        binanceName: String?,
        otherNames: List<String?> = emptyList(),
    ): BinanceCheck = when {
        knownName != null -> if (binanceTickerAllowed(coin, knownName, binanceName)) BinanceCheck.USE else BinanceCheck.SKIP
        binanceName.isNullOrBlank() || knownCollision(coin) -> BinanceCheck.UNCHECKED
        ambiguous(coin, binanceName, otherNames) -> BinanceCheck.UNVERIFIED
        else -> BinanceCheck.USE
    }

    /**
     * Aufteilung von [binanceSplit]: [use] für die Binance-Abfrage (samt [unverified]), [unchecked] ohne anderen Kurs
     * «ungeprüft», [unverified] mit Binance-Kurs, aber «Kurs ungeprüft» ([BinanceCheck.UNVERIFIED]).
     */
    data class BinanceSplit(val use: List<String>, val unchecked: Set<String>, val unverified: Set<String> = emptySet())

    /** [coins] nach [binanceCheck] aufgeteilt; [knownNames] wie [watchNames], [otherNames] je Coin wie bei [ambiguous]. */
    fun binanceSplit(
        coins: List<String>,
        knownNames: Map<String, String?>,
        binanceName: (String) -> String?,
        otherNames: (String) -> List<String?> = { emptyList() },
    ): BinanceSplit {
        val checks = coins.associateWith { binanceCheck(it, knownNames[it], binanceName(it), otherNames(it)) }
        return BinanceSplit(
            use = coins.filter { checks[it] == BinanceCheck.USE || checks[it] == BinanceCheck.UNVERIFIED },
            unchecked = coins.filterTo(LinkedHashSet()) { checks[it] == BinanceCheck.UNCHECKED },
            unverified = coins.filterTo(LinkedHashSet()) { checks[it] == BinanceCheck.UNVERIFIED },
        )
    }

    /** Die Coins aus [coins], deren Binance-Ticker gelten darf ([binanceTickerAllowed]). */
    fun binanceTickerCoins(
        coins: List<String>,
        watchNames: Map<String, String?>,
        binanceName: (String) -> String?,
    ): List<String> = coins.filter { binanceTickerAllowed(it, watchNames[it], binanceName(it)) }
}
