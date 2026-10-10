package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion

/**
 * Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
 * Widget, Portfolio-Alarme) — reines Kotlin, gespiegelt in `Shared/Portfolio/PortfolioStables.swift`:
 *  - Liste: [CurrencyConversion.USD_STABLES] (dieselbe wie die «≈ Umrechnung» der Merkliste).
 *  - USDT ist die Bewertungswährung und gilt immer genau 1.
 *  - Jeder andere Stablecoin gilt seinen Marktkurs in USDT (z. B. USDCUSDT 0.9993), wenn einer
 *    da ist — sonst 1. Im Wertverlauf und im Stichtag-Export gilt das je Tag (Tagesschluss, sonst 1).
 *  - Alle anderen Coins brauchen einen Kurs; ohne Kurs bleibt der Wert offen (null).
 */
object PortfolioStables {

    /** Bewertungswährung des Portfolios. */
    const val VALUATION = "USDT"

    /** Die eine Stablecoin-Liste. */
    val COINS: Set<String> get() = CurrencyConversion.USD_STABLES

    /** Stablecoin (Gross-/Kleinschreibung und Leerzeichen egal)? */
    fun isStable(coin: String): Boolean = PortfolioCalculator.normalizeCoin(coin) in COINS

    /** Die Bewertungswährung selbst (immer 1, keine Abfrage). */
    fun isValuation(coin: String): Boolean = PortfolioCalculator.normalizeCoin(coin) == VALUATION

    /** Braucht der Coin einen Kurs von einer Quelle? Alle ausser USDT — auch die übrigen Stablecoins. */
    fun needsQuote(coin: String): Boolean = !isValuation(coin)

    /**
     * Wert einer Einheit [coin] in USDT nach der Regel oben.
     * @param market Marktkurs in USDT (aktuell oder Tagesschluss); ungültig = keiner
     * @return null nur für Nicht-Stablecoins ohne gültigen Kurs
     */
    fun price(coin: String, market: Double?): Double? {
        if (isValuation(coin)) return 1.0
        val valid = market?.takeIf { it > 0.0 && it.isFinite() }
        if (valid != null) return valid
        return if (isStable(coin)) 1.0 else null
    }
}
