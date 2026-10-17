package com.cryptochecker.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frische der zwischengespeicherten Widget-Kerzen: entscheidet, ob nach dem Zeichnen ohne Netz nachgeladen wird. */
class WidgetSparkFreshnessTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `cached candles are fresh for ten minutes`() {
        assertTrue(isFreshSpark(now, now))
        assertTrue(isFreshSpark(now - SPARK_FRESH_MILLIS + 1, now))
        assertFalse(isFreshSpark(now - SPARK_FRESH_MILLIS, now))
    }

    @Test
    fun `missing or future-dated candles are stale`() {
        assertFalse(isFreshSpark(null, now))
        // Uhr zurückgestellt: Eintrag «aus der Zukunft» nicht ewig als frisch behandeln
        assertFalse(isFreshSpark(now + 60_000L, now))
    }
}
