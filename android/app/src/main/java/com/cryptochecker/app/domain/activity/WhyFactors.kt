package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/** Faktor im «Warum?»-Blatt; Reihenfolge = Rang bei gleicher Stärke. */
enum class WhyFactorKind {
    VOLUME, MARKET, VOLATILITY, OPEN_INTEREST, NEAR_HIGH, FUNDING, SENTIMENT,
    NEAR_LOW, POSITION_3D, PEG,
}

/** Pfeil vor dem Titel: ↗ / ↘ oder keiner. */
enum class WhyFactorDirection { UP, DOWN, NONE }

/** Kurze Nebenzeile eines Faktors («höher als üblich», «BTC zieht den Markt mit» …). */
enum class WhyFactorNote {
    VOLUME_HIGHER, VOLUME_LOWER, VOLUME_USUAL,
    MARKET_PULLS, MARKET_COIN_LAGS, MARKET_COIN_ALONE, MARKET_AGAINST, MARKET_CALM,
    MARKET_LEADER_MOVES, MARKET_LEADER_CALM,
    VOLATILITY_STRONGER, VOLATILITY_USUAL,
    OI_UP, OI_DOWN, OI_FLAT,
    HIGH_AT, HIGH_BELOW,
    FUNDING_LONGS, FUNDING_SHORTS, FUNDING_NEUTRAL,
    SENTIMENT,
    /** Markt (ETH oder breiter Markt) bewegt sich in dieselbe Richtung wie der Coin. */
    MARKET_MOVES,
    /** Open Interest gegenüber den älteren Messungen uneinheitlich. */
    OI_MIXED,
    LOW_AT, LOW_ABOVE,
    RANGE_NEAR_HIGH, RANGE_NEAR_LOW, RANGE_BELOW_HIGH, RANGE_ABOVE_LOW,
    PEG_NEAR, PEG_BELOW, PEG_ABOVE,
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

/**
 * Reine Regeln für die Faktorliste im «Warum?»-Blatt: aus denselben Gründen wie «Kurz gesagt»
 * ([WhyReport.reasons]) plus Open Interest und Nähe zum 30-Tage-Hoch, wenn es
 * Daten gibt. Nur Faktoren mit Daten erscheinen; auffällige zuerst (stärkster oben), neutrale
 * abgeblendet danach, höchstens [MAX_FACTORS]. Swift-Spiegel: WhyFactors.swift.
 */
object WhyFactors {

    const val MAX_FACTORS = 5

    /** Bis zu diesem Abstand (in %) zum 30-Tage-Hoch zählt «Nähe zum Hoch» als auffällig. */
    const val NEAR_HIGH_PERCENT = 5.0

    /** Bis zu diesem Abstand (in %) steht «auf dem 30-Tage-Hoch». */
    const val AT_HIGH_PERCENT = 0.1

    /** Ab dieser Open-Interest-Veränderung (in %, Betrag) ist der Faktor nicht mehr neutral. */
    const val OI_NOTABLE_PERCENT = 5.0

    /** Ab dieser Veränderung zu gestern bekommt die Stimmung einen Pfeil. */
    const val SENTIMENT_ARROW_POINTS = 5.0

    /** «Nähe zum Tief»: gleiche Schwellen wie beim Hoch. */
    const val NEAR_LOW_PERCENT = NEAR_HIGH_PERCENT
    const val AT_LOW_PERCENT = AT_HIGH_PERCENT

    /** Alle Faktoren mit Daten, gerankt: auffällige nach Stärke, dann neutrale; höchstens [max]. */
    fun rank(report: WhyReport, max: Int = MAX_FACTORS): List<WhyFactor> {
        val all = of(report)
        val active = all.filter { !it.neutral }
            .sortedWith(compareByDescending<WhyFactor> { it.strength }.thenBy { it.kind.ordinal })
        // Neutrale nach Art; beim Stablecoin steht die Bindung (1:1) vorn
        val neutral = all.filter { it.neutral }
            .sortedWith(compareBy<WhyFactor> { if (it.kind == WhyFactorKind.PEG) 0 else 1 }.thenBy { it.kind.ordinal })
        return (active + neutral).take(max.coerceAtLeast(0))
    }

