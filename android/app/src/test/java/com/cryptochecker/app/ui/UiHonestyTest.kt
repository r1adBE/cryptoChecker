package com.cryptochecker.app.ui

import com.cryptochecker.app.domain.watch.CompareMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kleine Ehrlichkeits-Regeln der Oberfläche (Verhalten statt Quelltext). Vergleich: die feste Spalte des
 * gewählten Zeitraums entfällt (nie zweimal «7T» mit zwei Werten). Diagnose/«Entwickler»: `AboutExtrasTest`; Kurs-Overlay
 * nach dem Prozessstart: Regel `app-start` (source_rules.py). Wie iOS `UiHonestyTests`.
 */
class UiHonestyTest {

    @Test
    fun compareNeverShowsTheRangeTwice() {
        val shown = CompareMath.withoutRepeatedRange(listOf("7D" to 1.0, "24h" to 2.0, "7D" to 3.0, "30D" to 4.0))
        assertEquals(listOf("7D", "24h", "30D"), shown.map { it.first })
        assertEquals(1.0, shown.first().second, 0.0)
        assertEquals(emptyList<Pair<String, Int>>(), CompareMath.withoutRepeatedRange(emptyList<Pair<String, Int>>()))
    }
}
