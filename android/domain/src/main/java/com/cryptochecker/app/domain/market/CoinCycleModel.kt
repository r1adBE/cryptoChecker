package com.cryptochecker.app.domain.market

import java.time.LocalDate

/** Kursdaten eines beliebigen Coins (in USDT), aufbereitet für die Kennzahlen. */
data class CoinInputs(
    val price: Double,
    val sma50d: Double?,
    val sma200d: Double?,
    val sma111d: Double?,
    val sma350d: Double?,
    val price30dAgo: Double?,
    val sma200w: Double?,
    val ath: Double?,
    val athDate: LocalDate?,
    val rsiDaily: Double?,
    val rsiWeekly: Double?,
    /** Veränderung des Kurses gegenüber Bitcoin in 90 Tagen, in Prozent. */
    val vsBtc90d: Double?,
    /** Wie viele Tage Kursverlauf vorliegen. */
    val historyDays: Int,
)

/** Kennzahlen eines Coins in fester Reihenfolge (Werte null = zu wenig Kursverlauf). */
data class CoinReport(
    val symbol: String,
    val price: Double,
    val indicators: List<Indicator>,
    val historyDays: Int,
)

/**
 * Kennzahlen für beliebige Coins — nur aus dem Kursverlauf, weil On-Chain-Daten frei nur
 * für Bitcoin verfügbar sind. Reine Messwerte ohne Zonen, Scores oder Deutung, wie
 * [BitcoinIndicatorModel]. Wie `CoinIndicatorModel` (iOS).
 */
object CoinIndicatorModel {

    fun evaluate(symbol: String, input: CoinInputs): CoinReport {
        val price = input.price
        val indicators = listOf(
            Indicator(IndicatorId.MAYER, IndicatorMath.ratio(price, input.sma200d)),
            Indicator(IndicatorId.MA200W, input.sma200w?.takeIf { it > 0 }),
            Indicator(IndicatorId.ATH_DISTANCE, IndicatorMath.athDistance(price, input.ath)),
            Indicator(IndicatorId.CHANGE_30D, IndicatorMath.changePercent(price, input.price30dAgo)),
            Indicator(IndicatorId.SMA50_VS_200, IndicatorMath.changePercent(input.sma50d, input.sma200d)),
            Indicator(IndicatorId.RSI_WEEKLY, input.rsiWeekly?.takeIf { it.isFinite() }),
            Indicator(IndicatorId.RSI_DAILY, input.rsiDaily?.takeIf { it.isFinite() }),
            Indicator(IndicatorId.VS_BTC, input.vsBtc90d?.takeIf { it.isFinite() }),
        )
        return CoinReport(symbol, price, indicators, input.historyDays)
    }
}
