// Arten, Richtungen, Hinweise und Einträge der Gründe von «Was bewegt sich mit?» (WhyFactors).
package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/** Faktor im «Warum?»-Blatt; Reihenfolge = Rang bei gleicher Stärke. */
enum class WhyFactorKind {
    VOLUME,
    MARKET,
    VOLATILITY,
    OPEN_INTEREST,
    NEAR_HIGH,
    FUNDING,
    SENTIMENT,
    NEAR_LOW,
    POSITION_3D,
    PEG,
}

/** Pfeil vor dem Titel: ↗ / ↘ oder keiner. */
enum class WhyFactorDirection { UP, DOWN, NONE }

/** Kurze Nebenzeile eines Faktors («höher als üblich», «BTC zieht den Markt mit» …). */
enum class WhyFactorNote {
    VOLUME_HIGHER,
    VOLUME_LOWER,
    VOLUME_USUAL,
    MARKET_PULLS,
    MARKET_COIN_LAGS,
    MARKET_COIN_ALONE,
    MARKET_AGAINST,
    MARKET_CALM,
    MARKET_LEADER_MOVES,
    MARKET_LEADER_CALM,
    VOLATILITY_STRONGER,
    VOLATILITY_USUAL,
    OI_UP,
    OI_DOWN,
    OI_FLAT,
    HIGH_AT,
    HIGH_BELOW,
    FUNDING_LONGS,
    FUNDING_SHORTS,
    FUNDING_NEUTRAL,
    SENTIMENT,

    /** Markt (ETH oder breiter Markt) bewegt sich in dieselbe Richtung wie der Coin. */
    MARKET_MOVES,

    /** Open Interest gegenüber den älteren Messungen uneinheitlich. */
    OI_MIXED,
    LOW_AT,
    LOW_ABOVE,
    RANGE_NEAR_HIGH,
    RANGE_NEAR_LOW,
    RANGE_BELOW_HIGH,
    RANGE_ABOVE_LOW,
    PEG_NEAR,
    PEG_BELOW,
    PEG_ABOVE,

    /** Stunde ungewöhnlich für diesen Coin (|z| ≥ 2), aber absolut unter 0,5 % — kein Ausschlag. */
    VOLATILITY_TINY,
}

/**
 * Ein Faktor als Daten; Text und Zahlenformat macht die Oberfläche.
 *
 * [value] / [secondary] je Art:
 *  - VOLUME: value = Volumen-Verhältnis (3.4 = 3,4×)
 *  - MARKET: value = Referenz 24h % ([ref]: BTC, ETH oder breiter Markt; bei Bitcoin selbst: BTC),
 *    secondary = Coin 24h % bzw. ETH 24h % (oder null)
 *  - VOLATILITY: value = Faktor gegenüber üblich (|z|)
 *  - OPEN_INTEREST: value = Open-Interest-Veränderung %
 *  - NEAR_HIGH: value = Abstand zum 30-Tage-Hoch in % (≥ 0, 0 = auf dem Hoch)
 *  - NEAR_LOW: value = Abstand zum 30-Tage-Tief in % (≥ 0, 0 = auf dem Tief)
 *  - POSITION_3D: value = Abstand zum 3-Tage-Hoch (≤ 0) bzw. -Tief (≥ 0) in %
 *  - PEG: value = Abweichung von 1:1 in % (mit Vorzeichen)
 *  - FUNDING: value = Funding Rate % je Periode der Börse ([intervalHours]: Hyperliquid pro Stunde)
 *  - SENTIMENT: value = Fear & Greed (0–100), secondary = Veränderung zu gestern (oder null)
 *
 * @param strength Rang: wie deutlich der Faktor über seiner Schwelle liegt (≥ 1 = auffällig)
 * @param neutral unauffällig — wird abgeblendet und nach den auffälligen gezeigt
 */
data class WhyFactor(
    val kind: WhyFactorKind,
    val direction: WhyFactorDirection,
    val note: WhyFactorNote,
    val value: Double,
    val secondary: Double? = null,
    val strength: Double,
    val neutral: Boolean,
    /** MARKET: Referenz von [value]; null = BTC. */
    val ref: MarketRef? = null,
    /** VOLUME: nur genähert («geschätzt»). */
    val approximate: Boolean = false,
    /** FUNDING: Verlauf («seit 24 Std. steigend»); null = keiner. */
    val trend: FundingTrend? = null,
    /** FUNDING: Abrechnungsintervall in Stunden, wenn nicht 8 (Hyperliquid: 1 → «pro Std.»); null = 8. */
    val intervalHours: Int? = null,
)
