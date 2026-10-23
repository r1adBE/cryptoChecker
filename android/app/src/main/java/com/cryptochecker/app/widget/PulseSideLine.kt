package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.PulseBreadth
import com.cryptochecker.app.ui.theme.MarketScalePalette
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat

/** Nebenzeile(n) des Widgets «Was gerade auffällt» mit Farben (wie iOS `PulseSideLine`); gezeichnet in [PulseWidgetRenderer]. */
internal object PulseSideLine {

        /** Stufenpunkt vor Fear & Greed (wie iOS `PulseSideLine.dot`). */
        const val FNG_DOT = "\u25CF"

    /**
     * Nebenzeile mit Farben wie in der App: die Fear-&-Greed-Zahl in der Textfarbe (wie
     * die Karte im Markt-Tab), davor der Stufenpunkt «●» in der Skala aus den Kursfarben des Widgets
     * ([WidgetColors.scaleSteps], gleiche Mischung wie die App, ≥ 3:1 als Grafik) — so passt er zu den Pfeilen;
     * «↗ 21» in der Kursfarbe für steigend, «↘ 9» für fallend.
     * Der Rest bleibt grau (Textfarbe der Zeile). Nur Farben — die Breite (Textanpassung) ändert sich nicht.
     */
    fun sideLine(
        text: String,
        fearGreedValue: Int?,
        fearGreedText: String?,
        breadth: PulseBreadth?,
        colors: WidgetColors,
    ): CharSequence {
        val out = android.text.SpannableString(text)
        fun color(start: Int, length: Int, value: Int) {
            if (start >= 0) {
                out.setSpan(android.text.style.ForegroundColorSpan(value), start, start + length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (fearGreedValue != null && fearGreedText != null) {
            val from = text.indexOf(fearGreedText)
            val number = LocaleNumbers.integer(fearGreedValue)
            // Zahl in Textfarbe statt Stufenfarbe: die Stufenfarben erreichen als Text kein AA (wie die Karte)
            if (from >= 0) color(text.indexOf(number, from), number.length, colors.textColor)
            // Stufenpunkt am Anfang des Fear-&-Greed-Teils in der Farbe der Stufe
            if (from >= 0 && text.startsWith(FNG_DOT, from)) {
                color(from, FNG_DOT.length, colors.scaleSteps[MarketScalePalette.level(fearGreedValue)])
            }
        }
        if (breadth != null) {
            // Punkt vor «Top 30» (zweite Zeile): Kursfarbe der Mehrheit, gleich viele → grau (wie iOS)
            if (text.startsWith(FNG_DOT) && fearGreedValue == null) {
                val dot = when {
                    breadth.up > breadth.down -> colors.upColor
                    breadth.down > breadth.up -> colors.downColor
                    else -> colors.secondaryTextColor
                }
                color(0, FNG_DOT.length, dot)
            }
            val up = PriceFormat.ARROW_UP + " " + LocaleNumbers.integer(breadth.up)
            val down = PriceFormat.ARROW_DOWN + " " + LocaleNumbers.integer(breadth.down)
            color(text.indexOf(up), up.length, colors.upColor)
            color(text.indexOf(down), down.length, colors.downColor)
        }
        return out
    }
}
