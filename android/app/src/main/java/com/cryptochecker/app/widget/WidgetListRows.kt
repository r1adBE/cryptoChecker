package com.cryptochecker.app.widget

import kotlin.math.floor

/**
 * Zeilen eines Merklisten-Widgets mit fester Zeilenzahl bei grosser Schrift — gemeinsame Regel mit iOS
 * (`WidgetRows.rowsFor`, Paritäts-Fixture `widget_list_rows.json`). Das Android-Widget braucht sie nicht: Seine
 * Liste ist eine scrollbare ListView, deren Zeilen mit der Systemschrift (sp) wachsen; es fällt also nie etwas weg.
 */
object WidgetListRows {
    /** Höhe einer Zeile des Paarnamens bzw. Kurses (14) in der Grundgrösse. */
    const val PRIMARY_LINE_HEIGHT = 17.0

    /** Obergrenze des Faktors der Haupttexte (wie iOS `WidgetTextSize.maxPrimaryScale`). */
    const val MAX_PRIMARY_SCALE = 1.3

    /**
     * In der Grundgrösse (Faktor ≤ 1) genau [baseRows]; sonst so viele Zeilen, wie in [height] Platz haben, wenn
     * jede `height / baseRows + PRIMARY_LINE_HEIGHT × (Faktor − 1)` hoch ist (Faktor höchstens
     * [MAX_PRIMARY_SCALE]), mindestens 1.
     */
    fun rowsFor(height: Double, scale: Double, baseRows: Int): Int {
        if (baseRows <= 0) return 0
        val factor = if (scale.isNaN()) 1.0 else scale.coerceIn(1.0, MAX_PRIMARY_SCALE)
        if (height <= 0.0 || height.isInfinite() || height.isNaN() || factor <= 1.0) return baseRows
        val row = height / baseRows + PRIMARY_LINE_HEIGHT * (factor - 1.0)
        return floor(height / row + 1e-9).toInt().coerceIn(1, baseRows)
    }
}
