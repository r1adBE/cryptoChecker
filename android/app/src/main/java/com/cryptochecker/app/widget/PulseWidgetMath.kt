package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import com.cryptochecker.app.util.PercentText
import com.cryptochecker.app.util.PriceFormat
import java.util.Locale
import kotlin.math.abs

/** Ein Coin im Widget «Was gerade auffällt», z. B. «BTC ↗ +2.8%» (farbiger Text, keine Pille). */
data class PulseWidgetCoin(val symbol: String, val name: String, val change: Double)

/**
 * Anordnung von Fear & Greed und Marktbreite (Top 30) im Widget «Was gerade auffällt»
 * (wie iOS `PulseSideLayout`): beides in einer Zeile, untereinander in zwei Zeilen oder nur ein Teil.
 */
enum class PulseSideLayout(val showsFearGreed: Boolean, val showsBreadth: Boolean) {
    ONE_LINE(true, true),
    TWO_LINES(true, true),
    FEAR_GREED(true, false),
    BREADTH(false, true),
}

/**
 * Reine Regeln des Widgets «Was gerade auffällt» (ohne Android, getestet in
 * PulseWidgetMathTest). Auswertung und Leitsatz kommen unverändert aus
 * [com.cryptochecker.app.domain.exchange.CryptoPulse] — wie die Karte im Markt-Tab.
 */
object PulseWidgetMath {

    /** Rand links + rechts des Widgets (dp, widget_pulse.xml). */
    const val PADDING_DP = 2 * 12f

    /** Abstand zwischen den Coins (dp, widget_pulse.xml) — reiner Text ohne Pille, ohne Innenrand. */
    const val CHIP_GAP_DP = 8f

    /** Logo und Abstand vor dem Titel (dp). */
    const val LOGO_DP = 16f + 4f

    /** Zeichen ↗/↘ vor der Schlagzeile samt Abstand (dp, ungefähr; 12 sp ≈ 10 dp + 4 dp Abstand). */
    const val GLYPH_DP = 14f

    /** Schriftgrössen des Titels: normal, eng; passt keine, nur das Logo. */
    val TITLE_SIZES_SP = listOf(WidgetType.BODY, WidgetType.LABEL)

    /** Schlagzeile einzeilig in diesen Grössen; passt keine, zweizeilig in der kleinsten. */
    val HEADLINE_SIZES_SP = listOf(WidgetType.TITLE, WidgetType.BODY)

    /** Praktisch unverändert («0.0%», grau, ohne Pfeil) — wie die Karte. */
    const val FLAT_PERCENT = 0.05

    /** +1 breit steigend, −1 breit fallend, 0 gemischt oder ruhig (dann kein Zeichen). */
    fun direction(summary: PulseSummary): Int = when (summary) {
        PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> 1
        PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> -1
        PulseSummary.MIXED, PulseSummary.CALM -> 0
    }

    /** «↗» / «↘» vor der Schlagzeile (schräg wie überall in der App); null ohne Richtung. */
    fun glyph(summary: PulseSummary): String? = when (direction(summary)) {
        1 -> PriceFormat.ARROW_UP
        -1 -> PriceFormat.ARROW_DOWN
        else -> null
    }

    /**
     * Wie viele Coins (BTC, ETH, SOL in dieser Reihenfolge) ganz in die Breite passen —
     * nie ein abgeschnittener. Mindestens BTC; unbekannte Breite (0): nur BTC.
     * @param chipTextWidthsDp gemessene Textbreite jedes Coins («BTC ↗ +2.8%»)
     * @param leadingDp Breite vor den Chips (Zeitraum «24h» samt Abstand), 0 = keiner
     */
    fun coinCount(widthDp: Int, chipTextWidthsDp: List<Float>, leadingDp: Float = 0f): Int {
        if (widthDp <= 0 || chipTextWidthsDp.isEmpty()) return 1
        val available = widthDp - PADDING_DP - WidgetTextFit.SAFETY_DP - leadingDp
        var used = 0f
        var count = 0
        for (text in chipTextWidthsDp.take(3)) {
            val need = text + if (count > 0) CHIP_GAP_DP else 0f
            if (used + need > available) break
            used += need
            count++
        }
        return count.coerceAtLeast(1)
    }

