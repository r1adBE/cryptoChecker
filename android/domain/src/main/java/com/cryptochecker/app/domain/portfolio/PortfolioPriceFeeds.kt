package com.cryptochecker.app.domain.portfolio

import java.time.LocalDate

/** Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag). */
data class HistoricFx(val rate: Double, val date: String?)

/** Ergebnis von [HistoricPrices.usdToSeriesChecked]: [rates] wie `usdToSeries`; [failed] = keine gültige Antwort. */
class FxSeriesLoad(val rates: Map<Long, Double>?, val failed: Boolean = false)

/** Ergebnis von [PortfolioPriceFeed.exportReference]: Bezugskurse je Coin (USDT) und Coins ohne Bezug wegen eines Abruffehlers. */
data class ExportReference(val prices: Map<String, Double>, val unavailable: Set<String>)

/** Aktuelle Kurse der Coins im Portfolio (Umsetzung in :data, `PortfolioPriceSource`). */
interface PortfolioPriceFeed {
    /** Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt. */
    suspend fun price(coin: String): Double?

    suspend fun prices(coins: Collection<String>, force: Boolean = false): PortfolioPrices

    /** Gemerkte Kurse (ohne Netz). */
    suspend fun stored(coins: Collection<String>): PortfolioPrices

    /** Coins für die Auswahl im Erfassen-Blatt. */
    suspend fun coins(): List<String>

    /** «Portfolio leeren»: gemerkte Kurse und Zwischenspeicher weg. */
    suspend fun clearStored()

    /** Spot-Börsen der App für das Feld «Börse». */
    suspend fun spotExchangeNames(): List<String>

    /** Bezugskurse für den Export (frisch, sonst gemerkt). */
    suspend fun exportReference(coins: Collection<String>): ExportReference
}

/** Devisenkurse USD → Zielwährung (Umsetzung: `FxRateSource`). */
interface FxRates {
    /** Wie viele Einheiten [currency] ein USD ist; null, wenn unbekannt. */
    suspend fun usdTo(currency: String): Double?

    /** Zuletzt bekannter Kurs ohne Netz. */
    fun cached(currency: String): Double?
}

/** Kurse vergangener Tage: Tagesschlüsse der Coins und EZB-Devisenkurse (Umsetzung: `HistoricPriceSource`). */
interface HistoricPrices {
    /**
     * Ergebnis je Coin: bestätigter Kurs, sonst [unchecked] = Kurs nicht als derselbe Token bestätigt. [source] =
     * Anbieter des Kurses (z. B. «Binance», «Kraken»; null ohne Kurs bzw. USDT = 1). [failed] = ohne Kurs, weil eine
     * Quelle nicht abrufbar war ([HistoricCloseChain.Resolved.failed]): «nicht abrufbar» statt «ohne Kurs».
     */
    data class CloseResult(val price: Double?, val unchecked: Boolean = false, val source: String? = null, val failed: Boolean = false)

    /** Schlusskurse der Coins am Tag [date]; [current] = Bezugskurse in USDT für die Identitätsprüfung. */
    suspend fun dailyClosesUsdt(coins: Collection<String>, date: LocalDate, current: Map<String, Double>): CutoffCloses

    /** Schlusskurs eines Coins am Tag [date] (UTC-Tageskerze); [current] = aktueller Kurs als Bezug. */
    suspend fun dailyCloseUsdt(coin: String, date: LocalDate, current: Double?): CloseResult

    /** Schlusskurse je Coin an mehreren Tagen. */
    suspend fun dayClosesUsdt(
        wanted: Map<String, Collection<LocalDate>>,
        current: Map<String, Double>,
    ): Map<String, Map<LocalDate, CloseResult>>

    /** Devisenkurs USD → [currency] am Tag [date]; null, wenn kein Kurs zu haben ist. */
    suspend fun usdTo(currency: String, date: LocalDate): HistoricFx?

    /** Tageskurse USD → [currency] von [from] bis [to]; null, wenn kein Kurs zu haben ist. */
    suspend fun usdToSeries(currency: String, from: LocalDate, to: LocalDate): Map<Long, Double>?

    /** Wie [usdToSeries], sagt aber, warum nichts kam ([FxSeriesLoad.failed]). */
    suspend fun usdToSeriesChecked(currency: String, from: LocalDate, to: LocalDate): FxSeriesLoad
}

/** Verlauf des Gesamtwerts: Tageskerzen der Coins und USDT/USD (Umsetzung: `PortfolioHistorySource`). */
interface PortfolioHistoryFeed {
    suspend fun dailyPrices(coins: Collection<String>, days: Int = PortfolioHistory.MAX_DAYS): PortfolioDailyPrices

    /** Tagesschlüsse USDT/USD (UTC-Tag → Schluss) für mindestens die letzten [days] Tage. */
    suspend fun usdtUsdDaily(days: Int = PortfolioHistory.MAX_DAYS): Map<Long, Double>
}
