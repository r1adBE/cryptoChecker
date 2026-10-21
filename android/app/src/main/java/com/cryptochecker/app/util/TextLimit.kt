package com.cryptochecker.app.util

import java.text.BreakIterator
import java.util.Locale

/**
 * Längengrenzen in Zeichen, wie sie der Mensch sieht (Graphem-Cluster) — wie Swift `String.count`/`prefix` auf iOS.
 * `String.take`/`length` zählen UTF-16-Einheiten: ein Emoji zählte doppelt, eine Familie 👨‍👩‍👧‍👦 elfmal, und das
 * Kürzen konnte ein Surrogatpaar oder eine ZWJ-Folge zerreissen. Seit Build 98 (Parität: `text_limit.json`).
 *
 * java.text.BreakIterator: auf Android über ICU, in JVM-Tests ab JDK 20 mit erweiterten Graphem-Clustern.
 */
object TextLimit {

    /** Anzahl sichtbarer Zeichen. */
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val it = iterator(text)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /** Höchstens [max] sichtbare Zeichen von vorn; nie mitten in einem Zeichen gekürzt. */
    fun takeGraphemes(text: String, max: Int): String {
        if (max <= 0) return ""
        // Weniger UTF-16-Einheiten als die Grenze: auch weniger Zeichen, nichts zu zählen
        if (text.length <= max) return text
        val it = iterator(text)
        var end = 0
        repeat(max) {
            val next = it.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }

    private fun iterator(text: String): BreakIterator =
        BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
}
