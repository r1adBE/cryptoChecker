package com.cryptochecker.app.domain.market

import java.time.LocalDate
import kotlin.math.max

/** Kursdaten eines beliebigen Coins (in USDT), aufbereitet für das Modell. */
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

enum class CoinSignalId { MAYER, MA200W, DRAWDOWN, RSI_WEEKLY, RSI_DAILY, PI_CYCLE, PARABOLIC, CROSS, VS_BTC }

/** Ein Signal; [value] null heisst: zu wenig Daten für diesen Indikator. */
data class CoinSignal(
    val id: CoinSignalId,
    val value: String?,
    val topPoints: Int = 0,
    val bottomPoints: Int = 0,
)

data class CoinReport(
    val symbol: String,
    val price: Double,
    val zone: MarketZone,
    val index: Int,
    val topScore: Int,
    val bottomScore: Int,
    val signals: List<CoinSignal>,
    val historyDays: Int,
)

/**
 * Zonenmodell für beliebige Coins — nur aus dem Kursverlauf, weil On-Chain-
 * Daten frei nur für Bitcoin verfügbar sind. Gleiche Logik wie bei Bitcoin:
 * Mehrere unabhängige Signale müssen in dieselbe Richtung zeigen.
 *
 * Top-Score                                  Bottom-Score
 *  Mayer ≥2,0 +1 · ≥2,4 +2                    Mayer ≤0,8 +1 · ≤0,6 +2
 *  200W-Multiple ≥3,5 +1                      Kurs ≤ 200-Wochen-Schnitt +2
 *  Wochen-RSI ≥70 +1 · ≥80 +2                 Rückgang vom Hoch ≥50 % +1 · ≥70 % +2
 *  Tages-RSI ≥80 +1                           Wochen-RSI ≤40 +1 · ≤30 +2
 *  Pi-Cycle (111T ≥ 2×350T) +2                Tages-RSI ≤25 +1
 *  +60 % in 30 Tagen +1
 * Trend (50/200 Tage) und Stärke gegenüber BTC fliessen nur als Richtung ein.
 */
object CoinCycleModel {

    fun evaluate(symbol: String, input: CoinInputs): CoinReport {
        val signals = mutableListOf<CoinSignal>()

        val mayer = input.sma200d?.takeIf { it > 0 }?.let { input.price / it }
        signals += CoinSignal(
            CoinSignalId.MAYER, mayer?.let { "%.2f".format(it) },
            topPoints = when { mayer == null -> 0; mayer >= 2.4 -> 2; mayer >= 2.0 -> 1; else -> 0 },
            bottomPoints = when { mayer == null -> 0; mayer <= 0.6 -> 2; mayer <= 0.8 -> 1; else -> 0 },
        )

        val mult200w = input.sma200w?.takeIf { it > 0 }?.let { input.price / it }
        signals += CoinSignal(
            CoinSignalId.MA200W, mult200w?.let { "%.2f×".format(it) },
            topPoints = if ((mult200w ?: 0.0) >= 3.5) 1 else 0,
            bottomPoints = if (mult200w != null && mult200w <= 1.0) 2 else 0,
        )

        val drawdown = input.ath?.takeIf { it > 0 }?.let { max(0.0, (1.0 - input.price / it) * 100.0) }
        signals += CoinSignal(
            CoinSignalId.DRAWDOWN, drawdown?.let { "−%.0f %%".format(it) },
            bottomPoints = when { drawdown == null -> 0; drawdown >= 70 -> 2; drawdown >= 50 -> 1; else -> 0 },
        )

        val rw = input.rsiWeekly
        signals += CoinSignal(
            CoinSignalId.RSI_WEEKLY, rw?.let { "%.0f".format(it) },
            topPoints = when { rw == null -> 0; rw >= 80 -> 2; rw >= 70 -> 1; else -> 0 },
            bottomPoints = when { rw == null -> 0; rw <= 30 -> 2; rw <= 40 -> 1; else -> 0 },
        )

        val rd = input.rsiDaily
        signals += CoinSignal(
            CoinSignalId.RSI_DAILY, rd?.let { "%.0f".format(it) },
            topPoints = if ((rd ?: 0.0) >= 80) 1 else 0,
            bottomPoints = if (rd != null && rd <= 25) 1 else 0,
        )

        val pi = if (input.sma111d != null && input.sma350d != null && input.sma350d > 0)
            input.sma111d / (2 * input.sma350d) else null
        signals += CoinSignal(
            CoinSignalId.PI_CYCLE, pi?.let { "%.2f".format(it) },
            topPoints = if ((pi ?: 0.0) >= 1.0) 2 else 0,
        )

        val gain30 = input.price30dAgo?.takeIf { it > 0 }?.let { (input.price / it - 1.0) * 100.0 }
        signals += CoinSignal(
            CoinSignalId.PARABOLIC, gain30?.let { "%+.0f %%".format(it) },
            topPoints = if ((gain30 ?: 0.0) >= 60.0) 1 else 0,
        )

        val golden = if (input.sma50d != null && input.sma200d != null) input.sma50d >= input.sma200d else null
        signals += CoinSignal(
            CoinSignalId.CROSS, golden?.let { if (it) "Golden Cross" else "Death Cross" },
        )

        signals += CoinSignal(CoinSignalId.VS_BTC, input.vsBtc90d?.let { "%+.0f %%".format(it) })

        val top = signals.sumOf { it.topPoints }.coerceAtMost(10)
        val bottom = signals.sumOf { it.bottomPoints }.coerceAtMost(10)
        // Trend: über dem 200-Tage-Schnitt bzw. Golden Cross
        val uptrend = when {
            mayer != null && golden != null -> mayer >= 1.0 && golden
            mayer != null -> mayer >= 1.0
            else -> golden
        }

        val zone = when {
            top >= 7 -> MarketZone.EXTREME_BULL
            bottom >= 7 -> MarketZone.EXTREME_BEAR
            top >= 4 && top > bottom -> MarketZone.BULL
            bottom >= 4 && bottom > top -> MarketZone.BEAR
            uptrend == true && top >= bottom -> MarketZone.BULL
            uptrend == false && bottom >= top -> MarketZone.BEAR
            else -> MarketZone.NEUTRAL
        }
        val trendShift = when (uptrend) { true -> 8; false -> -8; null -> 0 }
        val index = (50 + (top - bottom) * 5 + trendShift).coerceIn(0, 100)

        return CoinReport(symbol, input.price, zone, index, top, bottom, signals, input.historyDays)
    }
}
