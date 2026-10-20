package com.cryptochecker.app.ui.navigation

/**
 * Tab-Beschriftung bei grosser Schrift nur verkleinern (wie iOS `minimumScaleFactor(0.8)`): Passt der
 * Name in der gewünschten Grösse nicht in die Tab-Breite (ein Wort breiter als der Tab oder mehr als zwei
 * Zeilen), wird die Schrift schrittweise kleiner, höchstens bis [MIN_FACTOR] und nie unter die Grösse bei
 * normaler Schrift (12 sp bleibt die Untergrenze). Bewusst ohne `TextAutoSize`/Silbentrennung: gemessen
 * wird mit dem `TextMeasurer` in der Komposition (Hauptthread), höchstens [STEPS] + 1 Messungen je Name.
 * Reines Kotlin (Pixelwerte), damit die Stufen ohne Compose prüfbar sind.
 */
internal object TabLabelFit {
    /** Kleinster Faktor gegenüber der gewünschten Grösse (wie iOS `minimumScaleFactor(0.8)`). */
    const val MIN_FACTOR = 0.8f

    /** Anzahl Verkleinerungsschritte zwischen gewünschter und kleinster Grösse. */
    const val STEPS = 4

    /**
     * Zu probierende Schriftgrössen (px), grösste zuerst: [targetPx], dann gleichmässig bis
     * max([targetPx] × [MIN_FACTOR], [floorPx]). Ist [targetPx] nicht grösser als [floorPx] (normale
     * Schrift), nur [targetPx] — dort ändert sich nichts.
     */
    fun candidates(targetPx: Float, floorPx: Float): List<Float> {
        val smallest = maxOf(targetPx * MIN_FACTOR, floorPx)
        if (smallest >= targetPx) return listOf(targetPx)
        val step = (targetPx - smallest) / STEPS
        return (0..STEPS).map { targetPx - step * it }
    }

    /** Erste Grösse aus [candidates], für die [fits] stimmt; sonst die kleinste (dann «…» wie bisher). */
    fun pick(candidates: List<Float>, fits: (Float) -> Boolean): Float =
        candidates.firstOrNull(fits) ?: candidates.last()
}
