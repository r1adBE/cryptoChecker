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

    @Test
    fun remainingSecondsRoundsUp() {
        val w = RefreshDebounce.WINDOW_MILLIS
        assertEquals(15, RefreshDebounce.remainingSeconds(now, now))
        assertEquals(15, RefreshDebounce.remainingSeconds(now - 1, now))
        assertEquals(14, RefreshDebounce.remainingSeconds(now - 1_000, now))
        assertEquals(6, RefreshDebounce.remainingSeconds(now - 9_800, now))
        assertEquals(1, RefreshDebounce.remainingSeconds(now - w + 1, now))
    }

    @Test
    fun remainingSecondsZeroWhenFree() {
        assertEquals(0, RefreshDebounce.remainingSeconds(0, now))
        assertEquals(0, RefreshDebounce.remainingSeconds(now - RefreshDebounce.WINDOW_MILLIS, now))
        assertEquals(0, RefreshDebounce.remainingSeconds(now - 60_000, now))
        // Uhr zurückgestellt: frei wie bei decide
        assertEquals(0, RefreshDebounce.remainingSeconds(now + 5_000, now))
    }

    @Test
    fun remainingSecondsMatchesDecide() {
        for (age in -2_000L..20_000L step 250) {
            val recent = RefreshDebounce.decide(false, now - age, now) == Decision.RECENT
            assertEquals(recent, RefreshDebounce.remainingSeconds(now - age, now) > 0)
        }
    }

    @Test
    fun nextChangeLandsOnSecondBoundary() {
        assertEquals(1_000L, RefreshDebounce.millisUntilNextChange(now, now))
        assertEquals(800L, RefreshDebounce.millisUntilNextChange(now - 200, now))
        assertEquals(200L, RefreshDebounce.millisUntilNextChange(now - 9_800, now))
        assertEquals(1L, RefreshDebounce.millisUntilNextChange(now - RefreshDebounce.WINDOW_MILLIS + 1, now))
        assertEquals(0L, RefreshDebounce.millisUntilNextChange(now - RefreshDebounce.WINDOW_MILLIS, now))
        assertEquals(0L, RefreshDebounce.millisUntilNextChange(0, now))
        // Nach dem Warten zeigt der Countdown genau eine Zahl weniger
        val last = now - 9_800
        val wait = RefreshDebounce.millisUntilNextChange(last, now)
        assertEquals(RefreshDebounce.remainingSeconds(last, now) - 1, RefreshDebounce.remainingSeconds(last, now + wait))
    }

    @Test
    fun pullInsideWindowEchoesSecondsSinceLastRefresh() {
        // Ziehen im Fenster: kein Durchlauf, aber «Gerade aktualisiert · vor N s» (abgerundet, mindestens 1)
        val last = now - 8_400
        assertEquals(Decision.RECENT, RefreshDebounce.decide(false, last, now))
        assertEquals(8, RefreshDebounce.pullEchoSeconds(Decision.RECENT, last, now))
        assertEquals(1, RefreshDebounce.pullEchoSeconds(Decision.RECENT, now - 200, now))
        assertEquals(1, RefreshDebounce.pullEchoSeconds(Decision.RECENT, now, now))
        assertEquals(400L, RefreshDebounce.PULL_ECHO_MILLIS)
    }

    @Test
    fun pullOutsideWindowHasNoEcho() {
        // Neuer Durchlauf bzw. läuft schon: der Kreisel zeigt genug
        assertEquals(null, RefreshDebounce.pullEchoSeconds(Decision.START, now - 8_000, now))
        assertEquals(null, RefreshDebounce.pullEchoSeconds(Decision.RUNNING, now - 8_000, now))
        assertEquals(null, RefreshDebounce.pullEchoSeconds(Decision.RECENT, 0, now))
        // Uhr zurückgestellt
        assertEquals(null, RefreshDebounce.pullEchoSeconds(Decision.RECENT, now + 5_000, now))
        // Gleiche Grenze wie decide: jede Entscheidung RECENT hat eine Rückmeldung
        for (age in 0L until RefreshDebounce.WINDOW_MILLIS step 250) {
            val decision = RefreshDebounce.decide(false, now - age, now)
            assertEquals(decision == Decision.RECENT, RefreshDebounce.pullEchoSeconds(decision, now - age, now) != null)
        }
    }
}
