package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketPlainSummary
import com.cryptochecker.app.domain.market.PlainSummary
import com.cryptochecker.app.domain.market.PlainSummaryInput

/**
 * Grösse des Widgets «Was gerade auffällt» für «Kurz gesagt» (wie die iOS-Familien):
 * klein bleibt unverändert, mittel ersetzt den Leitsatz durch den ersten Satz, gross zeigt
 * unter dem bisherigen Inhalt den Block «Kurz gesagt».
 */
enum class PulseWidgetSize { SMALL, MEDIUM, LARGE }

/**
 * «Kurz gesagt» im Widget «Was gerade auffällt» (reine Regeln ohne Android, getestet in
 * PulseWidgetPlainTest; Swift-Spiegel `PulseWidgetPlain.swift`). Die Sätze kommen unverändert aus
 * [MarketPlainSummary] — wie der Block im Markt-Tab, mit denselben Frische-Regeln.
 *
 * Das Widget nutzt nur, was es auf beiden Plattformen hat: die Veränderung des Gesamtmarkts aus dem
 * Puls (Satz 1) und Fear & Greed (Satz 2). «Heute auffällig» und die Karte «Krypto-Markt» liegen in
 * iOS nicht im App-Group-Speicher — damit beide Plattformen gleich bleiben, entfällt Satz 3 im
 * Widget, und Satz 1 kommt nur aus dem Puls.
 */
object PulseWidgetPlain {

    /** Ab dieser Breite (dp) mittel/gross; schmaler wie die kleine iOS-Familie (unverändert). Wie das Portfolio-Widget. */
    const val WIDE_MIN_WIDTH_DP = 250

    /** Schriftgrössen im Block (sp): Titel, Sätze, Hinweis «keine Prognose, keine Anlageberatung». */
    const val TITLE_SP = 12f
    const val SENTENCE_SP = 13f
    const val FOOTNOTE_SP = 11f

    /** Leitsatz in dieser Grösse (sp, widget_pulse.xml) — der erste Satz im mittleren Widget ebenso. */
    const val LEAD_SP = 12f

    /** Abstand über dem Block, vor jedem Satz und vor dem Hinweis (dp, widget_pulse.xml). */
    const val BLOCK_TOP_DP = 8f
    const val SENTENCE_GAP_DP = 4f
    const val FOOTNOTE_GAP_DP = 4f

    /** Sicherheitsabstand in der Höhe (dp) für Rundungen beim Messen. */
    const val SAFETY_DP = 4f

    /** Höchstens so viele Sätze (wie die Karte). */
    const val MAX_SENTENCES = 3

    /**
     * Eingaben wie im Markt-Tab, aus dem, was das Widget hat: Puls ([pulseMarketChange24h], gespeichert
     * [pulseSavedAt]) und Fear & Greed aus dem Zwischenspeicher ([fearGreed], gespeichert [fearGreedSavedAt]).
     * Gültigkeit je Quelle wie in der App ([CycleSource.ttlMillis]) — so gilt dieselbe Regel «veraltet».
     * Der Zeitpunkt des Index-Werts selbst ist im Zwischenspeicher nicht abgelegt (unbekannt, wie in der App
     * nach einem Start aus dem Zwischenspeicher).
     */
    fun input(
        now: Long,
        pulseMarketChange24h: Double?,
        pulseSavedAt: Long?,
        fearGreed: Int?,
        fearGreedSavedAt: Long?,
    ): PlainSummaryInput = PlainSummaryInput(
        now = now,
        pulseMarketChange24h = pulseMarketChange24h,
        pulseStamp = pulseSavedAt?.let { DataStamp(null, it, CycleSource.PULSE.ttlMillis) },
        fearGreed = fearGreed,
        fearGreedTime = null,
        fearGreedStamp = fearGreedSavedAt?.let { DataStamp(null, it, CycleSource.FEAR_GREED.ttlMillis) },
    )

    /**
     * Grösse: schmal oder unbekannt = klein (unverändert); breit und Platz für mindestens einen
     * ganzen Satz im Block ([blockFits], aus [sentenceCount]) = gross; sonst mittel.
     */
    fun size(widthDp: Int, blockFits: Boolean): PulseWidgetSize = when {
        widthDp < WIDE_MIN_WIDTH_DP -> PulseWidgetSize.SMALL
        blockFits -> PulseWidgetSize.LARGE
        else -> PulseWidgetSize.MEDIUM
    }

