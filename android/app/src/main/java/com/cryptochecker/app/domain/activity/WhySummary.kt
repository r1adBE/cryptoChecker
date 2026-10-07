package com.cryptochecker.app.domain.activity

import kotlin.math.abs

/** Erster Satz von «Kurz gesagt». */
enum class WhyHeadline { CALM, COIN_VOLUME, COIN, AGAINST, MARKET_VOLUME, MARKET }

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
        val leaderCalm = leader != null && abs(leader.value) < ActivityAnalyzer.MARKET_MOVE_PERCENT
        val volumeHigh = ReasonKind.VOLUME_HIGH in kinds

        val headline = when {
            ReasonKind.MARKET_CALM in kinds || leaderCalm -> WhyHeadline.CALM
            ReasonKind.COIN_ONLY in kinds -> if (volumeHigh) WhyHeadline.COIN_VOLUME else WhyHeadline.COIN
            ReasonKind.AGAINST_MARKET in kinds -> WhyHeadline.AGAINST
            ReasonKind.MARKET_WIDE in kinds || leader != null ->
                if (volumeHigh) WhyHeadline.MARKET_VOLUME else WhyHeadline.MARKET
            else -> null
        }

        val extras = buildList {
            if (ReasonKind.VOLUME_LOW in kinds && headline != WhyHeadline.CALM) add(WhyExtra.THIN)
            if (ReasonKind.LEVERAGE_LONGS in kinds) add(WhyExtra.LONGS)
            if (ReasonKind.LEVERAGE_SHORTS in kinds) add(WhyExtra.SHORTS)
        }
        return WhyBrief(headline, extras)
    }

    fun mark(reason: Reason): WhyMark = when (reason.kind) {
        ReasonKind.VOLUME_HIGH, ReasonKind.VOLATILITY_HIGH -> WhyMark.SUPPORTS
        // Markt bewegt sich, der Coin in dieselbe Richtung (value = BTC, secondary = Coin)
        ReasonKind.MARKET_WIDE -> {
            val coin = reason.secondary
            if (coin != null && coin != 0.0 && (coin > 0) == (reason.value > 0)) WhyMark.SUPPORTS else WhyMark.NEUTRAL
        }
        // Bitcoin selbst ist der Markt: bewegt er sich deutlich, stützt das die Bewegung
        ReasonKind.MARKET_LEADER ->
            if (abs(reason.value) >= ActivityAnalyzer.MARKET_MOVE_PERCENT) WhyMark.SUPPORTS else WhyMark.NEUTRAL
        ReasonKind.LEVERAGE_LONGS, ReasonKind.LEVERAGE_SHORTS,
        ReasonKind.VOLUME_LOW, ReasonKind.AGAINST_MARKET -> WhyMark.CAUTION
        // Extreme Angst oder Gier gilt als erhöht
        ReasonKind.SENTIMENT -> if (reason.tone == ReasonTone.WARNING) WhyMark.CAUTION else WhyMark.NEUTRAL
        ReasonKind.VOLUME_NORMAL, ReasonKind.LEVERAGE_BALANCED, ReasonKind.MARKET_CALM,
        ReasonKind.VOLATILITY_NORMAL, ReasonKind.COIN_ONLY -> WhyMark.NEUTRAL
    }
}