    /** Faktoren in fester Reihenfolge (ungerankt), je Art höchstens einer. */
    fun of(report: WhyReport): List<WhyFactor> {
        val result = ArrayList<WhyFactor>()
        val seen = HashSet<WhyFactorKind>()
        fun add(factor: WhyFactor?) {
            if (factor != null && factor.value.isFinite() && seen.add(factor.kind)) result += factor
        }
        report.reasons.forEach { r ->
            when (r.kind) {
                ReasonKind.VOLUME_HIGH, ReasonKind.VOLUME_LOW, ReasonKind.VOLUME_NORMAL -> add(volume(r))
                ReasonKind.MARKET_WIDE, ReasonKind.COIN_ONLY, ReasonKind.AGAINST_MARKET,
                ReasonKind.MARKET_CALM, ReasonKind.MARKET_LEADER -> add(market(r))
                ReasonKind.VOLATILITY_HIGH, ReasonKind.VOLATILITY_NORMAL -> add(volatility(r))
                ReasonKind.LEVERAGE_LONGS, ReasonKind.LEVERAGE_SHORTS, ReasonKind.LEVERAGE_BALANCED -> {
                    add(funding(r, report.fundingTrend))
                    r.secondary?.let { add(openInterest(it, report.oiConsistent)) }
                }
                ReasonKind.SENTIMENT -> add(sentiment(r))
                ReasonKind.PEG -> add(peg(r))
            }
        }
        val high = nearHigh(report.price, report.high30d)
        val low = nearLow(report.price, report.low30d)
        add(high)
        add(low)
        // 3-Tage-Lage: nicht doppelt, wenn schon «nahe am 30-Tage-Hoch/-Tief» auffällt
        report.position3d?.let { p ->
            val duplicate = (p.kind == Position3dKind.NEAR_HIGH && high?.neutral == false) ||
                (p.kind == Position3dKind.NEAR_LOW && low != null)
            if (!duplicate) add(position3d(p))
        }
        return result
    }

    private fun direction(value: Double): WhyFactorDirection = when {
        value > 0.0 -> WhyFactorDirection.UP
        value < 0.0 -> WhyFactorDirection.DOWN
        else -> WhyFactorDirection.NONE
    }

    private fun volume(r: Reason): WhyFactor = when (r.kind) {
        ReasonKind.VOLUME_HIGH -> WhyFactor(
            WhyFactorKind.VOLUME, WhyFactorDirection.UP, WhyFactorNote.VOLUME_HIGHER, r.value,
            strength = r.value / ActivityAnalyzer.VOLUME_HIGH_RATIO, neutral = false,
        )
        ReasonKind.VOLUME_LOW -> WhyFactor(
            WhyFactorKind.VOLUME, WhyFactorDirection.DOWN, WhyFactorNote.VOLUME_LOWER, r.value,
            strength = if (r.value > 0.0) ActivityAnalyzer.VOLUME_LOW_RATIO / r.value else 1.0, neutral = false,
        )
        else -> WhyFactor(
            WhyFactorKind.VOLUME, WhyFactorDirection.NONE, WhyFactorNote.VOLUME_USUAL, r.value,
            strength = 0.0, neutral = true,
        )
    }.copy(approximate = r.approximate)

    private fun market(r: Reason): WhyFactor {
        // Schwellen aus dem Bericht (relativ zur Schwankung), sonst die festen
        val marketThreshold = r.threshold ?: ActivityAnalyzer.MARKET_MOVE_PERCENT
        val coinThreshold = r.threshold ?: ActivityAnalyzer.COIN_MOVE_PERCENT
        return when (r.kind) {
            // value = Referenz, secondary = Coin
            ReasonKind.MARKET_WIDE -> {
                val follows = WhySummary.mark(r) == WhyMark.SUPPORTS
                val note = when {
                    !follows -> WhyFactorNote.MARKET_COIN_LAGS
                    r.ref == null || r.ref == MarketRef.BTC -> WhyFactorNote.MARKET_PULLS
                    else -> WhyFactorNote.MARKET_MOVES
                }
                WhyFactor(
                    WhyFactorKind.MARKET, direction(r.value), note, r.value, r.secondary,
                    // Coin zieht nicht mit: der Markt erklärt die Bewegung nur halb
                    strength = abs(r.value) / marketThreshold * (if (follows) 1.0 else 0.5),
                    neutral = false, ref = r.ref,
                )
            }
            // value = Coin, secondary = Referenz → Faktor zeigt den Markt
            ReasonKind.COIN_ONLY -> WhyFactor(
                WhyFactorKind.MARKET, WhyFactorDirection.NONE, WhyFactorNote.MARKET_COIN_ALONE,
                r.secondary ?: 0.0, r.value,
                strength = abs(r.value) / coinThreshold, neutral = false, ref = r.ref,
            )
            ReasonKind.AGAINST_MARKET -> {
                val market = r.secondary ?: 0.0
                WhyFactor(
                    WhyFactorKind.MARKET, direction(market), WhyFactorNote.MARKET_AGAINST, market, r.value,
                    strength = abs(r.value) / coinThreshold, neutral = false, ref = r.ref,
                )
            }
            // Bitcoin selbst: value = BTC, secondary = ETH
            ReasonKind.MARKET_LEADER -> {
                val moves = abs(r.value) >= marketThreshold
                WhyFactor(
                    WhyFactorKind.MARKET, if (moves) direction(r.value) else WhyFactorDirection.NONE,
                    if (moves) WhyFactorNote.MARKET_LEADER_MOVES else WhyFactorNote.MARKET_LEADER_CALM,
                    r.value, r.secondary,
                    strength = if (moves) abs(r.value) / marketThreshold else 0.0, neutral = !moves,
                )
            }
            // MARKET_CALM: value = Referenz, secondary = Coin
            else -> WhyFactor(
                WhyFactorKind.MARKET, WhyFactorDirection.NONE, WhyFactorNote.MARKET_CALM, r.value, r.secondary,
                strength = 0.0, neutral = true, ref = r.ref,
            )
        }
    }

