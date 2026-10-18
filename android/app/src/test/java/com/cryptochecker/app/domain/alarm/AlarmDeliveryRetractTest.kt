package com.cryptochecker.app.domain.alarm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gezeigte Meldung, aber Alarm nicht gespeichert (inzwischen bearbeitet): Meldung wieder weg. Wie iOS. */
class AlarmDeliveryRetractTest {

    private suspend fun deliver(shown: Boolean, saved: Boolean, retracted: MutableList<Long>): Boolean =
        AlarmDelivery().deliver(
            alarmId = 7, now = 1_000_000L, cooldownMinutes = 15, holdAlarms = false,
            canShow = { true }, show = { shown }, mark = { saved },
            retract = { retracted += 7L },
        )

    @Test
    fun notSavedRetractsTheNotification() = runBlocking {
        val retracted = ArrayList<Long>()
        assertFalse(deliver(shown = true, saved = false, retracted))
        assertEquals(listOf(7L), retracted)
    }

    @Test
    fun savedKeepsTheNotification() = runBlocking {
        val retracted = ArrayList<Long>()
        assertTrue(deliver(shown = true, saved = true, retracted))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun unconfirmedPostIsNotRetracted() = runBlocking {
        // Nicht bestätigt: nichts gespeichert, aber auch nichts zu entfernen (bleibt scharf, Abklingzeit)
        val retracted = ArrayList<Long>()
        assertFalse(deliver(shown = false, saved = true, retracted))
        assertTrue(retracted.isEmpty())
    }
}
