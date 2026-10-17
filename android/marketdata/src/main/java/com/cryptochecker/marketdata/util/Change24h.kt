package com.cryptochecker.marketdata.util

/**
 * Bringt die 24-h-Angaben der Börsen auf Prozent (1.5 = +1,5 %).
 *
 * Gedacht nur für GLEITENDE 24 Stunden. Werte seit Mitternacht (Kraken «o», Upbit/Bithumb «signed_change_rate»
 * …) lässt man weg; die App rechnet dann selbst mit Stundenkerzen. Welche Börse welches Feld liefert: DEVELOPMENT.md,
 * Abschnitt «24 h change».
 *
 * Unbrauchbare Eingaben (NaN, ∞, Eröffnung ≤ 0) ergeben null. `JSONObject.optDouble(name)` passt direkt als
 * Eingabe, weil ein fehlendes Feld dort NaN ist.
 */
object Change24h {

    /** Abstand zweier Stundenkerzen in ms. */
    const val HOUR_MILLIS = 3_600_000L

    /** Die Börse meldet bereits Prozent (Binance «priceChangePercent»). */
    fun percent(value: Double): Double? = if (value.isFinite()) value else null

    /** Die Börse meldet einen Anteil, 0.015 = +1,5 % (Bybit «price24hPcnt»). */
    fun fraction(value: Double): Double? = percent(value)?.times(100.0)

    /** Aus dem Kurs von vor 24 Stunden (OKX «open24h»). */
    fun fromOpen(last: Double, open: Double): Double? {
        val valid = last.isFinite() && open.isFinite() && last > 0.0 && open > 0.0
        return if (valid) (last - open) / open * 100.0 else null
    }

    /** Aus der absoluten Veränderung: Eröffnung = letzter Kurs − Veränderung. */
    fun fromAbsolute(last: Double, change: Double): Double? {
        if (!change.isFinite()) return null
        return fromOpen(last, last - change)
    }

    /**
     * Prüft, ob die jüngsten [hours] Stundenkerzen (Startzeiten in ms, Reihenfolge egal, Doppelte zählen einmal)
     * lückenlos je eine Stunde auseinanderliegen. Ergebnis: Start der ältesten dieser Kerzen — ihre Eröffnung ist
     * etwa der Kurs vor 24 h. null bei zu wenig Kerzen oder einer Lücke; dann gibt es keine Veränderung (WOO X Spot).
     */
    fun hourlyWindowStart(startTimes: List<Long>, hours: Int = 24): Long? {
        if (hours <= 0) return null
        val window = startTimes.toSortedSet(reverseOrder()).take(hours)
        if (window.size < hours) return null
        val gapless = window.zipWithNext().all { (newer, older) -> newer - older == HOUR_MILLIS }
        return if (gapless) window.last() else null
    }
}
