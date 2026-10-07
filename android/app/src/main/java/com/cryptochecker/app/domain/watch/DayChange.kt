package com.cryptochecker.app.domain.watch

import kotlin.math.abs

/**
 * 24-h-Bezug aus Stundenkerzen (24 × 1 h, wie der Mini-Chart): Eröffnung der
 * ersten Kerze und Schluss der letzten (läuft in der Regel noch).
 */
data class DayReference(val open: Double, val lastClose: Double) {
    companion object {
        /** null, wenn ein Wert fehlt, nicht endlich oder nicht positiv ist. */
        fun of(open: Double?, lastClose: Double?): DayReference? {
            if (open == null || lastClose == null) return null
            if (!open.isFinite() || !lastClose.isFinite() || open <= 0.0 || lastClose <= 0.0) return null
            return DayReference(open, lastClose)
        }
    }
}

/**
 * Veränderung über 24 Stunden für Prozent-Pille, Puls-Zeile und Widgets.
 *
 * Die Börsen-Ticker liefern keinen eigenen 24-h-Wert (das Ticker-Modell kennt nur
 * bid/ask/vol/high/low/last), deshalb kommt der Bezug aus Stundenkerzen:
 *  1. Kerzen des Paars selbst (USD-artige Quotes teilen sich die USDT-Reihe des
 *     Mini-Charts): aktueller Kurs gegen die Eröffnung vor 24 h.
 *  2. Fiat-Quote ohne eigene Kerzen (z. B. BTC/CHF): Verlauf der USDT-Reihe allein
 *     (letzter Schluss gegen Eröffnung) — Näherung, Wechselkursbewegung bleibt aussen vor.
 *  3. Sonst nichts (null → Pille «—»), nie die Veränderung seit der letzten Abfrage.
 */
object DayChange {

    /** Quotes, für die die USDT-Reihe des Mini-Charts gilt. */
    val USD_LIKE_QUOTES = setOf("USDT", "USD", "USDC", "FDUSD")

    /** Kerzen-Quote für ein Paar: USD-artige auf USDT (gleiche Reihe wie der Mini-Chart). */
    fun candleQuote(quote: String): String {
        val q = quote.trim().uppercase()
        return if (q in USD_LIKE_QUOTES) "USDT" else q
    }

    /**
     * Weicht der Kurs um mehr als diesen Anteil vom letzten Kerzenschluss ab, gehören
     * Kerzen und Kurs wohl nicht zum selben Coin (gleiches Kürzel, anderer Token).
     */
    const val MAX_PRICE_GAP = 0.25

    /** Aktueller Kurs gegen die 24-h-Eröffnung; null ohne Kurs oder bei unpassender Reihe. */
    fun fromPrice(price: Double?, reference: DayReference?): Double? {
        val ref = reference ?: return null
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (abs(p / ref.lastClose - 1.0) > MAX_PRICE_GAP) return null
        return (p - ref.open) / ref.open * 100.0
    }

    /** Nur der Verlauf der Reihe: letzter Schluss gegen Eröffnung. */
    fun fromSeries(reference: DayReference?): Double? {
        val ref = reference ?: return null
        return (ref.lastClose - ref.open) / ref.open * 100.0
    }

    /**
     * Auswahl in fester Reihenfolge (siehe Klassenbeschreibung).
     * @param pairReference Kerzen des Paars in seiner Quote (bzw. USDT bei USD-artigen Quotes)
     * @param usdtReference USDT-Reihe des Basis-Assets (Mini-Chart), nur für Fiat-Quotes
     * @param quoteIsFiat Quote ist eine Landeswährung (EUR, CHF, …)
     */
    fun select(
        price: Double?,
        pairReference: DayReference?,
        usdtReference: DayReference?,
        quoteIsFiat: Boolean,
    ): Double? {
        if (pairReference != null) return fromPrice(price, pairReference)
        if (quoteIsFiat) return fromSeries(usdtReference)
        return null
    }
}
