package com.cryptochecker.app.domain.portfolio.csvimport

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// CSV-Import ([CsvImportParser]): Zeitpunkte (ISO, Tag zuerst/Monat zuerst) in der Zeitzone der Datei lesen.

private val ISO = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]{1,9}))?)?)?\\s*(Z|UTC|[+-][0-9]{2}:?[0-9]{2})?$")
private val DAY_FIRST = Regex("^([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{4})(?:[ T,]+([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?)?$")

/**
 * Zeit aus der Datei → UTC-Millisekunden; null = unlesbar.
 * «2024-09-15T14:23:17Z», «…+02:00», «2024-09-15 14:23:17» (Ortszeit), «24-08-2022 07:00:15»
 * (Swissquote, Tag zuerst), «15.09.2024 14:23». Ohne Versatz gilt [zone]. [monthFirst]: «03/04/2024» bzw.
 * «03-04-2024» = 4. März ([CsvDateOrder.MONTH_FIRST]); mit «.» immer Tag zuerst.
 */
fun CsvImportParser.parseTime(text: String, zone: String = CsvImportParser.DEFAULT_ZONE, monthFirst: Boolean = false): Long? {
    val (local, offsetMinutes) = parseClock(text, monthFirst) ?: return null
    return if (offsetMinutes == null) toUtc(local, zone) else local - offsetMinutes * 60_000L
}

/** Kalenderzeit (als wäre sie UTC) einer Zeit OHNE Versatz; null = mit Versatz/«Z» oder unlesbar. */
internal fun CsvImportParser.localTime(text: String, monthFirst: Boolean = false): Long? =
    parseClock(text, monthFirst)?.takeIf { it.second == null }?.first

/** Kalenderzeit (als wäre sie UTC) und Versatz in Minuten (null = ohne Versatz); null = unlesbar. */
private fun CsvImportParser.parseClock(text: String, monthFirst: Boolean): Pair<Long, Int?>? {
    val t = text.trim()
    ISO.matchEntire(t)?.let { m ->
        val g = m.groupValues
        val frac = g[7].padEnd(3, '0').take(3)
        val local = localMillis(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), frac.toIntOr0())
            ?: return null
        val off = g[8]
        if (off.isEmpty()) return local to null
        if (off == "Z" || off == "UTC") return local to 0
        val digits = off.substring(1).replace(":", "")
        val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
        return local to (if (off[0] == '-') -minutes else minutes)
    }
    DAY_FIRST.matchEntire(t)?.let { m ->
        val g = m.groupValues
        // «.» ist immer Tag zuerst; «/» und «-» je nach Reihenfolge der Datei
        val swap = monthFirst && t[g[1].length] != '.'
        val day = if (swap) g[2].toInt() else g[1].toInt()
        val month = if (swap) g[1].toInt() else g[2].toInt()
        val local = localMillis(g[3].toInt(), month, day, g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), 0)
            ?: return null
        return local to null
    }
    return null
}

/**
 * Reihenfolge Tag/Monat der Datumsspalte ([texts]): eine Zahl > 12 vorne → [CsvDateOrder.DAY_FIRST], an zweiter
 * Stelle → [CsvDateOrder.MONTH_FIRST] (vorne gewinnt bei Widerspruch); nur mehrdeutige Daten («03/04/2024», Tag ≠
 * Monat) → null (Vorschau fragt). Nur Trenner «/» und «-»; ISO und «03.04.2024» sind eindeutig → DAY_FIRST.
 */
fun CsvImportParser.detectDateOrder(texts: List<String>): CsvDateOrder? {
    var dayFirst = false
    var monthFirst = false
    var ambiguous = false
    for (raw in texts) {
        val t = raw.trim()
        val g = DAY_FIRST.matchEntire(t)?.groupValues ?: continue
        if (t[g[1].length] == '.') continue
        val a = g[1].toInt()
        val b = g[2].toInt()
        when {
            a > 12 -> dayFirst = true
            b > 12 -> monthFirst = true
            a != b -> ambiguous = true
        }
    }
    return when {
        dayFirst -> CsvDateOrder.DAY_FIRST
        monthFirst -> CsvDateOrder.MONTH_FIRST
        ambiguous -> null
        else -> CsvDateOrder.DAY_FIRST
    }
}

/** Zeit mit Versatz oder «Z»/«UTC» (dann zählt keine Zeitzone). */
internal fun CsvImportParser.hasOffset(text: String): Boolean = ISO.matchEntire(text.trim())?.groupValues?.get(8)?.isNotEmpty() == true

private fun String.toIntOr0(): Int = if (isEmpty()) 0 else toInt()

/** Kalenderzeit als wäre sie UTC → ms; ungültiges Datum = null. */
internal fun CsvImportParser.localMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long? {
    if (mo !in 1..12 || d < 1 || d > daysInMonth(y, mo) || h > 23 || mi > 59 || s > 59) return null
    return ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * 60_000L + s * 1000L + ms
}

private fun CsvImportParser.daysInMonth(y: Int, m: Int): Int = when (m) {
    2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Tage seit 1970-01-01 (proleptisch gregorianisch, H. Hinnant). */
internal fun CsvImportParser.daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = (if (m <= 2) y0 - 1 else y0).toLong()
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val mp = ((m + 9) % 12).toLong()
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/**
 * Ortszeit in [zone] → UTC (gleich wie iOS): in der Herbst-Doppelstunde gilt der spätere Versatz (Winterzeit), in
 * der Frühlingslücke wird um die Länge der Lücke vorgerückt (02:30 → 03:30 Sommerzeit). Seit Build 104 über
 * [LocalDateTime.atZone] — vorher zwei Schritte über den Versatz, westlich von UTC in Lücke und Doppelstunde falsch.
 */
internal fun CsvImportParser.toUtc(local: Long, zone: String): Long {
    val clock = LocalDateTime.ofEpochSecond(Math.floorDiv(local, 1000L), Math.floorMod(local, 1000L).toInt() * 1_000_000, ZoneOffset.UTC)
    return clock.atZone(ZoneId.of(zone)).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
}
