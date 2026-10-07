package com.cryptochecker.app.util

import com.cryptochecker.marketdata.util.FormatUtilsBase
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.abs

object PriceFormat {

    fun price(value: Double?): String =
        if (value == null || value <= 0.0) "—" else FormatUtilsBase.formatDouble(value)

    fun priceWithCurrency(value: Double?, quoteAsset: String): String =
        if (value == null || value <= 0.0) "—" else "${price(value)} $quoteAsset"

    /** Gehaltene Menge: Tausendertrennung, bis acht Nachkommastellen, ohne Nullen am Ende. */
    fun amount(value: Double): String = DecimalFormat("#,##0.########").format(value)

    /** Wert eines Bestands mit Währung, immer zwei Nachkommastellen. */
    fun valueWithCurrency(value: Double, quoteAsset: String): String =
        "${DecimalFormat("#,##0.00").format(value)} $quoteAsset"

    /** Menge für ein Eingabefeld: ohne Tausendertrennung, Punkt als Dezimalzeichen. */
    fun amountForInput(value: Double?): String =
        value?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

    /**
     * Freie Eingabe einer Menge: Komma oder Punkt, Leerzeichen und
     * Tausenderstriche werden ignoriert. Leer = 0 (kein Bestand),
     * ungültig oder negativ = null.
     */
    fun parseAmount(text: String): Double? {
        val cleaned = text.trim().replace(" ", "").replace("'", "").replace("’", "").replace(',', '.')
        if (cleaned.isEmpty()) return 0.0
        return cleaned.toDoubleOrNull()?.takeIf { it >= 0.0 && !it.isInfinite() }
    }

    /**
     * Pfeil zur Änderung wie in der Merkliste: «▲» steigend, «▼» fallend, leer bei
     * praktisch 0. Folgt immer dem Vorzeichen, nie dem Farbtausch.
     */
    fun changeArrow(value: Double?): String = when {
        value == null || abs(value) < 0.005 -> ""
        value > 0 -> "▲"
        else -> "▼"
    }

    fun changePercent(value: Double?): String? {
        if (value == null || abs(value) < 0.005) return null
        val sign = if (value > 0) "+" else "−"
        return "$sign%.2f%%".format(abs(value))
    }

    /** Uhrzeit mit Sekunden — bei Kursen zählt die Sekunde. */
    fun time(millis: Long): String =
        if (millis <= 0) "—"
        else DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

    /** Prozentwert für Benachrichtigungen: mit Vorzeichen, drei Nachkommastellen. */
    fun changePercentDetailed(value: Double): String {
        val sign = if (value >= 0) "+" else "-"
        return "$sign%.3f%%".format(abs(value))
    }

    /**
     * Abstand zu einem Zeitpunkt, kompakt: 45s, 12m, 3h, 7d.
     * Für „seit der letzten Meldung" in der Benachrichtigung.
     */
    fun age(sinceMillis: Long, now: Long = System.currentTimeMillis()): String? {
        if (sinceMillis <= 0) return null
        val elapsed = now - sinceMillis
        if (elapsed < 0) return null

        val seconds = elapsed / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** Dauer einer Aktualisierung, kurz gehalten für den Widget-Kopf. */
    fun duration(millis: Long): String = when {
        millis <= 0 -> ""
        millis < 1000 -> "$millis ms"
        else -> "%.1f s".format(millis / 1000.0)
    }

    /** Kurs so aufbereiten, dass die Sprachausgabe ihn sinnvoll vorliest. */
    fun spokenPrice(value: Double): String {
        val formatted = when {
            value >= 1000 -> "%.0f".format(value)
            value >= 1 -> "%.2f".format(value)
            else -> "%.6f".format(value).trimEnd('0').trimEnd('.')
        }
        return formatted.replace(",", ".")
    }
}
