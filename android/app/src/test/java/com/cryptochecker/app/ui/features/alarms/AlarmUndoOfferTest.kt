package com.cryptochecker.app.ui.features.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Rückgängig» nach einem Schnell-Alarm ([AlarmUndoOffer]): bleibt offen, bis es gezeigt und erledigt ist — auch über
 * «Prüfen» im Dialog nach dem allerersten Alarm hinweg —, aber nicht ewig ([PendingAlarmUndo.isFresh]).
 */
class AlarmUndoOfferTest {

    @Test
    fun staysUntilDone() {
        val offer = AlarmUndoOffer()
        val undo = PendingAlarmUndo(alarmId = 5, text = "Alarm gespeichert", createdAt = 1_000)
        offer.offer(undo)
        // Bildschirm verlassen und zurück: das Angebot ist noch da
        assertEquals(undo, offer.pending.value)
        offer.done(5)
        assertNull(offer.pending.value)
    }

    @Test
    fun doneOfOlderOfferKeepsNewerOne() {
        val offer = AlarmUndoOffer()
        offer.offer(PendingAlarmUndo(5, "a", 1_000))
        val newer = PendingAlarmUndo(6, "b", 2_000)
        offer.offer(newer)
        offer.done(5)
        assertEquals(newer, offer.pending.value)
    }

    @Test
    fun freshForTenMinutesOnly() {
        val undo = PendingAlarmUndo(5, "a", createdAt = 1_000)
        assertTrue(undo.isFresh(1_000))
        assertTrue(undo.isFresh(1_000 + PendingAlarmUndo.MAX_AGE_MILLIS))
        assertFalse(undo.isFresh(1_001 + PendingAlarmUndo.MAX_AGE_MILLIS))
        // Uhr zurückgestellt: nicht mehr anbieten
        assertFalse(undo.isFresh(999))
    }
}
