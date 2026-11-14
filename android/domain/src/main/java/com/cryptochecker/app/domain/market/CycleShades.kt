package com.cryptochecker.app.domain.market

/**
 * Grautöne der früheren Zyklen im Zyklus-Chart: gleichmässig von [OLDEST] (älteste, am hellsten) bis [NEWEST]
 * (jüngster früherer Zyklus) verteilt, damit auch nach dem Halving 2028 (drei frühere Zyklen) jeder Zyklus seinen
 * eigenen Ton hat. Auch [OLDEST] erreicht als Grafik mindestens 3:1 (siehe `CycleChart`). Gleich auf iOS
 * (`CycleShades.olderAlpha`).
 */
object CycleShades {
    const val OLDEST = 0.65f
    const val NEWEST = 0.85f

    /** Deckkraft von onSurfaceVariant für den früheren Zyklus [index] (0 = ältester) von [olderCount]. */
    fun olderAlpha(index: Int, olderCount: Int): Float {
        if (olderCount <= 1) return NEWEST
        val i = index.coerceIn(0, olderCount - 1)
        return OLDEST + (NEWEST - OLDEST) * i / (olderCount - 1)
    }
}
