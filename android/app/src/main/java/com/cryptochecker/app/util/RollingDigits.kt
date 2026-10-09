package com.cryptochecker.app.util

/**
 * Logik hinter den «rollenden Ziffern» (RollingNumberText), wie iOS
 * `contentTransition(.numericText)`: Der formatierte Text wird in Teile zerlegt —
 * jede Ziffer einzeln, alles dazwischen (Trenner, Währung, Wörter) als ein Stück,
 * damit Schriften mit Verbindungen (z. B. Devanagari, Thai) heil bleiben.
 * Die Teile werden von rechts gezählt: Einer bleiben Einer, auch wenn die Zahl
 * eine Stelle dazubekommt.
 */
object RollingDigits {

    /** Ein Teil des Textes; [key] = Platz von rechts (0 = letzter Teil). */
    data class Slot(val key: Int, val text: String)

    fun slots(text: String): List<Slot> {
        val parts = mutableListOf<String>()
        val run = StringBuilder()
        for (c in text) {
            if (c.isDigit()) {
                if (run.isNotEmpty()) {
                    parts += run.toString()
                    run.setLength(0)
                }
                parts += c.toString()
            } else {
                run.append(c)
            }
        }
        if (run.isNotEmpty()) parts += run.toString()
        return parts.mapIndexed { i, part -> Slot(parts.size - 1 - i, part) }
    }

    /** Nur eine geänderte Ziffer rollt; alles andere wechselt ohne Bewegung. */
    fun rolls(old: String, new: String): Boolean = old != new && old.isSingleDigit() && new.isSingleDigit()

    /** Plätze (von rechts), deren Ziffer von [old] zu [new] rollt. */
    fun rollingKeys(old: String, new: String): Set<Int> {
        val before = slots(old).associate { it.key to it.text }
        return slots(new).filter { slot -> before[slot.key]?.let { rolls(it, slot.text) } == true }
            .map { it.key }
            .toSet()
    }

    /**
     * Teile nebeneinander setzen geht nur ohne Schrift von rechts nach links
     * (Arabisch, Hebräisch): Dort ordnet erst der ganze Text die Reihenfolge — dann
     * ohne Rollen als ein Text zeigen.
     */
    fun canRoll(text: String): Boolean = text.none { c ->
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE -> true
            else -> false
        }
    }

    private fun String.isSingleDigit(): Boolean = length == 1 && this[0].isDigit()
}

/**
 * Richtung des Rollens: Ziffern gleiten nach oben, wenn der Wert gestiegen ist,
 * nach unten, wenn er gefallen ist. Ohne Vergleichswert bleibt die letzte Richtung.
 */
class RollingDirection(initial: Double?) {
    private var last: Double? = initial

    var up: Boolean = true
        private set

    /** Neuen Wert übernehmen; gibt die Richtung zurück (gleicher Wert = unverändert). */
    fun update(value: Double?): Boolean {
        val previous = last
        if (value != null && previous != null && value != previous) up = value > previous
        if (value != null) last = value
        return up
    }
}
