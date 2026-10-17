package com.cryptochecker.app.ui.components

/**
 * Lage des Rollbalkens einer Liste: Jede Zeile bekommt denselben Anteil der Spur; der Balken deckt
 * die sichtbaren Zeilen ab. Rein rechnerisch (ohne Compose), damit testbar.
 */
internal object ScrollThumb {

    /** Oberkante und Höhe des Balkens in Pixeln. */
    data class Span(val top: Float, val height: Float)

    /**
     * [firstVisible]: Index der obersten sichtbaren Zeile, [visibleCount]: Anzahl sichtbarer Zeilen,
     * [totalCount]: alle Zeilen, [trackHeight]: Höhe der Liste. null = nichts zu zeichnen
     * (leer, nichts sichtbar oder keine Höhe).
     */
    fun span(firstVisible: Int?, visibleCount: Int, totalCount: Int, trackHeight: Float): Span? {
        if (firstVisible == null || totalCount <= 0 || visibleCount <= 0 || trackHeight <= 0f) return null
        val perRow = trackHeight / totalCount
        val top = (firstVisible.coerceIn(0, totalCount - 1) * perRow)
        val height = (visibleCount.coerceAtMost(totalCount) * perRow).coerceAtMost(trackHeight - top)
        return Span(top, height)
    }
}
