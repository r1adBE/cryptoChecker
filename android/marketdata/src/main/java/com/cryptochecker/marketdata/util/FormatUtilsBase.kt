package com.cryptochecker.marketdata.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

object FormatUtilsBase {

    // ── Zahlen ───────────────────────────────────────────────────────────────────

    /** Die zwei Zahlenformate einer Sprache. DecimalFormat ist nicht threadsicher: Zugriff nur über [render]. */
    private class Patterns(val locale: Locale) {
        private val symbols = DecimalFormatSymbols.getInstance(locale)
        val cents = DecimalFormat("#,###.00", symbols)
        val fourDigits = DecimalFormat("@###", symbols)
    }

    private fun formatLocale(): Locale = Locale.getDefault(Locale.Category.FORMAT)

    @Volatile
    private var cached = Patterns(formatLocale())

    /**
     * Formate der aktuellen Sprache. Wechselt die App die Sprache (`Locale.setDefault`), entstehen sie beim
     * nächsten Zugriff neu — ohne Neustart und auch ohne [updateLocale].
     */
    private val patterns: Patterns
        get() {
            val wanted = formatLocale()
            return cached.takeIf { it.locale == wanted } ?: Patterns(wanted).also { cached = it }
        }

    /** Formate gleich jetzt neu anlegen (etwa nach einem Sprachwechsel), statt erst beim nächsten Zugriff. */
    fun updateLocale() {
        cached = Patterns(formatLocale())
    }

    /**
     * Kurs für die Anzeige: unter 10 höchstens vier signifikante Stellen, ab 10 immer zwei Nachkommastellen
     * («83'142.00») — so sind die Zahlen in der Merkliste gleich aufgebaut, auch bei grossen Kursen.
     */
    fun formatDouble(value: Double): String =
        render(if (value < 10) patterns.fourDigits else patterns.cents, value)

    fun formatDoubleWithFourMax(value: Double): String = render(patterns.fourDigits, value)

    private fun render(pattern: DecimalFormat, value: Double): String = synchronized(pattern) {
        try {
            pattern.format(value)
        } catch (e: Exception) {
            // Eine Anzeige darf nie abstürzen: lieber die rohe Zahl als gar keine (Spur im Log für die Fehlersuche)
            e.printStackTrace()
            value.toString()
        }
    }

    // ── Datum und Zeit ───────────────────────────────────────────────────────────

    /** Heute nur die Uhrzeit, sonst nur das Datum — im Format des Geräts. */
    @JvmStatic
    fun formatSameDayTimeOrDate(context: Context?, time: Long): String {
        val format = if (DateUtils.isToday(time)) DateFormat.getTimeFormat(context) else DateFormat.getDateFormat(context)
        return format.format(Date(time))
    }

    /** «Uhrzeit, Datum» im Format des Geräts. */
    fun formatDateTime(context: Context?, time: Long): String {
        val moment = Date(time)
        val clock = DateFormat.getTimeFormat(context).format(moment)
        val day = DateFormat.getDateFormat(context).format(moment)
        return "$clock, $day"
    }
}
