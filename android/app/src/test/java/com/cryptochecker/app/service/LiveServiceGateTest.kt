package com.cryptochecker.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveServiceGateTest {

    private val now = 1_000_000_000L

    @Test
    fun `ohne Meldung laeuft der Dienst nicht`() {
        assertFalse(LiveServiceGate.isAlive(0L, now))
        assertFalse(LiveServiceGate.skipWorker(manual = false, lastBeatAt = 0L, now = now))
    }

    @Test
    fun `frische Meldung laesst den periodischen Job aus`() {
        assertTrue(LiveServiceGate.isAlive(now - 60_000L, now))
        assertTrue(LiveServiceGate.skipWorker(manual = false, lastBeatAt = now - 60_000L, now = now))
    }

    @Test
    fun `Knopfdruck laeuft immer`() {
        assertFalse(LiveServiceGate.skipWorker(manual = true, lastBeatAt = now - 1_000L, now = now))
    }

    @Test
    fun `alte oder unplausible Meldung zaehlt nicht`() {
        assertFalse(LiveServiceGate.isAlive(now - LiveServiceGate.MAX_AGE_MILLIS, now))
        assertFalse(LiveServiceGate.isAlive(now + 5_000L, now))
        // Längstes Live-Intervall (300 s) zweimal: noch laufend
        assertTrue(LiveServiceGate.isAlive(now - 600_000L, now))
    }

    @Test
    fun `beat und stopped`() {
        LiveServiceGate.beat(now)
        assertTrue(LiveServiceGate.isRunning(now + 1_000L))
        LiveServiceGate.stopped()
        assertFalse(LiveServiceGate.isRunning(now + 1_000L))
    }
}