    /**
     * Höhe (dp) unter dem bisherigen Inhalt — dieselbe Rechnung wie [PulseWidgetMath.sideLinesFit]:
     * [PulseWidgetMath.FULL_MIN_HEIGHT_DP] (Kopf, Schlagzeile, 2 Zeilen Leitsatz, Coins, Stand),
     * je Nebenzeile [PulseWidgetMath.FEAR_GREED_LINE_DP], zweizeilige Schlagzeile mehr.
     * Unbekannte Höhe (≤ 0): 0 (dann kein Block — nie ein abgeschnittener Satz).
     */
    fun blockAvailableDp(heightDp: Int, headlineLines: Int, sideLines: Int): Float {
        if (heightDp <= 0) return 0f
        val used = PulseWidgetMath.FULL_MIN_HEIGHT_DP +
            sideLines.coerceIn(0, 2) * PulseWidgetMath.FEAR_GREED_LINE_DP +
            (if (headlineLines > 1) PulseWidgetMath.HEADLINE_SECOND_LINE_DP else 0)
        return (heightDp - used - SAFETY_DP).coerceAtLeast(0f)
    }

    /**
     * Wie viele Sätze (von vorne, je ganz, nie abgeschnitten) samt Titel und Hinweis in
     * [availableDp] passen; die übrigen entfallen von hinten. 0 = der Block entfällt.
     * Die Coins und Nebenzeilen darüber behalten ihren Platz ([blockAvailableDp] rechnet sie ab).
     * @param sentenceHeightsDp gemessene Höhe jedes Satzes (umbrochen auf die Breite)
     */
    fun sentenceCount(availableDp: Float, titleDp: Float, footnoteDp: Float, sentenceHeightsDp: List<Float>): Int {
        if (availableDp <= 0f || sentenceHeightsDp.isEmpty()) return 0
        var used = BLOCK_TOP_DP + titleDp + FOOTNOTE_GAP_DP + footnoteDp
        var count = 0
        for (height in sentenceHeightsDp.take(MAX_SENTENCES)) {
            val need = SENTENCE_GAP_DP + height
            if (used + need > availableDp) break
            used += need
            count++
        }
        return count
    }

    /**
     * Mittleres Widget: der erste Satz statt des Leitsatzes — nur, wenn er ganz in die Zeilen des
     * Leitsatzes passt ([leadLines], [PulseWidgetMath.leadLines]); sonst bleibt der Leitsatz.
     * @param sentenceLines Zeilen des ersten Satzes (umbrochen, [LEAD_SP]); null = kein Satz
     */
    fun sentenceAsLead(sentenceLines: Int?, leadLines: Int): Boolean =
        sentenceLines != null && sentenceLines in 1..leadLines

    /**
     * Quellen der ersten [shownCount] Sätze für den Hinweis im grossen Widget («Daten: CoinGecko,
     * alternative.me · …»): Richtung aus CoinGecko `/global`, Stimmung von alternative.me, was heraussticht
     * aus CoinGecko-Daten (wie die Karte). Reihenfolge wie die Sätze, ohne Doppel; null = keine.
     */
    fun sources(summary: PlainSummary, shownCount: Int): String? {
        val perSentence = listOfNotNull(
            summary.direction?.let { DataFreshness.COINGECKO },
            summary.mood?.let { DataFreshness.ALTERNATIVE_ME },
            summary.standout?.let { DataFreshness.COINGECKO },
        )
        return DataFreshness.providers(*perSentence.take(shownCount.coerceAtLeast(0)).toTypedArray())
    }

    /**
     * Zeitpunkt, ab dem ein gezeigter Satz auf veralteten Daten stünde (frühester von allen; null =
     * kein Satz). Dann zeichnet das Widget ohne Netz neu, damit kein Satz länger steht als in der App.
     */
    fun expiresAt(input: PlainSummaryInput, summary: PlainSummary): Long? {
        fun staleAt(stamp: DataStamp?): Long? =
            stamp?.takeIf { it.savedAt > 0L && it.ttlMillis > 0L }?.let { it.savedAt + DataFreshness.STALE_FACTOR * it.ttlMillis + 1 }
        val times = listOfNotNull(
            staleAt(input.pulseStamp).takeIf { summary.direction != null },
            staleAt(input.fearGreedStamp).takeIf { summary.mood != null },
            input.fearGreedTime?.let { it + MarketPlainSummary.FEAR_GREED_MAX_AGE_MILLIS + 1 }?.takeIf { summary.mood != null },
        )
        return times.minOrNull()
    }
}
