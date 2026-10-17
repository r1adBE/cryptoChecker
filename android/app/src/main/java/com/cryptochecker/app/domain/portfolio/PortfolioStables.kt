package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion

/**
 * Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
 * Widget, Portfolio-Alarme) — reines Kotlin, gespiegelt in `Shared/Portfolio/PortfolioStables.swift`:
 *  - Liste: [CurrencyConversion.USD_STABLES] (dieselbe wie die «≈ Umrechnung» der Merkliste).
 *  - USDT ist die interne Rechenwährung (1 USDT = 1 USDT). In Fiat (auch USD) gilt USDT seinen Marktkurs USDT/USD
 *    ([usdtUsd], [UsdtUsdRates]; Owner-Entscheid Okt. 2026, früher fest 1): aktuell der Live-Kurs, für einen Tag der
 *    Tagesschluss USDT/USD. Ohne Kurs (offline, Tag ohne Schluss) gilt 1 als NÄHERUNG ([UsdtUsd.approximate]) —
 *    nie still: die Anzeige markiert sie («USDT = 1 angenommen», Steuer-CSV Vermerk je Zeile). So bleiben Werte
 *    USDT-notierter Portfolios auch offline sichtbar. USD (Fiat) selbst ist in USD immer 1.
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

    /** Die Rechenwährung selbst (in USDT immer 1, keine Abfrage; in Fiat über [usdtUsd]). */
    fun isValuation(coin: String): Boolean = PortfolioCalculator.normalizeCoin(coin) == VALUATION

    /** Braucht der Coin einen Kurs in USDT von einer Quelle? Alle ausser USDT — auch die übrigen Stablecoins. */
    fun needsQuote(coin: String): Boolean = !isValuation(coin)

    /**
     * Wert einer Einheit [coin] in USDT nach der Regel oben.
     * @param market Marktkurs in USDT (aktuell oder Tagesschluss); ungültig = keiner
     * @return null für jeden Coin ausser USDT ohne gültigen Kurs (auch für USDC, DAI …); USDT = 1 (Rechenwährung —
     *   sein Wert in Fiat folgt aus [usdtUsd])
     */
    fun price(coin: String, market: Double?): Double? {
        if (isValuation(coin)) return 1.0
        return market?.takeIf { it > 0.0 && it.isFinite() }
    }

    /** Höchste Abweichung des aktuellen Kurses von 1, bei der ein fehlender Schluss als 1 gilt (0.1 %). */
    const val MAX_FALLBACK_DEVIATION = 0.001

    /**
     * Ersatzkurs für einen Stichtag ohne Tagesschluss («7 T»): USDT (Rechenwährung) immer 1; andere Stablecoins 1 nur,
     * wenn [live] höchstens [MAX_FALLBACK_DEVIATION] von 1 abweicht; sonst und für Nicht-Stablecoins
     * null (= unbekannt).
     */
    fun fallbackWithoutClose(coin: String, live: Double): Double? {
        if (isValuation(coin)) return 1.0
        if (!isStable(coin)) return null
        return if (live.isFinite() && kotlin.math.abs(live - 1.0) <= MAX_FALLBACK_DEVIATION) 1.0 else null
    }

    /** Plausibler Marktkurs USDT/USD (wie [com.cryptochecker.app.domain.alarm.DepegAlarm]): sonst Börsenfehler. */
    const val USDT_USD_MIN = 0.5
    const val USDT_USD_MAX = 1.5

    /** Ersatz ohne Marktkurs USDT/USD: 1, als Näherung markiert. */
    val USDT_USD_FALLBACK = UsdtUsd(1.0, approximate = true)

    /**
     * USDT in USD aus einem Marktkurs [market] (live oder Tagesschluss): gültig und plausibel
     * ([USDT_USD_MIN]…[USDT_USD_MAX]) → dieser Kurs; sonst [USDT_USD_FALLBACK] (1, Näherung).
     */
    fun usdtUsd(market: Double?): UsdtUsd {
        val m = market?.takeIf { it.isFinite() && it >= USDT_USD_MIN && it <= USDT_USD_MAX } ?: return USDT_USD_FALLBACK
        return UsdtUsd(m, approximate = false)
    }

    /**
     * Faktor USDT → Zielwährung: [usdToTarget] (Einheiten Zielwährung je USD, USD = 1) × USDT/USD [usdt].
     * null ohne gültigen Devisenkurs.
     */
    fun usdtTo(usdToTarget: Double?, usdt: UsdtUsd): Double? {
        val fx = usdToTarget?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        return (fx * usdt.rate).takeIf { it > 0.0 && it.isFinite() }
    }
}

/** USDT in USD: [rate] = Marktkurs, oder 1 mit [approximate] (kein Kurs — Anzeige markiert die Näherung). */
data class UsdtUsd(val rate: Double, val approximate: Boolean)

/**
 * Marktkurse USDT/USD für Umrechnungen in Fiat: [live] = aktueller Kurs (Kraken USDT/USD bzw. Binance USDC/USDT wie der
 * Depeg-Alarm, [com.cryptochecker.app.domain.alarm.DepegAlarm.combine]); [daily] = Tagesschluss je Tag (Tage seit
 * 1970-01-01). Ab [todayEpochDay] gilt der Live-Kurs, davor der Schluss DES Tags — fehlt er, 1 als Näherung
 * ([PortfolioStables.usdtUsd]), kein Schluss eines anderen Tags.
 */
data class UsdtUsdRates(val live: Double?, val daily: Map<Long, Double> = emptyMap()) {

    /** Aktueller Kurs (oder Ersatz 1, Näherung). */
    fun current(): UsdtUsd = PortfolioStables.usdtUsd(live)

    /** Kurs für den Tag [day]: ab [todayEpochDay] [current], sonst Tagesschluss oder Ersatz 1 (Näherung). */
    fun on(day: Long, todayEpochDay: Long): UsdtUsd =
        if (day >= todayEpochDay) current() else PortfolioStables.usdtUsd(daily[day])
}
