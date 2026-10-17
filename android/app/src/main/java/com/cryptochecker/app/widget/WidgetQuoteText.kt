package com.cryptochecker.app.widget

import kotlin.math.max

/**
 * Währung hinter einem Kurs oder Betrag in den Widgets: kleiner und grau wie der Börsenname der
 * Merkliste («67’000.00 USDT», «−1’968.40 CHF · 24h»). Reine Regeln ohne Android (getestet in
 * WidgetQuoteTextTest; gespiegelt in `WidgetQuoteText`, iOS) — gezeichnet wird mit
 * [WidgetToolkit.quoteText], gemessen mit [WidgetToolkit.quoteTextWidthDp].
 */
object WidgetQuoteText {

    /** Kleinste Schriftgrösse der Währung (sp) — wie iOS `WidgetPriceText`. J-18: 11 statt 9 (lesbar). */
    const val MIN_QUOTE_SP = 11f

    /** Währung ≈ 42 % der Kursgrösse, mindestens [MIN_QUOTE_SP]. */
    private const val QUOTE_RATIO = 0.42f

    /**
     * Lage der Währung [quote] in [text]: das letzte Vorkommen, vor dem ein Leerzeichen steht und
     * hinter dem kein Buchstabe oder keine Ziffer folgt («1’968.40 CHF · 24h» → «CHF»). Leere
     * Währung, verborgener Betrag («•••») oder «—»: null (nichts zu verkleinern).
     */
    fun quoteRange(text: String, quote: String): IntRange? {
        if (quote.isBlank() || text.isEmpty()) return null
        var from = text.length
        while (from > 0) {
            val index = text.lastIndexOf(quote, from - 1)
            if (index < 0) return null
            val end = index + quote.length
            val spaceBefore = index > 0 && text[index - 1].isWhitespace()
            val freeAfter = end >= text.length || !text[end].isLetterOrDigit()
            // Davor eine Zahl (ohne Zahl ist es kein Betrag, z. B. nur «USDT»)
            if (spaceBefore && freeAfter && text.substring(0, index).any { it.isDigit() }) {
                return index until end
            }
            from = index
        }
        return null
    }

    /** Verhältnis Währung ÷ Kurs für die Kursgrösse [priceSp] (0–1). */
    fun scale(priceSp: Float): Float =
        if (priceSp <= 0f) 1f else (max(MIN_QUOTE_SP, priceSp * QUOTE_RATIO) / priceSp).coerceAtMost(1f)

    /**
     * Breite von [text] mit verkleinerter Währung: der Rest in [priceSp], die Währung in
     * [priceSp] × [scale]. [measure] misst einen Text in einer Schriftgrösse (dp oder px).
     */
    fun width(text: String, quote: String, priceSp: Float, measure: (String, Float) -> Float): Float {
        val range = quoteRange(text, quote) ?: return measure(text, priceSp)
        val rest = text.removeRange(range)
        return measure(rest, priceSp) + measure(text.substring(range), priceSp * scale(priceSp))
    }
}