    private fun volatility(r: Reason): WhyFactor = if (r.kind == ReasonKind.VOLATILITY_HIGH) {
        WhyFactor(
            WhyFactorKind.VOLATILITY, WhyFactorDirection.UP, WhyFactorNote.VOLATILITY_STRONGER, r.value,
            strength = r.value / ActivityAnalyzer.VOLATILITY_HIGH_Z, neutral = false,
        )
    } else {
        WhyFactor(
            WhyFactorKind.VOLATILITY, WhyFactorDirection.NONE,
            // |z| über der Schwelle, aber unter der Mindestbewegung: nicht «wie üblich» nennen
            if (r.value >= ActivityAnalyzer.VOLATILITY_HIGH_Z) WhyFactorNote.VOLATILITY_TINY else WhyFactorNote.VOLATILITY_USUAL,
            r.value, strength = 0.0, neutral = true,
        )
    }

    private fun funding(r: Reason, trend: FundingTrend?): WhyFactor {
        // Rang auf 8 Stunden umgerechnet (stündliches Funding zählt achtfach), Anzeige bleibt der Satz der Börse
        val strength = abs(ActivityAnalyzer.fundingPer8h(r.value, r.intervalHours)) / ActivityAnalyzer.FUNDING_EXTREME_PERCENT
        return when (r.kind) {
            ReasonKind.LEVERAGE_LONGS -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.UP, WhyFactorNote.FUNDING_LONGS, r.value,
                strength = strength, neutral = false, trend = trend, intervalHours = r.intervalHours,
            )
            ReasonKind.LEVERAGE_SHORTS -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.DOWN, WhyFactorNote.FUNDING_SHORTS, r.value,
                strength = strength, neutral = false, trend = trend, intervalHours = r.intervalHours,
            )
            else -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.NONE, WhyFactorNote.FUNDING_NEUTRAL, r.value,
                strength = 0.0, neutral = true, trend = trend, intervalHours = r.intervalHours,
            )
        }
    }

    /** [consistent] = false: gegenüber den älteren Messungen uneinheitlich — dann neutral. */
    private fun openInterest(change: Double, consistent: Boolean): WhyFactor {
        val notable = abs(change) >= OI_NOTABLE_PERCENT && consistent
        val note = when {
            abs(change) >= OI_NOTABLE_PERCENT && !consistent -> WhyFactorNote.OI_MIXED
            !notable -> WhyFactorNote.OI_FLAT
            change > 0.0 -> WhyFactorNote.OI_UP
            else -> WhyFactorNote.OI_DOWN
        }
        return WhyFactor(
            WhyFactorKind.OPEN_INTEREST, if (notable) direction(change) else WhyFactorDirection.NONE, note, change,
            strength = if (notable) abs(change) / ActivityAnalyzer.OI_JUMP_PERCENT else 0.0, neutral = !notable,
        )
    }

    /** Abstand zum 30-Tage-Hoch; null ohne Kurs oder Hoch. */
    fun distanceToHighPercent(price: Double?, high: Double?): Double? {
        if (price == null || high == null || !price.isFinite() || !high.isFinite() || price <= 0.0 || high <= 0.0) {
            return null
        }
        return ((high - price) / high * 100.0).coerceAtLeast(0.0)
    }

    private fun nearHigh(price: Double?, high: Double?): WhyFactor? {
        val distance = distanceToHighPercent(price, high) ?: return null
        val near = distance <= NEAR_HIGH_PERCENT
        return WhyFactor(
            WhyFactorKind.NEAR_HIGH, WhyFactorDirection.NONE,
            if (distance <= AT_HIGH_PERCENT) WhyFactorNote.HIGH_AT else WhyFactorNote.HIGH_BELOW,
            distance,
            // Näher am Hoch = stärker (1 bei 5 % Abstand, 2 auf dem Hoch)
            strength = if (near) 1.0 + (NEAR_HIGH_PERCENT - distance) / NEAR_HIGH_PERCENT else 0.0,
            neutral = !near,
        )
    }

    /** Abstand zum 30-Tage-Tief in % (≥ 0); null ohne Kurs oder Tief. */
    fun distanceToLowPercent(price: Double?, low: Double?): Double? {
        if (price == null || low == null || !price.isFinite() || !low.isFinite() || price <= 0.0 || low <= 0.0) {
            return null
        }
        return ((price - low) / low * 100.0).coerceAtLeast(0.0)
    }

    /**
     * «Nähe zum 30-Tage-Tief» — gleiche Schwellen wie das Hoch, aber nur, wenn nahe: Weit über
     * dem Tief zu stehen ist der Normalfall und bekäme sonst eine leere, abgeblendete Zeile.
     */
    private fun nearLow(price: Double?, low: Double?): WhyFactor? {
        val distance = distanceToLowPercent(price, low) ?: return null
        if (distance > NEAR_LOW_PERCENT) return null
        return WhyFactor(
            WhyFactorKind.NEAR_LOW, WhyFactorDirection.NONE,
            if (distance <= AT_LOW_PERCENT) WhyFactorNote.LOW_AT else WhyFactorNote.LOW_ABOVE,
            distance,
            // Näher am Tief = stärker (1 bei 5 % Abstand, 2 auf dem Tief)
            strength = 1.0 + (NEAR_LOW_PERCENT - distance) / NEAR_LOW_PERCENT,
            neutral = false,
        )
    }

    /** Lage in drei Tagen (nur auffällige kommen im Bericht an). */
    private fun position3d(p: Position3d): WhyFactor {
        val near = ActivityAnalyzer.RANGE_NEAR_PERCENT
        return when (p.kind) {
            Position3dKind.NEAR_HIGH -> WhyFactor(
                WhyFactorKind.POSITION_3D, WhyFactorDirection.NONE, WhyFactorNote.RANGE_NEAR_HIGH, p.percent,
                // 1 bei 1 % Abstand, 1,5 auf dem Hoch — schwächer als das 30-Tage-Hoch
                strength = 1.0 + (near - abs(p.percent)) / near * 0.5, neutral = false,
            )
            Position3dKind.NEAR_LOW -> WhyFactor(
                WhyFactorKind.POSITION_3D, WhyFactorDirection.NONE, WhyFactorNote.RANGE_NEAR_LOW, p.percent,
                strength = 1.0 + (near - abs(p.percent)) / near * 0.5, neutral = false,
            )
            Position3dKind.BELOW_HIGH -> WhyFactor(
                WhyFactorKind.POSITION_3D, WhyFactorDirection.DOWN, WhyFactorNote.RANGE_BELOW_HIGH, p.percent,
                strength = abs(p.percent) / p.threshold, neutral = false,
            )
            Position3dKind.ABOVE_LOW -> WhyFactor(
                WhyFactorKind.POSITION_3D, WhyFactorDirection.UP, WhyFactorNote.RANGE_ABOVE_LOW, p.percent,
                strength = abs(p.percent) / p.threshold, neutral = false,
            )
        }
    }

    /** Stablecoin: Abweichung von 1:1; unter [StablePeg.NOTABLE_PERCENT] neutral. */
    private fun peg(r: Reason): WhyFactor {
        val deviation = r.value
        val notable = StablePeg.isNotable(deviation)
        val note = when {
            abs(deviation) <= StablePeg.NEAR_PERCENT -> WhyFactorNote.PEG_NEAR
            deviation < 0.0 -> WhyFactorNote.PEG_BELOW
            else -> WhyFactorNote.PEG_ABOVE
        }
        return WhyFactor(
            WhyFactorKind.PEG,
            if (notable) direction(deviation) else WhyFactorDirection.NONE,
            note, deviation,
            strength = if (notable) abs(deviation) / StablePeg.NOTABLE_PERCENT else 0.0,
            neutral = !notable,
        )
    }

    private fun sentiment(r: Reason): WhyFactor {
        val extreme = r.tone == ReasonTone.WARNING
        val change = r.secondary
        val arrow = if (change != null && abs(change) >= SENTIMENT_ARROW_POINTS) direction(change) else WhyFactorDirection.NONE
        return WhyFactor(
            WhyFactorKind.SENTIMENT, arrow, WhyFactorNote.SENTIMENT, r.value, change,
            strength = if (extreme) 1.0 else 0.0, neutral = !extreme,
        )
    }
}
