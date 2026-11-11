package com.cryptochecker.app.domain.util
import java.text.BreakIterator
import java.util.Locale

/**
 * Längengrenzen in Zeichen, wie sie der Mensch sieht (Graphem-Cluster) — wie Swift `String.count`/`prefix` auf iOS.
 * `String.take`/`length` zählen UTF-16-Einheiten: ein Emoji zählte doppelt, eine Familie 👨‍👩‍👧‍👦 elfmal, und das
 * Kürzen konnte ein Surrogatpaar oder eine ZWJ-Folge zerreissen. Parität: `text_limit.json`.
 *
 * java.text.BreakIterator: auf Android über ICU, in JVM-Tests ab JDK 20 mit erweiterten Graphem-Clustern.
 */
object TextLimit {

    /** Anzahl sichtbarer Zeichen. */
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val breaks = iterator(text)
        var count = 0
        while (breaks.next() != BreakIterator.DONE) count++
        return count
    }

    /** Höchstens [max] sichtbare Zeichen von vorn; nie mitten in einem Zeichen gekürzt. */
    fun takeGraphemes(text: String, max: Int): String {
        if (max <= 0) return ""
        // Weniger UTF-16-Einheiten als die Grenze: auch weniger Zeichen, nichts zu zählen
        if (text.length <= max) return text
        val breaks = iterator(text)
        var end = 0
        repeat(max) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }

    /**
     * Freitext säubern: trimmen, auf [max] sichtbare Zeichen kürzen und nochmals trimmen (ein Schnitt kann
     * auf ein Leerzeichen fallen); leer = null. Für Notizen, Gruppen- und Börsennamen — aus der Eingabe wie
     * aus einer Sicherung gleich wie iOS `TextLimit.clean`.
     */
    fun clean(raw: String?, max: Int): String? =
        raw?.trim()?.let { takeGraphemes(it, max) }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Mehrzeiligen Freitext als eine Zeile (Notiz unter einem Paar der Merkliste): Zeilenumbrüche (LF, CR, U+2028,
     * U+2029) samt Leerzeichen und Tabs darum werden ein Leerzeichen, leere Zeilen fallen weg. Wie iOS
     * `TextLimit.singleLine`.
     */
    fun singleLine(text: String): String =
        text.split('\n', '\r', '\u2028', '\u2029').map { it.trim(' ', '\t') }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun iterator(text: String): BreakIterator =
        BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
}
