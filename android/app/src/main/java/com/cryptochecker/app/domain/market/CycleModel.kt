package com.cryptochecker.app.domain.market

import com.cryptochecker.app.util.LocaleNumbers
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Rohdaten für das Zyklus-Modell. Fehlende Werte (null) zählen einfach nicht —
 * das Modell rechnet mit dem, was verfügbar ist.
 */
data class CycleInputs(
    /** Aktueller BTC-Kurs in USD. */
    val price: Double,
    /** Durchschnitt der letzten 200 Tagesschlusskurse. */
    val sma200d: Double?,
    /** Durchschnitt der letzten 111 bzw. 350 Tagesschlusskurse (Pi-Cycle). */
    val sma111d: Double?,
    val sma350d: Double?,
    /** Schlusskurs vor 30 Tagen. */
    val price30dAgo: Double?,
    /** Durchschnitt der letzten 200 Wochenschlusskurse. */
    val sma200w: Double?,
    /** Allzeithoch und wann es war. */
    val ath: Double?,
    val athDate: LocalDate?,
    /** On-Chain (Coin Metrics): Marktwert / realisierter Wert. */
    val mvrv: Double?,
    /** Mining-Einnahmen heute geteilt durch ihren 365-Tage-Schnitt. */
    val puell: Double?,
    /** Hashrate: 30- und 60-Tage-Schnitt (Hash Ribbons). */
    val hash30d: Double?,
    val hash60d: Double?,
    val today: LocalDate = LocalDate.now(),
)

enum class MarketZone { EXTREME_BEAR, BEAR, NEUTRAL, BULL, EXTREME_BULL }

/** Ein einzelnes Signal mit seinem Beitrag zum Score. */
data class CycleSignal(
    val id: SignalId,
    /** Anzeige des Messwerts, z. B. "1.58". */
    val value: String?,
    val topPoints: Int,
    val bottomPoints: Int,
)

enum class SignalId { MVRV_NUPL, PUELL, MAYER, MA200W, DRAWDOWN, HASH_RIBBON, PARABOLIC, HALVING_TIME, ATH_TIME }

data class CycleReport(
    val zone: MarketZone,
    /** 0 = extrem bärisch, 100 = extrem bullisch (Top-Zone). */
    val index: Int,
    val topScore: Int,
    val bottomScore: Int,
    val signals: List<CycleSignal>,
    val cycle: CycleInfo,
    val monthsSinceAth: Long?,
    val drawdownPercent: Double?,
    val onChainAvailable: Boolean,
)

/**
 * Zonenmodell nach dem Prinzip „mehrere unabhängige Signale müssen
 * gleichzeitig in dieselbe Richtung zeigen“. Kein Kurs-Tipp: Es sagt nur,
 * wie viele historische Top- bzw. Bottom-Merkmale gerade zutreffen.
 *
 * Top-Score (max. 10)                      Bottom-Score (max. 10)
 *  MVRV/NUPL  ≥2,4 +1 · ≥2,8 +2 · ≥3,5 +3   MVRV ≤1,5 +1 · ≤1,2 +2 · ≤1,0 (NUPL ≤0) +3
 *  Puell      ≥2,5 +1 · ≥3,5 +2             Puell ≤0,7 +1 · ≤0,5 +2
 *  Mayer      ≥2,0 +1 · ≥2,4 +2             Rückgang vom ATH ≥50 % +1 · ≥70 % +2
 *  200W-Mult. ≥3,5 +1                       Kurs ≤ 200-Wochen-Schnitt +1
 *  Parabolisch (Pi-Cycle o. +40 % in 30 T.) +1   Hash-Ribbon-Kapitulation +1
 *  12–18 Monate nach Halving +1             ≥12 Monate nach ATH +1
 *
 * MVRV und NUPL hängen mathematisch zusammen (NUPL = 1 − 1/MVRV) und zählen
 * deshalb gemeinsam, nicht doppelt.
 */
object CycleModel {

