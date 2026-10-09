package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.RefreshDebounce.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshDebounceTest {

    private val now = 1_000_000_000L

    @Test
    fun runningWins() {
        assertEquals(Decision.RUNNING, RefreshDebounce.decide(running = true, lastFinishedAt = 0, now = now))
        assertEquals(Decision.RUNNING, RefreshDebounce.decide(running = true, lastFinishedAt = now - 60_000, now = now))
    }

    @Test
    fun neverRefreshedStarts() {
        assertEquals(Decision.START, RefreshDebounce.decide(running = false, lastFinishedAt = 0, now = now))
    }

    @Test
    fun withinWindowIsRecent() {
        assertEquals(Decision.RECENT, RefreshDebounce.decide(false, now, now))
        assertEquals(Decision.RECENT, RefreshDebounce.decide(false, now - 8_000, now))
        assertEquals(Decision.RECENT, RefreshDebounce.decide(false, now - RefreshDebounce.WINDOW_MILLIS + 1, now))
    }

    @Test
    fun afterWindowStarts() {
        assertEquals(Decision.START, RefreshDebounce.decide(false, now - RefreshDebounce.WINDOW_MILLIS, now))
        assertEquals(Decision.START, RefreshDebounce.decide(false, now - 60_000, now))
    }

    @Test
    fun futureTimestampStarts() {
        // Uhr zurückgestellt: nicht für immer sperren
        assertEquals(Decision.START, RefreshDebounce.decide(false, now + 5_000, now))
    }
}