    /**
     * Schriftgrösse des Titels «Was gerade auffällt» (sp) neben dem Logo: die erste aus
     * [TITLE_SIZES_SP], in der er ganz passt; null = nur das Logo (nie «Was gerade a…»).
     * Unbekannte Breite: normal.
     */
    fun titleSizeSp(widthDp: Int, measureDp: (Float) -> Float): Float? {
        if (widthDp <= 0) return TITLE_SIZES_SP.first()
        val available = widthDp - PADDING_DP - LOGO_DP - WidgetTextFit.SAFETY_DP
        return TITLE_SIZES_SP.firstOrNull { measureDp(it) <= available }
    }

    /** Schlagzeile: Schriftgrösse (sp) und Zeilen (1 oder 2). */
    data class HeadlineStyle(val sizeSp: Float, val lines: Int)

    /**
     * Schlagzeile ohne «Breite Stä…»: einzeilig in der ersten Grösse aus [HEADLINE_SIZES_SP],
     * die passt; sonst zweizeilig in der kleinsten (14 sp). [measureDp] misst einzeilig.
     */
    fun headlineStyle(widthDp: Int, hasGlyph: Boolean, measureDp: (Float) -> Float): HeadlineStyle {
        if (widthDp <= 0) return HeadlineStyle(HEADLINE_SIZES_SP.first(), 1)
        val available = widthDp - PADDING_DP - (if (hasGlyph) GLYPH_DP else 0f) - WidgetTextFit.SAFETY_DP
        val size = HEADLINE_SIZES_SP.firstOrNull { measureDp(it) <= available }
        return if (size != null) HeadlineStyle(size, 1) else HeadlineStyle(HEADLINE_SIZES_SP.last(), 2)
    }

    /** BTC, ETH, SOL in dieser Reihenfolge, höchstens [count]. */
    fun coins(report: PulseReport, count: Int): List<PulseWidgetCoin> = listOf(
        PulseWidgetCoin("BTC", "Bitcoin", report.btc24h),
        PulseWidgetCoin("ETH", "Ethereum", report.eth24h),
        PulseWidgetCoin("SOL", "Solana", report.sol24h),
    ).take(count.coerceIn(0, 3))

    fun isFlat(change: Double): Boolean = abs(change) < FLAT_PERCENT

    /**
     * «↗ +2.8%», «↘ −1.4%» oder «0.0%» (unter 0.05 %), eine Nachkommastelle —
     * gleiches Format wie die Coins der Karte. Pfeil folgt dem Vorzeichen,
     * nie dem Farbtausch.
     */
    fun changeText(change: Double, locale: Locale = Locale.getDefault()): String {
        // Prozentzeichen der Sprache ([PercentText]): «0,0 %», türkisch «%0,0», arabisch «٠٫٠٪»
        if (isFlat(change)) return PercentText.unsigned(0.0, 1, locale)
        val arrow = if (change > 0) PriceFormat.ARROW_UP else PriceFormat.ARROW_DOWN
        val sign = if (change > 0) "+" else "−"
        return arrow + " " + sign + PercentText.unsigned(change, 1, locale)
    }

    /** Text eines Coins «BTC ↗ +2.8%» (Kürzel in der Textfarbe, Veränderung in der Kursfarbe). */
    fun chipText(coin: PulseWidgetCoin, locale: Locale = Locale.getDefault()): String =
        coin.symbol + " " + changeText(coin.change, locale)

    /** Ältere Daten zeigt das Widget nicht mehr (dann «nicht verfügbar»), 6 Stunden. */
    const val MAX_AGE_MILLIS = 6 * 3_600_000L

    /** Ab dieser Höhe (dp) zwei Zeilen Leitsatz und der Stand; darunter eine Zeile, ohne Stand. */
    const val FULL_MIN_HEIGHT_DP = 150

