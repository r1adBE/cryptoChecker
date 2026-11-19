package com.cryptochecker.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadProgressTest {

    @Test
    fun `starts small, grows per step, ends full`() {
        val p = LoadProgress(4)
        assertEquals(LoadProgress.START, p.current(), 1e-6f)
        assertEquals(0.31f, p.step(), 1e-6f)
        p.step()
        p.step()
        assertEquals(1f, p.step(), 1e-6f)
        // Mehr Schritte als angekündigt: bleibt voll
        assertEquals(1f, p.step(), 1e-6f)
    }

    @Test
    fun `no steps counts as done`() {
        assertEquals(1f, LoadProgress(0).current(), 0f)
    }

    @Test
    fun `tally counts parts that join while loading and resets when idle`() {
        val tally = LoadTally<String>()
        assertEquals(null, tally.next(emptySet()))
        assertEquals(LoadProgress.START, tally.next(setOf("a", "b"))!!, 1e-6f)
        // «a» fertig, «c» kommt dazu: 1 von 3
        assertEquals(LoadProgress.shown(1, 3), tally.next(setOf("b", "c"))!!, 1e-6f)
        assertEquals(LoadProgress.shown(2, 3), tally.next(setOf("c"))!!, 1e-6f)
        assertEquals(null, tally.next(emptySet()))
        // Neues Laden zählt von vorn
        assertEquals(LoadProgress.START, tally.next(setOf("a"))!!, 1e-6f)
    }
}
