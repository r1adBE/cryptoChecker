package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.TokenIdentity

/**
 * Kurs aus einem Merklisten-Paar für einen Coin, den Binance-Spot nicht führt (reines Kotlin, testbar;
 * Spiegel: `Shared/Portfolio/PortfolioWatchPair.swift`).
 *
 * Folgt der Nutzer dem Coin in der Merkliste an einer anderen Spot-Börse (z. B. Kraken XYZ/EUR), gilt
 * der Kurs dieses Paars — es ist der Token, den der Nutzer selbst gewählt hat — statt der ungeprüften
 * letzten Stundenkerze der Ausweich-Kette (gleiches Kürzel kann dort ein anderer Token sein). Gegenwährung
 * wie beim Weg über die eingetragene Börse ([PortfolioExchangeRoute.QUOTES]: USDT, USD, Fiat).
 */
object PortfolioWatchPair {

    /** Ein Merklisten-Paar (nur die Felder, die die Wahl braucht). */
    data class Candidate(
        val exchangeKey: String,
        val base: String,
        val quote: String,
        val pairId: String?,
        /** Spot (kein Futures-Kontrakt). */
        val spot: Boolean,
        val disabled: Boolean,
        /** Letzte Aktualisierung (Epoch-ms) — bei gleicher Gegenwährung gewinnt das frischere Paar. */
        val lastUpdate: Long,
        /** Voller Name des Coins laut Paarliste der Börse (TokenIdentity); null = unbekannt. */
        val name: String? = null,
    )

    /**
     * Bestes Paar für [coin]: aktiv, Spot, Spot-Börse der App ausser Binance ([PortfolioExchangeRoute.isSpot],
     * Binance führt den Coin ja nicht), Gegenwährung aus [PortfolioExchangeRoute.QUOTES] (in dieser Reihenfolge);
     * bei Gleichstand das zuletzt aktualisierte, dann nach Börse. null = keines.
     * Gleicher Token: Nennen die Börsen der passenden Paare den Coin klar verschieden
     * ([TokenIdentity.conflicting], z. B. «Simon's Cat» und «Catcoin»), ist unklar, welcher Token im
     * Portfolio steht — dann keines (kein geratener Kurs).
     */
    fun pick(coin: String, watches: List<Candidate>): Candidate? {
        val base = PortfolioCalculator.normalizeCoin(coin)
        val usable = watches.filter { w ->
            !w.disabled && w.spot &&
                PortfolioExchangeRoute.isSpot(w.exchangeKey) &&
                w.exchangeKey != PortfolioExchangeRoute.DEFAULT_EXCHANGE &&
                PortfolioCalculator.normalizeCoin(w.base) == base &&
                w.quote.uppercase() in PortfolioExchangeRoute.QUOTES
        }
        if (usable.isEmpty()) return null
        if (TokenIdentity.conflicting(base, usable.map { it.name })) return null
        return usable.minWithOrNull(
            compareBy<Candidate> { PortfolioExchangeRoute.QUOTES.indexOf(it.quote.uppercase()) }
                .thenByDescending { it.lastUpdate }
                .thenBy { it.exchangeKey }
        )
    }
}
