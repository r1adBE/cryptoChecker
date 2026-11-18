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
}
