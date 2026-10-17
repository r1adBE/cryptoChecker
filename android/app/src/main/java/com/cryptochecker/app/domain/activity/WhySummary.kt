package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/** Erster Satz von «Kurz gesagt». */
enum class WhyHeadline {
    CALM, COIN_VOLUME, COIN, AGAINST, MARKET_VOLUME, MARKET,
    /** Stablecoin nahe an seiner 1:1-Bindung. */
    PEG_NEAR,
    /** Stablecoin weicht spürbar von 1:1 ab. */
    PEG_OFF,
}

/** Zusatzsätze, in dieser Reihenfolge. */
enum class WhyExtra { THIN, LONGS, SHORTS }

/** «Kurz gesagt»: erster Satz (null = keiner passt) und Zusatzsätze. */
data class WhyBrief(val headline: WhyHeadline?, val extras: List<WhyExtra>) {
    val isEmpty: Boolean get() = headline == null && extras.isEmpty()
}

/** Markierung vor einem Grund: ✓ stützt die Bewegung, ! erhöht/Vorsicht, – neutral. */
enum class WhyMark { SUPPORTS, CAUTION, NEUTRAL }

/**
 * Reine Regeln für die Kurzfassung und die Markierungen im «Warum»-Blatt —
 * abgeleitet aus denselben Gründen, die das Blatt zeigt ([WhyReport.reasons]).
 */
object WhySummary {

    fun brief(reasons: List<Reason>): WhyBrief {
        val kinds = reasons.map { it.kind }.toSet()
        val leader = reasons.firstOrNull { it.kind == ReasonKind.MARKET_LEADER }
        // Bitcoin selbst: ruhig, solange es die Marktschwelle nicht erreicht
        val leaderCalm = leader != null && !leaderMoving(leader)
        val volumeHigh = ReasonKind.VOLUME_HIGH in kinds
        val peg = reasons.firstOrNull { it.kind == ReasonKind.PEG }

        val headline = when {
            // Stablecoin: Abweichung von 1:1 statt Markt-Einordnung
            peg != null -> if (StablePeg.isNotable(peg.value)) WhyHeadline.PEG_OFF else WhyHeadline.PEG_NEAR
            ReasonKind.MARKET_CALM in kinds || leaderCalm -> WhyHeadline.CALM
            ReasonKind.COIN_ONLY in kinds -> if (volumeHigh) WhyHeadline.COIN_VOLUME else WhyHeadline.COIN
            ReasonKind.AGAINST_MARKET in kinds -> WhyHeadline.AGAINST
            ReasonKind.MARKET_WIDE in kinds || leader != null ->
                if (volumeHigh) WhyHeadline.MARKET_VOLUME else WhyHeadline.MARKET
            else -> null
        }

        val extras = buildList {
            if (ReasonKind.VOLUME_LOW in kinds && headline != WhyHeadline.CALM && peg == null) add(WhyExtra.THIN)
            if (ReasonKind.LEVERAGE_LONGS in kinds) add(WhyExtra.LONGS)
            if (ReasonKind.LEVERAGE_SHORTS in kinds) add(WhyExtra.SHORTS)
        }
        return WhyBrief(headline, extras)
    }

    /**
     * Marktweit nur «ähnlich»: Die gezeigte Referenz blieb unter ihrer Schwelle, bewegte sich aber
     * in dieselbe Richtung und mindestens halb so stark wie der Coin ([ActivityAnalyzer.SIMILAR_RATIO]).
     */
    fun isSimilarOnly(reason: Reason): Boolean {
        val threshold = reason.threshold ?: return false
        return reason.kind == ReasonKind.MARKET_WIDE && abs(reason.value) < threshold
    }

    /** Bitcoin bewegt sich so stark, wie es für «der ganze Markt bewegt sich» nötig ist (Schwelle aus dem Bericht). */
    fun leaderMoving(reason: Reason): Boolean =
        reason.value.isFinite() && abs(reason.value) >= (reason.threshold ?: ActivityAnalyzer.MARKET_MOVE_PERCENT)

    fun mark(reason: Reason): WhyMark = when (reason.kind) {
        ReasonKind.VOLUME_HIGH, ReasonKind.VOLATILITY_HIGH -> WhyMark.SUPPORTS
        // Markt bewegt sich, der Coin in dieselbe Richtung (value = BTC, secondary = Coin)
        ReasonKind.MARKET_WIDE -> {
            val coin = reason.secondary
            if (coin != null && coin != 0.0 && (coin > 0) == (reason.value > 0)) WhyMark.SUPPORTS else WhyMark.NEUTRAL
        }
        // Bitcoin selbst ist der Markt: bewegt er sich deutlich, stützt das die Bewegung
        ReasonKind.MARKET_LEADER -> if (leaderMoving(reason)) WhyMark.SUPPORTS else WhyMark.NEUTRAL
        // Stablecoin: spürbare Abweichung von 1:1 gilt als erhöht
        ReasonKind.PEG -> if (StablePeg.isNotable(reason.value)) WhyMark.CAUTION else WhyMark.NEUTRAL
        ReasonKind.LEVERAGE_LONGS, ReasonKind.LEVERAGE_SHORTS,
        ReasonKind.VOLUME_LOW, ReasonKind.AGAINST_MARKET -> WhyMark.CAUTION
        // Extreme Angst oder Gier gilt als erhöht
        ReasonKind.SENTIMENT -> if (reason.tone == ReasonTone.WARNING) WhyMark.CAUTION else WhyMark.NEUTRAL
        ReasonKind.VOLUME_NORMAL, ReasonKind.LEVERAGE_BALANCED, ReasonKind.MARKET_CALM,
        ReasonKind.VOLATILITY_NORMAL, ReasonKind.COIN_ONLY -> WhyMark.NEUTRAL
    }
}
