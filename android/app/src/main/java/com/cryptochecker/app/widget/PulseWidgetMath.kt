package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.PulseSummary
import java.util.Locale
import kotlin.math.abs

/** Ein Coin-Chip im Widget «Was gerade auffällt», z. B. «BTC ▲ +2.8%». */
data class PulseWidgetCoin(val symbol: String, val name: String, val change: Double)

/**
 * Reine Regeln des Widgets «Was gerade auffällt» (ohne Android, getestet in
 * PulseWidgetMathTest). Auswertung und Leitsatz kommen unverändert aus
 * [com.cryptochecker.app.domain.market.CryptoPulse] — wie die Karte im Markt-Tab.
 */
object PulseWidgetMath {

    /** Rand links + rechts des Widgets (dp, widget_pulse.xml). */
    const val PADDING_DP = 2 * 12f

    /** Abstand zwischen den Chips und Innenrand eines Chips links + rechts (dp). */
    const val CHIP_GAP_DP = 6f
    const val CHIP_PADDING_DP = 14f

    /** Logo und Abstand vor dem Titel (dp). */
    const val LOGO_DP = 16f + 6f

    /** Zeichen ▲/▼ vor der Schlagzeile samt Abstand (dp, ungefähr; 13 sp). */
    const val GLYPH_DP = 16f

    /** Schriftgrössen des Titels: normal, eng; passt keine, nur das Logo. */
    val TITLE_SIZES_SP = listOf(13f, 11f)

    /** Schlagzeile einzeilig in diesen Grössen; passt keine, zweizeilig in der kleinsten. */
    val HEADLINE_SIZES_SP = listOf(18f, 16f, 14f)

    /** Praktisch unverändert (Chip «0.0%», grau, ohne Pfeil) — wie die Karte. */
    const val FLAT_PERCENT = 0.05

    /** +1 breit steigend, −1 breit fallend, 0 gemischt oder ruhig (dann kein Zeichen). */
    fun direction(summary: PulseSummary): Int = when (summary) {
        PulseSummary.BROAD_UP, PulseSummary.BROAD_UP_VOLUME -> 1
        PulseSummary.BROAD_DOWN, PulseSummary.BROAD_DOWN_VOLUME -> -1
        PulseSummary.MIXED, PulseSummary.CALM -> 0
    }

    /** «▲» / «▼» vor der Schlagzeile; null ohne Richtung. */
    fun glyph(summary: PulseSummary): String? = when (direction(summary)) {
        1 -> "▲"
        -1 -> "▼"
        else -> null
    }

    /**
     * Wie viele Chips (BTC, ETH, SOL in dieser Reihenfolge) ganz in die Breite passen —
     * nie ein abgeschnittener Chip. Mindestens BTC; unbekannte Breite (0): nur BTC.
     * @param chipTextWidthsDp gemessene Textbreite jedes Chips (ohne Innenrand)
     */
    fun coinCount(widthDp: Int, chipTextWidthsDp: List<Float>): Int {
        if (widthDp <= 0 || chipTextWidthsDp.isEmpty()) return 1
        val available = widthDp - PADDING_DP - WidgetTextFit.SAFETY_DP
        var used = 0f
        var count = 0
        for (text in chipTextWidthsDp.take(3)) {
            val need = text + CHIP_PADDING_DP + if (count > 0) CHIP_GAP_DP else 0f
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
     * «▲ +2.8%», «▼ −1.4%» oder «0.0%» (unter 0.05 %), eine Nachkommastelle —
     * gleiches Format wie die Coin-Pillen der Karte. Pfeil folgt dem Vorzeichen,
     * nie dem Farbtausch.
     */
    fun changeText(change: Double, locale: Locale = Locale.getDefault()): String {
        if (isFlat(change)) return String.format(locale, "%.1f", 0.0) + "%"
        val arrow = if (change > 0) "▲" else "▼"
        val sign = if (change > 0) "+" else "−"
        return arrow + " " + sign + String.format(locale, "%.1f", abs(change)) + "%"
    }

    /** Chip-Text «BTC ▲ +2.8%». */
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

    /** Höhe der Zeile «Fear & Greed 72 · Gier» samt Abstand (dp, 11 sp). */
    const val FEAR_GREED_LINE_DP = 17

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
     * noch eine Zeile Höhe ([FULL_MIN_HEIGHT_DP] + [FEAR_GREED_LINE_DP], zweizeilige Schlagzeile
     * mehr), und der Text passt ganz in die Breite. Unbekannte Grösse (0): ja. Kleine Widgets
     * bleiben unverändert.
     */
    fun showsFearGreed(widthDp: Int, heightDp: Int, headlineLines: Int, textWidthDp: Float): Boolean {
        val needed = FULL_MIN_HEIGHT_DP + FEAR_GREED_LINE_DP + if (headlineLines > 1) HEADLINE_SECOND_LINE_DP else 0
        val tallEnough = heightDp <= 0 || heightDp >= needed
        val wideEnough = widthDp <= 0 || textWidthDp <= widthDp - PADDING_DP - WidgetTextFit.SAFETY_DP
        return tallEnough && wideEnough
    }

    /** Für den Screenreader wie angezeigt gerundet; flach = 0. */
    fun spokenChange(change: Double): Double = if (isFlat(change)) 0.0 else change
}
