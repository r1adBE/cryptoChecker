package com.cryptochecker.app.ui.components

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ergebnisse nach der Rückfrage «nicht gespeichert» gehen nicht verloren, wenn gerade kein Bildschirm
 * sammelt (Activity wird neu erstellt), und kommen genau einmal an — auch beim Wechsel des Sammlers (alter Bildschirm weg,
 * neuer da).
 */
class ScreenEventsTest {

    @Test
    fun eventsWaitForTheNextScreenAndArriveOnce() = runTest {
        val events = ScreenEvents<String>()
        // Kein Sammler (zwischen alter und neuer Activity): gepuffert
        events.send("close")
        events.send("saved")
        assertEquals(listOf("close", "saved"), events.events.take(2).toList())
        // Schon zugestellt: der nächste Bildschirm bekommt sie nicht noch einmal
        assertNull(withTimeoutOrNull(50) { events.events.first() })
    }

    @Test
    fun aNewCollectorTakesOverAfterTheOldOneIsGone() = runTest {
        val events = ScreenEvents<Int>()
        val old = mutableListOf<Int>()
        val oldScreen = launch { events.events.collect { old += it } }
        testScheduler.advanceUntilIdle()
        events.send(1)
        testScheduler.advanceUntilIdle()
        oldScreen.cancel()
        // Antwort kommt, während der alte Bildschirm weg und der neue noch nicht da ist
        events.send(2)
        assertEquals(listOf(1), old)
        assertEquals(2, events.events.first())
    }
}
