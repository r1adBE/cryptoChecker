package com.cryptochecker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Punkt für Punkt durch einen Chart ([ChartPointStep]): Start, Enden, leere Reihe. */
class ChartPointStepTest {

    @Test
    fun nextStartsAtFirstAndStopsAtLast() {
        assertEquals(0, ChartPointStep.next(null, 3))
        assertEquals(1, ChartPointStep.next(0, 3))
        assertEquals(2, ChartPointStep.next(2, 3))
    }

    @Test
    fun previousStartsAtLastAndStopsAtFirst() {
        assertEquals(2, ChartPointStep.previous(null, 3))
        assertEquals(1, ChartPointStep.previous(2, 3))
        assertEquals(0, ChartPointStep.previous(0, 3))
    }

    @Test
    fun emptyOrShrunkSeries() {
        assertNull(ChartPointStep.next(null, 0))
        assertNull(ChartPointStep.previous(4, 0))
        // Reihe kürzer geworden (neuer Zeitraum): bleibt innerhalb
        assertEquals(1, ChartPointStep.next(7, 2))
        assertEquals(1, ChartPointStep.previous(7, 2))
    }
}