    /** Daten noch zeigen? Zeitpunkt in der Zukunft (Uhr verstellt) oder ≤ 0: nein. */
    fun isShowable(savedAt: Long, now: Long): Boolean = savedAt in 1..now && now - savedAt <= MAX_AGE_MILLIS

    /** Zweizeilige Schlagzeile braucht so viel mehr Höhe (dp, 14 sp-Zeile). */
    const val HEADLINE_SECOND_LINE_DP = 19

    /** Zeilen des Leitsatzes: 2, bei wenig Höhe 1 (unbekannte Höhe: 2). */
    fun leadLines(heightDp: Int, headlineLines: Int = 1): Int {
        val needed = FULL_MIN_HEIGHT_DP + if (headlineLines > 1) HEADLINE_SECOND_LINE_DP else 0
        return if (heightDp <= 0 || heightDp >= needed) 2 else 1
    }

    /** Zeile «Stand …» nur mit genug Höhe (unbekannte Höhe: ja). */
    fun showsTime(heightDp: Int): Boolean = heightDp <= 0 || heightDp >= FULL_MIN_HEIGHT_DP

    /** Fear & Greed im Widget höchstens so alt (nur Zwischenspeicher, kein eigener Abruf): 24 h. */
    const val FEAR_GREED_MAX_AGE_MILLIS = 24 * 3_600_000L

    /** Höhe der Zeile «Fear & Greed 72 · Gier» samt Abstand (dp, 12 sp). */
    const val FEAR_GREED_LINE_DP = 18

    /**
     * Fear-&-Greed-Wert fürs Widget aus vorhandenen Daten: der jüngere von Markt-Tab-Eintrag
     * ([cached], gespeichert [cachedAt]) und Pulse-Eingabe ([pulse], [pulseAt]); nur 0–100 und
     * höchstens [FEAR_GREED_MAX_AGE_MILLIS] alt (Zeitpunkt in der Zukunft oder ≤ 0: nie). null = Zeile weg.
     */
    fun fearGreed(cached: Int?, cachedAt: Long?, pulse: Int?, pulseAt: Long?, now: Long): Int? =
        listOfNotNull(
            cached?.let { v -> cachedAt?.let { v to it } },
            pulse?.let { v -> pulseAt?.let { v to it } },
        )
            .filter { (value, at) -> value in 0..100 && at in 1..now && now - at <= FEAR_GREED_MAX_AGE_MILLIS }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Zeile «Fear & Greed 72 · Gier» zeigen? Nur mit Platz: unter Leitsatz (2 Zeilen) und Stand
     * noch [sideLines] Zeilen Höhe ([FULL_MIN_HEIGHT_DP] + [sideLines] × [FEAR_GREED_LINE_DP],
     * zweizeilige Schlagzeile mehr), und der Text passt ganz in die Breite. Unbekannte Grösse (0):
     * ja. Kleine Widgets bleiben unverändert. Der Leitsatz ([leadLines]) behält dabei seine
     * 2 Zeilen, weil jede Nebenzeile erst oberhalb von [FULL_MIN_HEIGHT_DP] Platz bekommt.
     */
    fun showsFearGreed(widthDp: Int, heightDp: Int, headlineLines: Int, textWidthDp: Float, sideLines: Int = 1): Boolean {
        val tallEnough = sideLinesFit(heightDp, headlineLines, sideLines)
        val wideEnough = widthDp <= 0 || textWidthDp <= widthDp - PADDING_DP - WidgetTextFit.SAFETY_DP
        return tallEnough && wideEnough
    }

    /** Reicht die Höhe für [sideLines] Nebenzeilen unter Leitsatz und Stand? Unbekannte Höhe (0): ja. */
    fun sideLinesFit(heightDp: Int, headlineLines: Int, sideLines: Int): Boolean {
        val needed = FULL_MIN_HEIGHT_DP + sideLines.coerceAtLeast(1) * FEAR_GREED_LINE_DP +
            if (headlineLines > 1) HEADLINE_SECOND_LINE_DP else 0
        return heightDp <= 0 || heightDp >= needed
    }

