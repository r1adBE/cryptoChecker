package com.cryptochecker.app.domain.starter

/**
 * Ruhiger «Erst-Moment» beim Hinzufügen (Start-Merkliste oder allererstes Paar
 * aus dem Hinzufügen-Tab): Zeilen blenden gestaffelt ein, Kurs und Mini-Chart
 * erscheinen sanft, kurz ein Häkchen, ein leichtes Haptik-Signal und ein Banner.
 * Reines Kotlin (testbar); Ablauf in der Merkliste.
 */
data class AddMoment(
    /** Eindeutig je Hinzufügen-Aktion. */
    val id: Long,
    val pairs: List<Added>,
) {
    /**
     * Ein neu angelegtes Paar, erkannt an Börse, Basis und Quote (so kann der Moment
     * schon vor dem Speichern bereitstehen und die Zeile gleich beim ersten Zeichnen
     * einblenden). [display] z. B. «BTC/USDT».
     */
    data class Added(val marketKey: String, val base: String, val quote: String, val display: String) {
        fun matches(marketKey: String, base: String, quote: String): Boolean =
            this.marketKey == marketKey && this.base.equals(base, ignoreCase = true) &&
                this.quote.equals(quote, ignoreCase = true)
    }

    /** Platz in der Staffelung; null = Zeile gehört nicht zu diesem Moment. */
    fun indexOf(marketKey: String, base: String, quote: String): Int? =
        pairs.indexOfFirst { it.matches(marketKey, base, quote) }.takeIf { it >= 0 }

    companion object {
        /** Versatz zwischen zwei Zeilen. */
        const val STAGGER_MILLIS = 90L

        /** Zeile blendet ein (Deckkraft + leicht von unten). */
        const val ENTER_MILLIS = 250

        /** Mini-Chart zeichnet sich von links nach rechts. */
        const val DRAW_MILLIS = 500

        /** Häkchen bleibt so lange sichtbar, dann blendet es aus. */
        const val CHECK_HOLD_MILLIS = 1_500L

        /** Ausblenden des Häkchens. */
        const val CHECK_FADE_MILLIS = 400

        fun staggerDelay(index: Int): Long = index.coerceAtLeast(0) * STAGGER_MILLIS

        /**
         * Bis dahin ist der ganze Ablauf auch der letzten Zeile vorbei
         * (Einblenden, Häkchen halten und ausblenden), mit etwas Luft.
         */
        fun totalMillis(count: Int): Long =
            staggerDelay(count - 1) + ENTER_MILLIS + CHECK_HOLD_MILLIS + CHECK_FADE_MILLIS + 400L

        /**
         * Text für «%1$s wird jetzt überwacht»: ein Paar mit Anzeige («BTC/USDT»),
         * mehrere als Basis-Liste («BTC, ETH, XRP, BNB, SOL»). Leer ohne Paare.
         */
        fun subject(pairs: List<Added>): String = when (pairs.size) {
            0 -> ""
            1 -> pairs.first().display
            else -> pairs.map { it.base }.distinct().joinToString(", ")
        }
    }
}
