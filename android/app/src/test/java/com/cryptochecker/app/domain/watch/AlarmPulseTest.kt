package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPulseTest {

    @Test
    fun `nur eine neue Auslösung bei offener App pulsiert`() {
        // Erster Stand beim Öffnen: schon vorhandene Auslösungen pulsieren nicht
        assertFalse(AlarmPulse.isNew(null, 1_000L))
        assertFalse(AlarmPulse.isNew(null, null))
        // Neue Auslösung
        assertTrue(AlarmPulse.isNew(1_000L, 2_000L))
        assertTrue(AlarmPulse.isNew(0L, 2_000L))
        // Unverändert, zurückgesetzt oder noch nie
        assertFalse(AlarmPulse.isNew(2_000L, 2_000L))
        assertFalse(AlarmPulse.isNew(2_000L, 0L))
        assertFalse(AlarmPulse.isNew(0L, 0L))
        assertFalse(AlarmPulse.isNew(1_000L, null))
    }

    @Test
    fun `Puls ist kurz und dezent`() {
        assertTrue(AlarmPulse.MILLIS in 100..250)
        assertTrue(AlarmPulse.SCALE > 1f && AlarmPulse.SCALE <= 1.25f)
    }
}
