package com.cryptochecker.marketdata.util

/**
 * Rechnet die 24-h-Angaben der Börsen in Prozent um (1.5 = +1,5 %).
 *
 * Nur für GLEITENDE 24-h-Werte verwenden. Tageswerte seit Mitternacht
 * (Kraken „o“, Upbit/Bithumb „signed_change_rate“ …) bleiben weg; dann rechnet
 * die App mit Stundenkerzen. Welche Börse welches Feld liefert, steht in
 * DEVELOPMENT.md im Abschnitt «24 h change».
 *
 * Fehlt ein Wert oder ist er unbrauchbar (NaN, ∞, Eröffnung ≤ 0), ist das
 * Ergebnis null. Als Eingabe passt direkt `JSONObject.optDouble(name)`:
 * ein fehlendes Feld ergibt dort NaN.
 */
object Change24h {

    /** Wert ist schon in Prozent (z. B. Binance „priceChangePercent“). */
    fun percent(value: Double): Double? = value.takeIf { it.isFinite() }

    /** Wert ist ein Bruchteil, 0.015 = +1,5 % (z. B. Bybit „price24hPcnt“). */
    fun fraction(value: Double): Double? = if (value.isFinite()) value * 100.0 else null

    /** Aus dem Kurs von vor 24 Stunden (z. B. OKX „open24h“). */
    fun fromOpen(last: Double, open: Double): Double? {
        if (!last.isFinite() || !open.isFinite() || last <= 0.0 || open <= 0.0) return null
        return (last - open) / open * 100.0
    }

    /** Aus der absoluten Veränderung in 24 Stunden: Eröffnung = letzter Kurs − Veränderung. */
    fun fromAbsolute(last: Double, change: Double): Double? =
        if (change.isFinite()) fromOpen(last, last - change) else null
}
