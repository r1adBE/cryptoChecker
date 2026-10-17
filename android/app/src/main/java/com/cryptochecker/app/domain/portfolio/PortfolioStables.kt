package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion

/**
 * Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
 * Widget, Portfolio-Alarme) — reines Kotlin, gespiegelt in `Shared/Portfolio/PortfolioStables.swift`:
 *  - Liste: [CurrencyConversion.USD_STABLES] (dieselbe wie die «≈ Umrechnung» der Merkliste).
 *  - USDT ist die Bewertungswährung und gilt immer genau 1.
 *  - Jeder andere Stablecoin gilt seinen Marktkurs in USDT (z. B. USDCUSDT 0.9993). Ohne Kurs
 *    hat er keinen Wert (null, Position «—» und «ohne Kurs», zählt unter «ohne N Coins») — kein
 *    stilles 1: ein abgekoppelter Stablecoin soll nicht als 1 in Wert und Gewinn landen. Im
 *    Wertverlauf und im Stichtag-Export gilt das je Tag (Tagesschluss, sonst kein Wert).
 *  - Ausnahme «7 T» ([PortfolioPeriodChange]): fehlt einem Stablecoin (ausser USDT) der Schluss am
 *    Stichtag, gilt 1 nur, solange sein aktueller Kurs höchstens 0.1 % von 1 abweicht
 *    ([MAX_FALLBACK_DEVIATION]); sonst ist die Veränderung unbekannt («—») statt eine Zahl aus
 *    einem nie beobachteten Kurs 1 ([fallbackWithoutClose]).
 *    Diese Ausnahme setzt einen aktuellen Kurs voraus (ohne ihn fehlt der Coin ohnehin).
 *  - Alle anderen Coins brauchen ebenso einen Kurs; ohne Kurs bleibt der Wert offen (null).
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
     * @return null für jeden Coin ausser USDT ohne gültigen Kurs (auch für USDC, DAI …)
     */
    fun price(coin: String, market: Double?): Double? {
        if (isValuation(coin)) return 1.0
        return market?.takeIf { it > 0.0 && it.isFinite() }
    }

    /** Höchste Abweichung des aktuellen Kurses von 1, bei der ein fehlender Schluss als 1 gilt (0.1 %). */
    const val MAX_FALLBACK_DEVIATION = 0.001

    /**
     * Ersatzkurs für einen Stichtag ohne Tagesschluss («7 T»): USDT immer 1; andere Stablecoins 1 nur,
     * wenn [live] höchstens [MAX_FALLBACK_DEVIATION] von 1 abweicht; sonst und für Nicht-Stablecoins
     * null (= unbekannt).
     */
    fun fallbackWithoutClose(coin: String, live: Double): Double? {
        if (isValuation(coin)) return 1.0
        if (!isStable(coin)) return null
        return if (live.isFinite() && kotlin.math.abs(live - 1.0) <= MAX_FALLBACK_DEVIATION) 1.0 else null
    }
}
