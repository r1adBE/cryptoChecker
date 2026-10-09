package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveIntervalTest {

    @Test
    fun `visible uses the chosen interval, hidden at most once a minute`() {
        assertEquals(15_000L, LiveInterval.intervalMillis(15, appVisible = true))
        assertEquals(60_000L, LiveInterval.intervalMillis(15, appVisible = false))
        assertEquals(60_000L, LiveInterval.intervalMillis(30, appVisible = false))
        assertEquals(60_000L, LiveInterval.intervalMillis(60, appVisible = false))
        // Längere Intervalle bleiben
        assertEquals(300_000L, LiveInterval.intervalMillis(300, appVisible = false))
        // Untergrenze
        assertEquals(15_000L, LiveInterval.intervalMillis(5, appVisible = true))
    }

    @Test
    fun `becoming visible refreshes at once when the last run is older than the chosen interval`() {
        val last = 1_000_000L
        // Geschlossen: nach 20 s noch 40 s warten
        assertEquals(40_000L, LiveInterval.waitMillis(last, last + 20_000, 15, appVisible = false))
        // Wieder offen nach 20 s: 15 s sind schon um → sofort
        assertEquals(0L, LiveInterval.waitMillis(last, last + 20_000, 15, appVisible = true))
        // Offen nach 10 s: noch 5 s
        assertEquals(5_000L, LiveInterval.waitMillis(last, last + 10_000, 15, appVisible = true))
        // Uhr zurückgestellt: höchstens ein Intervall
        assertEquals(15_000L, LiveInterval.waitMillis(last, last - 50_000, 15, appVisible = true))
    }

    @Test
    fun `screen off slows down to at most every five minutes`() {
        assertEquals(300_000L, LiveInterval.intervalMillis(15, appVisible = false, screenOn = false))
        assertEquals(300_000L, LiveInterval.intervalMillis(60, appVisible = true, screenOn = false))
        assertEquals(600_000L, LiveInterval.intervalMillis(600, appVisible = false, screenOn = false))
        val last = 1_000_000L
        // Bildschirm wieder an nach 2 Minuten: Minuten-Takt ist um → sofort
        assertEquals(0L, LiveInterval.waitMillis(last, last + 120_000, 15, appVisible = false, screenOn = true))
        assertEquals(180_000L, LiveInterval.waitMillis(last, last + 120_000, 15, appVisible = false, screenOn = false))
    }
}
