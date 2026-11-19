package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drehender Knopf der Widgets ([WidgetTapSpin]): bis geladen ist, mindestens kurz, nie endlos. */
class WidgetTapSpinTest {

    private val tap = 1_000_000L

    @Test
    fun spinsUntilLoadedButAtLeastTheAckTime() {
        // Lädt noch
        assertTrue(WidgetTapSpin.busy(tap, doneAt = 0L, now = tap + 5_000L))
        // Schnell fertig: trotzdem noch kurz
        assertTrue(WidgetTapSpin.busy(tap, doneAt = tap + 200L, now = tap + 500L))
        // Fertig und die Mindestzeit vorbei
        assertFalse(WidgetTapSpin.busy(tap, doneAt = tap + 200L, now = tap + WidgetTapSpin.ACK_MILLIS))
    }

    @Test
    fun neverSpinsWithoutTapOrForever() {
        assertFalse(WidgetTapSpin.busy(0L, doneAt = 0L, now = tap))
        assertFalse(WidgetTapSpin.busy(tap, doneAt = 0L, now = tap + WidgetTapSpin.MAX_MILLIS))
        // Uhr zurückgestellt
        assertFalse(WidgetTapSpin.busy(tap, doneAt = 0L, now = tap - 1L))
    }

    @Test
    fun ackLeftIsTheRestOfTheMinimum() {
        assertEquals(700L, WidgetTapSpin.ackLeft(tap, tap + 300L))
        assertEquals(0L, WidgetTapSpin.ackLeft(tap, tap + 5_000L))
    }
}
