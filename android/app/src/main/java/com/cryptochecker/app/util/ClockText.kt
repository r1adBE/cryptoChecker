package com.cryptochecker.app.util

import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Uhrzeiten nach der 12/24-h-Einstellung des Systems (Einstellungen › Datum und Uhrzeit), nicht nur nach der Sprache:
 * `java.text.DateFormat` kennt diese Einstellung nicht und schrieb in Englisch immer «2:05:09 PM», auch wenn das
 * Gerät auf 24 h steht — der Chart (`android.text.format.DateFormat.is24HourFormat`) zeigte dagegen «14:05».
 * Gilt für [PriceFormat.time], [PriceFormat.shortTime] und [MomentText]. Unter iOS folgt `DateFormatter`
 * der Einstellung von sich aus.
 *
 * Die Musterumstellung ([pattern]) ist eine reine Funktion (JVM-Tests); die Systemeinstellung kommt über [install].
 */
object ClockText {

    /** Liest die Systemeinstellung; null bis [install] (z. B. in JVM-Tests) → Muster der Sprache unverändert. */
    @Volatile
    private var source: (() -> Boolean)? = null

    /** Einmal beim Start der App: Einstellung über den App-Kontext lesen (bei jedem Aufruf neu, sie kann sich ändern). */
    fun install(context: Context) {
        val app = context.applicationContext
        source = { android.text.format.DateFormat.is24HourFormat(app) }
    }

    /** 24-h-Einstellung des Systems; null = unbekannt (nicht installiert oder nicht lesbar). */
    fun systemIs24Hour(): Boolean? = source?.let { read -> runCatching { read() }.getOrNull() }

    /**
     * Zeitformat ([withSeconds]: MEDIUM, sonst SHORT) bzw. mit [dateStyle] Datum und Uhrzeit für [locale], Stunden nach
     * [is24] (null = wie die Sprache).
     */
    fun format(locale: Locale, withSeconds: Boolean, is24: Boolean?, dateStyle: Int? = null): DateFormat {
        val timeStyle = if (withSeconds) DateFormat.MEDIUM else DateFormat.SHORT
        val format = if (dateStyle == null) DateFormat.getTimeInstance(timeStyle, locale)
        else DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
        if (is24 != null && format is SimpleDateFormat) {
            val adjusted = pattern(format.toPattern(), is24, locale)
            if (adjusted != format.toPattern()) runCatching { format.applyPattern(adjusted) }
        }
        return format
    }

    /** Sprachen, in denen die Tageszeit («vorm.»/«AM») vor der Stunde steht (CLDR zh, ja, ko: «下午2:05»). */
    private val PERIOD_FIRST = setOf("zh", "ja", "ko")

    /** Abstände um die Tageszeit, die mit ihr wegfallen (Leerzeichen, geschützt, schmal geschützt). */
    private val SPACES = setOf(' ', ' ', ' ')

    /**
     * Muster [localePattern] (`SimpleDateFormat`) auf 24 h («HH», ohne Tageszeit «a») bzw. 12 h («h» mit «a») umstellen;
     * schon passend: unverändert. Text in Hochkommas («'h'» im Muster fr_CA) bleibt, wie er ist.
     */
    fun pattern(localePattern: String, is24: Boolean, locale: Locale): String {
        val parts = split(localePattern)
        val letters = parts.filter { !it.quoted }.joinToString("") { it.text }
        val has12 = letters.any { it == 'h' || it == 'K' }
        val has24 = letters.any { it == 'H' || it == 'k' }
        if (is24 && !has12) return localePattern
        if (!is24 && (!has24 || has12)) return localePattern
        return if (is24) to24(parts) else to12(parts, locale.language in PERIOD_FIRST, locale.language == "ko")
    }

    private data class Part(val text: String, val quoted: Boolean)

    /** In Abschnitte ausserhalb und innerhalb von Hochkommas zerlegen («''» = Hochkomma bleibt im Text). */
    private fun split(pattern: String): List<Part> {
        val parts = ArrayList<Part>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                if (current.isNotEmpty()) parts += Part(current.toString(), quoted)
                current.clear()
                // «''» ausserhalb: ein Hochkomma als Text
                if (!quoted && i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    parts += Part("''", quoted = true)
                    i += 2
                    continue
                }
                current.append(c)
                if (quoted) {
                    parts += Part(current.toString(), quoted = true)
                    current.clear()
                }
                quoted = !quoted
                i++
                continue
            }
            current.append(c)
            i++
        }
        if (current.isNotEmpty()) parts += Part(current.toString(), quoted)
        return parts
    }

    private fun to24(parts: List<Part>): String = parts.joinToString("") { part ->
        if (part.quoted) part.text
        else {
            // Tageszeit samt anschliessendem bzw. vorangehendem Abstand weg, Stunde zweistellig «HH» (wie Android «Hms»)
            var t = part.text.replace(Regex("[\\u0020\\u00A0\\u202F]*a+[\\u0020\\u00A0\\u202F]*")) { m ->
                val before = m.range.first > 0 && m.value.first() in SPACES
                val after = m.range.last < part.text.length - 1 && m.value.last() in SPACES
                if (before && after) " " else ""
            }
            t = t.replace(Regex("[hK]+"), "HH")
            t
        }
    }

    private fun to12(parts: List<Part>, periodFirst: Boolean, spaceAfterPeriod: Boolean): String {
        // Tageszeit vor die erste Stunde (zh/ja/ko) bzw. hinter die letzte Zeitangabe (Stunde, Minute, Sekunde)
        val lastTime = parts.indexOfLast { !it.quoted && it.text.any { c -> c in "Hkms" } }
        var hourSeen = false
        return parts.mapIndexed { index, part ->
            if (part.quoted) return@mapIndexed part.text
            var t = part.text.replace(Regex("[Hk]+")) {
                val prefix = if (periodFirst && !hourSeen) (if (spaceAfterPeriod) "a " else "a") else ""
                hourSeen = true
                prefix + "h"
            }
            if (!periodFirst && index == lastTime) {
                val end = t.indexOfLast { it in "hms" } + 1
                t = t.substring(0, end) + " a" + t.substring(end)
            }
            t
        }.joinToString("")
    }
}
