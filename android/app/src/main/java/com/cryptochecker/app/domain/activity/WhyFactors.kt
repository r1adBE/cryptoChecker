package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/** Faktor im «Warum?»-Blatt; Reihenfolge = Rang bei gleicher Stärke. */
enum class WhyFactorKind { VOLUME, MARKET, VOLATILITY, OPEN_INTEREST, NEAR_HIGH, FUNDING, SENTIMENT }

/** Pfeil vor dem Titel: ↑ / ↓ oder keiner. */
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
}

/**
 * Ein Faktor als Daten; Text und Zahlenformat macht die Oberfläche.
 *
 * [value] / [secondary] je Art:
 *  - VOLUME: value = Volumen-Verhältnis (3.4 = 3,4×)
 *  - MARKET: value = BTC 24h % (bei Bitcoin selbst: BTC), secondary = Coin 24h % bzw. ETH 24h % (oder null)
 *  - VOLATILITY: value = Faktor gegenüber üblich (|z|)
 *  - OPEN_INTEREST: value = Open-Interest-Veränderung %
 *  - NEAR_HIGH: value = Abstand zum 30-Tage-Hoch in % (≥ 0, 0 = auf dem Hoch)
 *  - FUNDING: value = Funding Rate % je Periode
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
)

/**
 * Reine Regeln für die Faktorliste im «Warum?»-Blatt: aus denselben Gründen wie «Kurz gesagt»
 * und «Sicherheit» ([WhyReport.reasons]) plus Open Interest und Nähe zum 30-Tage-Hoch, wenn es
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

    /** Alle Faktoren mit Daten, gerankt: auffällige nach Stärke, dann neutrale; höchstens [max]. */
    fun rank(report: WhyReport, max: Int = MAX_FACTORS): List<WhyFactor> {
        val all = of(report)
        val active = all.filter { !it.neutral }
            .sortedWith(compareByDescending<WhyFactor> { it.strength }.thenBy { it.kind.ordinal })
        val neutral = all.filter { it.neutral }.sortedBy { it.kind.ordinal }
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
                    add(funding(r))
                    r.secondary?.let { add(openInterest(it)) }
                }
                ReasonKind.SENTIMENT -> add(sentiment(r))
            }
        }
        add(nearHigh(report.price, report.high30d))
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
    }

    private fun market(r: Reason): WhyFactor {
        val moveThreshold = ActivityAnalyzer.MARKET_MOVE_PERCENT
        return when (r.kind) {
            // value = BTC, secondary = Coin
            ReasonKind.MARKET_WIDE -> {
                val follows = WhySummary.mark(r) == WhyMark.SUPPORTS
                WhyFactor(
                    WhyFactorKind.MARKET, direction(r.value),
                    if (follows) WhyFactorNote.MARKET_PULLS else WhyFactorNote.MARKET_COIN_LAGS,
                    r.value, r.secondary,
                    // Coin zieht nicht mit: der Markt erklärt die Bewegung nur halb
                    strength = abs(r.value) / moveThreshold * (if (follows) 1.0 else 0.5),
                    neutral = false,
                )
            }
            // value = Coin, secondary = BTC → Faktor zeigt den Markt (BTC)
            ReasonKind.COIN_ONLY -> WhyFactor(
                WhyFactorKind.MARKET, WhyFactorDirection.NONE, WhyFactorNote.MARKET_COIN_ALONE,
                r.secondary ?: 0.0, r.value,
                strength = abs(r.value) / ActivityAnalyzer.COIN_MOVE_PERCENT, neutral = false,
            )
            ReasonKind.AGAINST_MARKET -> {
                val btc = r.secondary ?: 0.0
                WhyFactor(
                    WhyFactorKind.MARKET, direction(btc), WhyFactorNote.MARKET_AGAINST, btc, r.value,
                    strength = abs(r.value) / ActivityAnalyzer.COIN_MOVE_PERCENT, neutral = false,
                )
            }
            // Bitcoin selbst: value = BTC, secondary = ETH
            ReasonKind.MARKET_LEADER -> {
                val moves = abs(r.value) >= moveThreshold
                WhyFactor(
                    WhyFactorKind.MARKET, if (moves) direction(r.value) else WhyFactorDirection.NONE,
                    if (moves) WhyFactorNote.MARKET_LEADER_MOVES else WhyFactorNote.MARKET_LEADER_CALM,
                    r.value, r.secondary,
                    strength = if (moves) abs(r.value) / moveThreshold else 0.0, neutral = !moves,
                )
            }
            // MARKET_CALM: value = BTC, secondary = Coin
            else -> WhyFactor(
                WhyFactorKind.MARKET, WhyFactorDirection.NONE, WhyFactorNote.MARKET_CALM, r.value, r.secondary,
                strength = 0.0, neutral = true,
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
            WhyFactorKind.VOLATILITY, WhyFactorDirection.NONE, WhyFactorNote.VOLATILITY_USUAL, r.value,
            strength = 0.0, neutral = true,
        )
    }

    private fun funding(r: Reason): WhyFactor {
        val strength = abs(r.value) / ActivityAnalyzer.FUNDING_EXTREME_PERCENT
        return when (r.kind) {
            ReasonKind.LEVERAGE_LONGS -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.UP, WhyFactorNote.FUNDING_LONGS, r.value,
                strength = strength, neutral = false,
            )
            ReasonKind.LEVERAGE_SHORTS -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.DOWN, WhyFactorNote.FUNDING_SHORTS, r.value,
                strength = strength, neutral = false,
            )
            else -> WhyFactor(
                WhyFactorKind.FUNDING, WhyFactorDirection.NONE, WhyFactorNote.FUNDING_NEUTRAL, r.value,
                strength = 0.0, neutral = true,
            )
        }
    }

    private fun openInterest(change: Double): WhyFactor {
        val notable = abs(change) >= OI_NOTABLE_PERCENT
        val note = when {
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