    /**
     * Nebenzeile(n) in dieser Reihenfolge versuchen (wie iOS `PulseSideLayout.candidates`): beides
     * in einer Zeile, sonst Fear & Greed und Marktbreite untereinander (nur mit Höhe für zwei
     * Zeilen, [twoLines]), sonst nur Fear & Greed, sonst nur die Marktbreite. So verschwindet die
     * Marktbreite nicht mehr, wenn das Widget hoch genug ist.
     */
    fun sideCandidates(hasFearGreed: Boolean, hasBreadth: Boolean, twoLines: Boolean): List<PulseSideLayout> = when {
        hasFearGreed && hasBreadth -> listOfNotNull(
            PulseSideLayout.ONE_LINE,
            PulseSideLayout.TWO_LINES.takeIf { twoLines },
            PulseSideLayout.FEAR_GREED,
            PulseSideLayout.BREADTH,
        )
        hasFearGreed -> listOf(PulseSideLayout.FEAR_GREED)
        hasBreadth -> listOf(PulseSideLayout.BREADTH)
        else -> emptyList()
    }

    /** Erste Anordnung aus [sideCandidates], die ganz passt ([fits]); null = keine Nebenzeile. */
    fun chooseSide(
        hasFearGreed: Boolean,
        hasBreadth: Boolean,
        twoLines: Boolean,
        fits: (PulseSideLayout) -> Boolean,
    ): PulseSideLayout? = sideCandidates(hasFearGreed, hasBreadth, twoLines).firstOrNull(fits)

    /**
     * Anordnung der Nebenzeile(n) aus gemessenen Textbreiten (dp, 12 sp; null = Teil fehlt):
     * [bothWidthDp] «● Fear & Greed 72 · Gier · Top 30 · 24h ↗ 22 ↘ 8», [fearGreedWidthDp] und
     * [breadthWidthDp] je allein. Zwei Zeilen nur, wenn die Höhe sie trägt ([sideLinesFit]) und
     * jeder Teil ganz in seine Zeile passt — sonst wie bisher nur ein Teil. Nie abgeschnitten.
     * [breadthLineWidthDp]: die Marktbreite als eigene zweite Zeile (dort mit Punkt «● Top 30 …», also
     * breiter); allein in der ersten Zeile steht sie ohne Punkt und wird mit [breadthWidthDp] gemessen.
     */
    fun sideLayout(
        widthDp: Int,
        heightDp: Int,
        headlineLines: Int,
        fearGreedWidthDp: Float?,
        breadthWidthDp: Float?,
        bothWidthDp: Float?,
        breadthLineWidthDp: Float? = breadthWidthDp,
    ): PulseSideLayout? = chooseSide(
        hasFearGreed = fearGreedWidthDp != null,
        hasBreadth = breadthWidthDp != null,
        twoLines = sideLinesFit(heightDp, headlineLines, 2),
    ) { layout ->
        fun fits(width: Float?, lines: Int) =
            width != null && showsFearGreed(widthDp, heightDp, headlineLines, width, lines)
        when (layout) {
            PulseSideLayout.ONE_LINE -> fits(bothWidthDp, 1)
            PulseSideLayout.TWO_LINES -> fits(fearGreedWidthDp, 2) && fits(breadthLineWidthDp, 2)
            PulseSideLayout.FEAR_GREED -> fits(fearGreedWidthDp, 1)
            PulseSideLayout.BREADTH -> fits(breadthWidthDp, 1)
        }
    }

    /** Abstand zwischen «24h» (grau) und dem ersten Coin (dp, widget_pulse.xml). */
    const val PERIOD_GAP_DP = 4f

    /** Zeile mit «24h» vor den Coins: Breite des Zeitraums samt Abstand; ohne Text 0. */
    fun periodLeadingDp(periodTextWidthDp: Float?): Float =
        periodTextWidthDp?.takeIf { it > 0f }?.let { it + PERIOD_GAP_DP } ?: 0f

    /** Für den Screenreader wie angezeigt gerundet; flach = 0. */
    fun spokenChange(change: Double): Double = if (isFlat(change)) 0.0 else change
}
