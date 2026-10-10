package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Futures-Alarme: Satz, Schwellen und Open-Interest-Verlauf über einen Tag (Rest: DerivativesParityTest). */
class DerivativesAlarmTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun conditionsAreDerivativesOnly() {
        val derivatives = AlarmCondition.entries.filter { it.isDerivatives }
        assertEquals(
            listOf(AlarmCondition.FUNDING_ABOVE, AlarmCondition.FUNDING_BELOW, AlarmCondition.OI_UP, AlarmCondition.OI_DOWN),
            derivatives,
        )
        derivatives.forEach {
            assertFalse(it.isPercent)
            assertFalse(it.isPriceThreshold)
            assertFalse(it.isNearExtreme)
        }
    }

    @Test
    fun thresholds() {
        assertTrue(DerivativesAlarm.isValidThreshold(AlarmCondition.FUNDING_BELOW, -0.01))
        assertTrue(DerivativesAlarm.isValidThreshold(AlarmCondition.FUNDING_ABOVE, 0.0))
        assertFalse(DerivativesAlarm.isValidThreshold(AlarmCondition.FUNDING_ABOVE, 10.5))
        assertFalse(DerivativesAlarm.isValidThreshold(AlarmCondition.OI_UP, 0.0))
        assertTrue(DerivativesAlarm.isValidThreshold(AlarmCondition.OI_DOWN, 5.0))
        assertFalse(DerivativesAlarm.isValidThreshold(AlarmCondition.PRICE_ABOVE, 5.0))
        assertFalse(DerivativesAlarm.isValidThreshold(AlarmCondition.OI_UP, Double.NaN))
    }

    @Test
    fun sentenceParts() {
        val de = Locale.forLanguageTag("de-CH")
        val funding = AlarmSentence.parts(AlarmSentence.Kind.FUNDING_ABOVE, "BTC", 0.05, "USDT", 1, de) { v, _ -> "$v" }
        assertNotNull(funding)
        assertEquals("0.05%", funding!!.value.replace(" ", "").replace(" ", "").replace("’", ""))
        // Funding unter 0 % ist gültig (Funding wird negativ)
        assertNotNull(AlarmSentence.parts(AlarmSentence.Kind.FUNDING_BELOW, "BTC", 0.0, "USDT", 1, de) { v, _ -> "$v" })
        val negative = AlarmSentence.parts(AlarmSentence.Kind.FUNDING_BELOW, "BTC", -0.0125, "USDT", 1, Locale.US) { v, _ -> "$v" }
        assertEquals("-0.0125%", negative!!.value)
        assertNull(AlarmSentence.parts(AlarmSentence.Kind.FUNDING_ABOVE, "BTC", null, "USDT", 1, de) { v, _ -> "$v" })
        assertNull(AlarmSentence.parts(AlarmSentence.Kind.FUNDING_ABOVE, " ", 0.05, "USDT", 1, de) { v, _ -> "$v" })
        // Open Interest: Fenster auf 1/4/24 Stunden
        val oi = AlarmSentence.parts(AlarmSentence.Kind.OI_UP, "ETH", 10.0, "USDT", 12, Locale.US) { v, _ -> "$v" }
        assertEquals(24, oi!!.windowHours)
        assertEquals("10%", oi.value)
        assertNull(AlarmSentence.parts(AlarmSentence.Kind.OI_DOWN, "ETH", 0.0, "USDT", 4, Locale.US) { v, _ -> "$v" })
        assertEquals("+12.3%", AlarmSentence.signedPercent(12.34, Locale.US))
        assertEquals("-4%", AlarmSentence.signedPercent(-4.0, Locale.US))
        assertEquals("0%", AlarmSentence.fundingPercent(-0.0, Locale.US))
    }

    /** Messungen alle 5 Min. über 30 Stunden: Verlauf bleibt klein und deckt 1, 4 und 24 Stunden ab. */
    @Test
    fun historyOverADay() {
        var history = emptyList<DerivativesAlarm.OiPoint>()
        var now = start
        var units = 1_000.0
        repeat(30 * 12) {
            history = DerivativesAlarm.appendOi(history, DerivativesAlarm.OiPoint(units, now), now)
            units += 1.0
            now += 5 * minute
        }
        val last = now - 5 * minute
        assertTrue("kept ${history.size}", history.size < 100)
        assertTrue(history.zipWithNext().all { (a, b) -> b.time > a.time })
        assertTrue(last - history.first().time <= DerivativesAlarm.OI_RETENTION_MILLIS)
        for (hours in DerivativesAlarm.OI_WINDOWS) {
            val change = DerivativesAlarm.oiChangePercent(history, units, hours, last)
            assertNotNull("window $hours", change)
            assertTrue("window $hours: $change", change!! > 0.0)
        }
        // Nach einer Lücke von zwei Tagen: nichts alt genug → kein Vergleich, Verlauf leer
        val later = last + 48 * 60 * minute
        assertNull(DerivativesAlarm.oiChangePercent(history, units, 24, later))
        assertTrue(DerivativesAlarm.pruneOi(history, later).isEmpty())
    }

    @Test
    fun newAlarmComparesWithinTheWindow() {
        // Erste Messung jetzt: nur die aktuelle Messung → kein Vergleich
        val history = DerivativesAlarm.appendOi(emptyList(), DerivativesAlarm.OiPoint(100.0, start), start)
        assertNull(DerivativesAlarm.oiChangePercent(history, 150.0, 1, start))
        // Eine Veränderung in kürzerer Zeit zählt auch als «in N Stunden»
        assertEquals(50.0, DerivativesAlarm.oiChangePercent(history, 150.0, 1, start + 30 * minute)!!, 1e-9)
        assertEquals(50.0, DerivativesAlarm.oiChangePercent(history, 150.0, 1, start + 60 * minute)!!, 1e-9)
        assertEquals(50.0, DerivativesAlarm.oiChangePercent(history, 150.0, 4, start + 60 * minute)!!, 1e-9)
    }

    @Test
    fun hourlyBackgroundRunsStillCompare() {
        // Hintergrund im Stundentakt mit Schwankung: Messungen 50 bzw. 100 Min. auseinander
        var history = emptyList<DerivativesAlarm.OiPoint>()
        history = DerivativesAlarm.appendOi(history, DerivativesAlarm.OiPoint(100.0, start), start)
        history = DerivativesAlarm.appendOi(history, DerivativesAlarm.OiPoint(104.0, start + 50 * minute), start + 50 * minute)
        // Nach weiteren 50 Min.: die Messung von vor 100 Min. ist zu alt (> 90), die von vor 50 Min. zählt
        val change = DerivativesAlarm.oiChangePercent(history, 115.0, 1, start + 100 * minute)
        assertEquals(115.0 / 104.0 * 100.0 - 100.0, change!!, 1e-9)
    }
}
