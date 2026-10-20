package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eigene Grenzen der Bitcoin-Kennzahlen; Swift: `IndicatorAlertRulesTests` (MarketsTests.swift). */
class IndicatorAlertRulesTest {

    @Test
    fun `stored integers become thresholds`() {
        assertEquals(2.4, IndicatorAlertRules.threshold(IndicatorAlertKind.MAYER_ABOVE, 240)!!, 1e-12)
        assertEquals(5.0, IndicatorAlertRules.threshold(IndicatorAlertKind.ATH_WITHIN, 5)!!, 1e-12)
        assertNull(IndicatorAlertRules.threshold(IndicatorAlertKind.MVRV_BELOW, 0))
        assertEquals(setOf(IndicatorAlertKind.MAYER_ABOVE), IndicatorAlertRules.active(mapOf(IndicatorAlertKind.MAYER_ABOVE to 240, IndicatorAlertKind.MVRV_ABOVE to 0)).keys)
    }

    @Test
    fun `notify once when crossing, rearm when back`() {
        val k = IndicatorAlertKind.MAYER_ABOVE
        val first = IndicatorAlertRules.decide(k, 2.5, 2.4, wasCrossed = false)
        assertTrue(first.notify); assertEquals(true, first.crossed)
        val again = IndicatorAlertRules.decide(k, 2.6, 2.4, wasCrossed = true)
        // Kein zweites Mal melden; Zustand erneut schreiben (samt aktueller Grenze)
        assertFalse(again.notify); assertEquals(true, again.crossed)
        val back = IndicatorAlertRules.decide(k, 2.3, 2.4, wasCrossed = true)
        assertFalse(back.notify); assertEquals(false, back.crossed)
        // Fehlender Wert: nichts ändern
        assertNull(IndicatorAlertRules.decide(k, null, 2.4, wasCrossed = true).crossed)
    }

    @Test
    fun `below and distance to the high`() {
        assertTrue(IndicatorAlertRules.crossed(IndicatorAlertKind.MVRV_BELOW, 0.8, 0.8))
        assertFalse(IndicatorAlertRules.crossed(IndicatorAlertKind.MVRV_BELOW, 0.81, 0.8))
        // Abstand 3 % ≤ Grenze 5 % → nahe am Hoch
        assertTrue(IndicatorAlertRules.crossed(IndicatorAlertKind.ATH_WITHIN, 3.0, 5.0))
    }

    @Test
    fun `new bound rearms a crossed state like fear and greed`() {
        // Gemeldet bei Grenze 2.40×; neue Grenze 2.00×, Wert liegt schon darüber → neu bewerten und melden
        assertFalse(IndicatorAlertRules.wasCrossed(storedCrossed = true, storedThreshold = 240, current = 200))
        val k = IndicatorAlertKind.MAYER_ABOVE
        assertTrue(IndicatorAlertRules.decide(k, 2.5, 2.0, wasCrossed = false).notify)
        // Gleiche Grenze: bleibt gemeldet (keine Doppelmeldung)
        assertTrue(IndicatorAlertRules.wasCrossed(storedCrossed = true, storedThreshold = 240, current = 240))
        // Stand vor dieser Regel (keine Grenze gemerkt): übernehmen
        assertTrue(IndicatorAlertRules.wasCrossed(storedCrossed = true, storedThreshold = null, current = 200))
        assertFalse(IndicatorAlertRules.wasCrossed(storedCrossed = false, storedThreshold = 240, current = 200))
    }

    @Test
    fun `old state without stored bound picks up the bound and a later new bound notifies`() {
        val k = IndicatorAlertKind.MAYER_ABOVE
        // Nach dem Update: gemerkt «jenseits», keine Grenze gespeichert → übernehmen, nicht doppelt melden …
        val wasCrossed = IndicatorAlertRules.wasCrossed(storedCrossed = true, storedThreshold = null, current = 240)
        val run = IndicatorAlertRules.decide(k, 2.6, 2.4, wasCrossed)
        assertFalse(run.notify)
        // … aber den Zustand schreiben: der Aufrufer speichert dabei die Grenze 240 mit
        assertEquals(true, run.crossed)
        // Danach neue Grenze 2.00×, Wert weiter jenseits → neu bewerten und melden
        val afterChange = IndicatorAlertRules.wasCrossed(storedCrossed = true, storedThreshold = 240, current = 200)
        assertFalse(afterChange)
        assertTrue(IndicatorAlertRules.decide(k, 2.6, 2.0, afterChange).notify)
    }
}
