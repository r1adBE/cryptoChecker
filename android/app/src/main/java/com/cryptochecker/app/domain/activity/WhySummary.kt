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

/** «Sicherheit: hoch / mittel / niedrig» der wahrscheinlichen Gründe. */
enum class WhyConfidenceLevel { LOW, MEDIUM, HIGH }

/**
 * Wie sicher die Einordnung ist — nie eine sichere Ursache, nur wie viele unabhängige
 * Hinweise zusammenpassen.
 *
 * @param agreeing so viele Hinweise deuten auf die Einordnung («2 von 4 …»)
 * @param total so viele Hinweise liessen sich prüfen (Markt vs. Coin, Volumen, Volatilität, Hebel)
 * @param partialData Daten unvollständig (Kerzen, Marktvergleich oder mehrere Hinweise fehlen)
 */
data class WhyConfidence(
    val level: WhyConfidenceLevel,
    val agreeing: Int,
    val total: Int,
    val partialData: Boolean,
)

/**
 * Reine Regeln für «Sicherheit» im «Warum»-Blatt (auch aus «Heute auffällig»), abgeleitet aus
 * denselben Gründen wie «Kurz gesagt». Unabhängige Hinweise (je höchstens einer):
 * Markt vs. Coin, Volumen, Volatilität, Hebel (Funding/Open Interest). Die Stimmung
 * (Fear & Greed) ist Hintergrund und zählt nicht.
 *
 *  - hoch: mindestens [HIGH_MIN_STRONG] passende starke Hinweise, vollständige Daten, kein Widerspruch
 *  - niedrig: unvollständige Daten, keine Einordnung, kein passender oder nur ein schwacher Hinweis
 *  - sonst mittel
 */
object WhyConfidenceRules {
    /** Ab so vielen geprüften Hinweisen gelten die Daten als vollständig genug. */
    const val MIN_HINTS_FOR_GOOD_DATA = 3

    /** «hoch» braucht so viele passende starke Hinweise. */
    const val HIGH_MIN_STRONG = 3

    private enum class Hint { MARKET, VOLUME, VOLATILITY, LEVERAGE }

    private fun hintOf(kind: ReasonKind): Hint? = when (kind) {
        ReasonKind.MARKET_WIDE, ReasonKind.COIN_ONLY, ReasonKind.AGAINST_MARKET,
        ReasonKind.MARKET_CALM, ReasonKind.MARKET_LEADER -> Hint.MARKET
        ReasonKind.VOLUME_HIGH, ReasonKind.VOLUME_LOW, ReasonKind.VOLUME_NORMAL -> Hint.VOLUME
        ReasonKind.VOLATILITY_HIGH, ReasonKind.VOLATILITY_NORMAL -> Hint.VOLATILITY
        ReasonKind.LEVERAGE_LONGS, ReasonKind.LEVERAGE_SHORTS, ReasonKind.LEVERAGE_BALANCED -> Hint.LEVERAGE
        ReasonKind.SENTIMENT -> null
    }

    /** Passt zur Einordnung, widerspricht ihr oder ist neutral. */
    private enum class Fit { AGREES, CONTRADICTS, NEUTRAL }

    private fun fit(reason: Reason, calm: Boolean): Fit = if (calm) {
        when (reason.kind) {
            ReasonKind.MARKET_CALM -> Fit.AGREES
            ReasonKind.MARKET_LEADER -> if (WhySummary.mark(reason) == WhyMark.SUPPORTS) Fit.CONTRADICTS else Fit.AGREES
            ReasonKind.VOLUME_NORMAL, ReasonKind.VOLUME_LOW, ReasonKind.VOLATILITY_NORMAL -> Fit.AGREES
            ReasonKind.VOLUME_HIGH, ReasonKind.VOLATILITY_HIGH -> Fit.CONTRADICTS
            ReasonKind.LEVERAGE_BALANCED -> if (reason.strong) Fit.NEUTRAL else Fit.AGREES
            else -> Fit.NEUTRAL
        }
    } else {
        when (reason.kind) {
            ReasonKind.COIN_ONLY, ReasonKind.AGAINST_MARKET -> Fit.AGREES
            ReasonKind.MARKET_WIDE, ReasonKind.MARKET_LEADER ->
                if (WhySummary.mark(reason) == WhyMark.SUPPORTS) Fit.AGREES else Fit.NEUTRAL
            ReasonKind.VOLUME_HIGH, ReasonKind.VOLATILITY_HIGH -> Fit.AGREES
            // Wenig Volumen hinter einer Bewegung: spricht gegen eine klare Einordnung
            ReasonKind.VOLUME_LOW -> Fit.CONTRADICTS
            ReasonKind.LEVERAGE_LONGS, ReasonKind.LEVERAGE_SHORTS -> Fit.AGREES
            // Ausgeglichenes Funding zählt nur mit sprunghaftem Open Interest
            ReasonKind.LEVERAGE_BALANCED -> if (reason.strong) Fit.AGREES else Fit.NEUTRAL
            else -> Fit.NEUTRAL
        }
    }

    /**
     * Sicherheit der Einordnung; null, wenn sich nichts prüfen liess (keine Marktdaten
     * oder keine Hinweise) — dann zeigt das Blatt keine Sicherheit.
     */
    fun evaluate(reasons: List<Reason>, hasMarketData: Boolean): WhyConfidence? {
        if (!hasMarketData) return null
        val byHint = LinkedHashMap<Hint, Reason>()
        reasons.forEach { r -> hintOf(r.kind)?.let { byHint.putIfAbsent(it, r) } }
        if (byHint.isEmpty()) return null

        val headline = WhySummary.brief(reasons).headline
        val calm = headline == WhyHeadline.CALM
        var agreeing = 0
        var strongAgreeing = 0
        var contradiction = false
        byHint.values.forEach { r ->
            when (fit(r, calm)) {
                Fit.AGREES -> {
                    agreeing++
                    // Ruhe ist keine Stärke-Frage: jeder passende Hinweis zählt voll
                    if (calm || r.strong) strongAgreeing++
                }
                Fit.CONTRADICTS -> contradiction = true
                Fit.NEUTRAL -> Unit
            }
        }
        val total = byHint.size
        val partial = Hint.MARKET !in byHint || total < MIN_HINTS_FOR_GOOD_DATA
        val level = when {
            partial || headline == null || agreeing == 0 || (agreeing == 1 && strongAgreeing == 0) ->
                WhyConfidenceLevel.LOW
            strongAgreeing >= HIGH_MIN_STRONG && !contradiction -> WhyConfidenceLevel.HIGH
            else -> WhyConfidenceLevel.MEDIUM
        }
        return WhyConfidence(level, agreeing, total, partial)
    }
}
