package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import java.math.BigDecimal

// CSV-Import ([CsvImportParser]): Zahlen mit Dezimal- und Tausendertrennzeichen der Börse lesen.

/**
 * Dezimalzeichen der Datei aus eindeutigen Zahlen: nur «.»-Zahlen (genau ein Punkt, nicht 3 Stellen
 * dahinter, oder «0»/«-0» davor — «0.125» ist nie Tausender) → '.'; nur solche mit «,» → ','; beides oder keine → null.
 */
fun CsvImportParser.decimalHint(cells: List<String>): Char? {
    var dot = false
    var comma = false
    for (raw in cells) {
        val t = stripGrouping(raw)
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        if (dots == 1 && commas == 0 && (t.length - t.indexOf('.') - 1 != 3 || zeroWhole(t, '.'))) dot = true
        if (commas == 1 && dots == 0 && (t.length - t.indexOf(',') - 1 != 3 || zeroWhole(t, ','))) comma = true
        if (dots >= 1 && commas >= 1) { if (t.lastIndexOf('.') > t.lastIndexOf(',')) dot = true else comma = true }
    }
    return when {
        dot && !comma -> '.'
        comma && !dot -> ','
        else -> null
    }
}

/** Ganzzahlteil vor dem einzigen [sep] ist «0» oder «-0» (nie eine Tausendergruppe). */
private fun CsvImportParser.zeroWhole(t: String, sep: Char): Boolean = t.substringBefore(sep).removePrefix("-") == "0"

/**
 * Erste mehrdeutige Zahl ([cells] ohne Gruppierung), wenn die Datei kein Dezimalzeichen verrät ([decimalHint] null):
 * genau ein «.» oder «,», genau 3 Ziffern dahinter, gültig als Tausender («42,000» = 42000 oder 42?). null = keine.
 */
fun CsvImportParser.ambiguousNumber(cells: List<String>): String? = cells.firstOrNull { raw ->
    val t = stripGrouping(raw)
    val dots = t.count { it == '.' }
    val commas = t.count { it == ',' }
    val sep = when {
        dots == 1 && commas == 0 -> '.'
        commas == 1 && dots == 0 -> ','
        else -> return@firstOrNull false
    }
    grouped(t, sep) && PLAIN.matches(t.replace(sep.toString(), ""))
}?.trim()

private fun CsvImportParser.stripGrouping(text: String): String = buildString {
    for (c in text.trim()) {
        when (c) {
            ' ', ' ', ' ', '\'', '’', 'ʼ', '+' -> {}
            '−' -> append('-')
            else -> append(c)
        }
    }
}

/**
 * Zahl aus der Datei → exakte Dezimalzahl; ungültig = null.
 * Beide Zeichen vorhanden: das letzte ist das Dezimalzeichen. Nur eines, mehrfach: Tausender.
 * Nur eines, einmal: Dezimalzeichen — ausser [hint] sagt das andere und die Gruppen passen (Tausender).
 * Tausender nur mit gültigen Gruppen ([grouped]): «0.001» bleibt 0.001, auch in einer Datei mit Dezimalkomma.
 * Wissenschaftliche Schreibweise, wie Excel kleine Beträge speichert ([scientific]): «1.234E-05», «1,234E-05».
 */
fun CsvImportParser.parseNumber(text: String, hint: Char? = null): BigDecimal? {
    val t = stripGrouping(text)
    if (t.isEmpty()) return null
    if (t.any { it == 'e' || it == 'E' }) return scientific(t)
    val dots = t.count { it == '.' }
    val commas = t.count { it == ',' }
    val plain = when {
        dots > 0 && commas > 0 -> {
            val dotLast = t.lastIndexOf('.') > t.lastIndexOf(',')
            val cut = if (dotLast) t.lastIndexOf('.') else t.lastIndexOf(',')
            val whole = t.substring(0, cut)
            val sep = if (dotLast) ',' else '.'
            if (!grouped(whole, sep)) return null
            whole.replace(sep.toString(), "") + "." + t.substring(cut + 1)
        }
        commas > 1 -> if (grouped(t, ',')) t.replace(",", "") else return null
        dots > 1 -> if (grouped(t, '.')) t.replace(".", "") else return null
        commas == 1 -> if (hint == '.' && grouped(t, ',')) t.replace(",", "") else t.replace(',', '.')
        dots == 1 -> if (hint == ',' && grouped(t, '.')) t.replace(".", "") else t
        else -> t
    }
    if (!PLAIN.matches(plain) || plain.length > 60) return null
    return runCatching { BigDecimal(plain) }.getOrNull()
}

/**
 * [sep] als Tausender gültig: erste Gruppe 1–3 Ziffern ohne führende 0 (eine einzelne «0» nie), alle weiteren
 * genau 3 Ziffern, mindestens eine Trennung. Spiegel: `grouped` in `CsvImportNumbers.swift`.
 */
private fun CsvImportParser.grouped(text: String, sep: Char): Boolean {
    val groups = text.removePrefix("-").split(sep)
    val first = groups.first()
    if (groups.size < 2 || first.length !in 1..3 || first[0] == '0' || !first.all { it in '0'..'9' }) return false
    return groups.drop(1).all { g -> g.length == 3 && g.all { it in '0'..'9' } }
}

private val PLAIN = Regex("^-?[0-9]+(\\.[0-9]+)?$")

/** Mantisse mit höchstens einem Dezimalzeichen («.» oder «,», nie Tausender), «E»/«e», Exponent mit 1–3 Ziffern. */
private val SCIENTIFIC = Regex("^(-?[0-9]+(?:[.,][0-9]+)?)[eE](-?[0-9]{1,3})$")

/**
 * «1.234E-05» → 0.00001234 (das «+» eines Exponenten entfernt schon [stripGrouping]). Mantisse wie [parseNumber]
 * höchstens 60 Zeichen; der Wert muss unter 10³⁰ liegen ([PortfolioDecimal.MAX_INTEGER_DIGITS]), sonst ungültig. Die
 * Nachkommastellen prüft wie bei einer gewöhnlichen Zahl erst das Speichern (gerundet,
 * [CsvImportMapping.unsavableReason]): «1.234E-38» als Preis gilt wie «0.00…01234». Höchstens [MAX_EXPONENT_SCALE]
 * Nachkommastellen (ohne Nullen am Ende), so weit reicht `Decimal` (iOS). Wie iOS.
 */
private fun scientific(t: String): BigDecimal? {
    val m = SCIENTIFIC.matchEntire(t) ?: return null
    val mantissa = m.groupValues[1].replace(',', '.')
    if (mantissa.length > 60) return null
    val value = runCatching { BigDecimal(mantissa).scaleByPowerOfTen(m.groupValues[2].toInt()) }.getOrNull() ?: return null
    val s = value.stripTrailingZeros()
    if (s.signum() == 0) return s
    return s.takeIf { it.precision() - it.scale() <= PortfolioDecimal.MAX_INTEGER_DIGITS && it.scale() <= MAX_EXPONENT_SCALE }
}

/** Höchstens so viele Nachkommastellen in wissenschaftlicher Schreibweise: kleinster Exponent von `Decimal` (iOS). */
private const val MAX_EXPONENT_SCALE = 128