    fun evaluate(input: CycleInputs): CycleReport {
        val cycle = BitcoinCycle.info(input.today)
        val signals = mutableListOf<CycleSignal>()

        // MVRV / NUPL
        input.mvrv?.let { mvrv ->
            val nupl = 1.0 - 1.0 / mvrv
            val top = when { mvrv >= 3.5 -> 3; mvrv >= 2.8 -> 2; mvrv >= 2.4 -> 1; else -> 0 }
            val bottom = when { mvrv <= 1.0 -> 3; mvrv <= 1.2 -> 2; mvrv <= 1.5 -> 1; else -> 0 }
            signals += CycleSignal(SignalId.MVRV_NUPL, "%.2f · NUPL %.2f".format(mvrv, nupl), top, bottom)
        }

        // Puell Multiple
        input.puell?.let { p ->
            val top = when { p >= 3.5 -> 2; p >= 2.5 -> 1; else -> 0 }
            val bottom = when { p <= 0.5 -> 2; p <= 0.7 -> 1; else -> 0 }
            signals += CycleSignal(SignalId.PUELL, "%.2f".format(p), top, bottom)
        }

        // Mayer Multiple (Kurs / 200-Tage-Schnitt)
        val mayer = input.sma200d?.takeIf { it > 0 }?.let { input.price / it }
        mayer?.let { m ->
            val top = when { m >= 2.4 -> 2; m >= 2.0 -> 1; else -> 0 }
            signals += CycleSignal(SignalId.MAYER, "%.2f".format(m), top, 0)
        }

        // 200-Wochen-Schnitt
        input.sma200w?.takeIf { it > 0 }?.let { ma ->
            val mult = input.price / ma
            signals += CycleSignal(
                SignalId.MA200W, "%.2f×".format(mult),
                topPoints = if (mult >= 3.5) 1 else 0,
                bottomPoints = if (mult <= 1.0) 1 else 0
            )
        }

        // Rückgang vom Allzeithoch
        val drawdown = input.ath?.takeIf { it > 0 }?.let { max(0.0, (1.0 - input.price / it) * 100.0) }
        drawdown?.let { dd ->
            val bottom = when { dd >= 70 -> 2; dd >= 50 -> 1; else -> 0 }
            signals += CycleSignal(SignalId.DRAWDOWN, "−%.0f %%".format(dd), 0, bottom)
        }

        // Hash Ribbons: 30-Tage-Schnitt unter 60-Tage-Schnitt = Miner geben auf
        if (input.hash30d != null && input.hash60d != null && input.hash60d > 0) {
            val capitulation = input.hash30d < input.hash60d
            signals += CycleSignal(SignalId.HASH_RIBBON, if (capitulation) "↓" else "↑", 0, if (capitulation) 1 else 0)
        }

        // Parabolischer Anstieg: Pi-Cycle-Top oder +40 % in 30 Tagen
        val piCycle = input.sma111d != null && input.sma350d != null && input.sma111d >= 2 * input.sma350d
        val gain30 = input.price30dAgo?.takeIf { it > 0 }?.let { (input.price / it - 1.0) * 100.0 }
        if (gain30 != null || input.sma350d != null) {
            val parabolic = piCycle || (gain30 ?: 0.0) >= 40.0
            signals += CycleSignal(SignalId.PARABOLIC, gain30?.let { "%+.0f %%".format(it) }, if (parabolic) 1 else 0, 0)
        }

        // Zeit: Halving-Fenster und Abstand zum Hoch
        val inTopWindow = cycle.monthsSinceHalving in 12..18
        signals += CycleSignal(SignalId.HALVING_TIME, LocaleNumbers.integer(cycle.monthsSinceHalving), if (inTopWindow) 1 else 0, 0)

        val monthsSinceAth = input.athDate?.let { ChronoUnit.MONTHS.between(it, input.today) }
        monthsSinceAth?.let { m ->
            // Nur zählen, wenn das Hoch auch deutlich zurückliegt (sonst sind wir am Hoch).
            val bottom = if (m >= 12 && (drawdown ?: 0.0) >= 20) 1 else 0
            signals += CycleSignal(SignalId.ATH_TIME, LocaleNumbers.integer(m), 0, bottom)
        }

        val top = signals.sumOf { it.topPoints }.coerceAtMost(10)
        val bottom = signals.sumOf { it.bottomPoints }.coerceAtMost(10)
        val uptrend = mayer?.let { it >= 1.0 }

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

        return CycleReport(
            zone = zone,
            index = index,
            topScore = top,
            bottomScore = bottom,
            signals = signals,
            cycle = cycle,
            monthsSinceAth = monthsSinceAth,
            drawdownPercent = drawdown,
            onChainAvailable = input.mvrv != null || input.puell != null,
        )
    }
}
