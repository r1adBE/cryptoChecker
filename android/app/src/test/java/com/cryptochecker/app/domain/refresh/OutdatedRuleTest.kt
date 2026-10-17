package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdatedRuleTest {

    private val min = 60_000L

    @Test
    fun threeTimesIntervalWithFloor() {
        // Hintergrund 15 Min. → 45 Min.
        assertEquals(45 * min, OutdatedRule.afterMillis(15))
        // Hintergrund 60 Min. → 3 Std.
        assertEquals(180 * min, OutdatedRule.afterMillis(60))
        // Kurzes Intervall (1 Min.) → 3 Min., aber mindestens 15 Min.
        assertEquals(15 * min, OutdatedRule.afterMillis(1))
        assertEquals(OutdatedRule.MIN_MILLIS, OutdatedRule.afterMillis(5))
    }

    @Test
    fun liveLimitIsTwoMinutes() {
        // Paar am Live-Strom (LiveStatus): 2 Min. statt 15 Min.
        assertEquals(2 * min, OutdatedRule.LIVE_MILLIS)
        val now = 1_000_000_000L
        assertFalse(OutdatedRule.isOutdated(now - 18_000, now, OutdatedRule.LIVE_MILLIS))
        assertTrue(OutdatedRule.isOutdated(now - 4 * min, now, OutdatedRule.LIVE_MILLIS))
    }

    @Test
    fun outdatedOnlyPastLimitAndWithTime() {
        val after = 45 * min
        val now = 1_000_000_000L
        assertFalse(OutdatedRule.isOutdated(now - after, now, after))
        assertTrue(OutdatedRule.isOutdated(now - after - 1, now, after))
        assertFalse(OutdatedRule.isOutdated(0L, now, after))
        assertFalse(OutdatedRule.isOutdated(-5L, now, after))
        // Uhr zurückgestellt (Zeit in der Zukunft): nicht veraltet
        assertFalse(OutdatedRule.isOutdated(now + 10 * min, now, after))
    }

    @Test
    fun nextChangeIsEarliestFreshTimePlusLimit() {
        val after = 45 * min
        val now = 1_000_000_000L
        val times = listOf(now - 10 * min, now - 40 * min, now - 2 * after, 0L)
        // now − 40 Min. wird zuerst veraltet: in 5 Min. (+1 ms)
        assertEquals(now - 40 * min + after + 1, OutdatedRule.nextChangeAt(times, now, after))
    }

    @Test
    fun noNextChangeWhenAllOutdatedOrEmpty() {
        val after = 45 * min
        val now = 1_000_000_000L
        assertNull(OutdatedRule.nextChangeAt(listOf(now - 2 * after, 0L), now, after))
        assertNull(OutdatedRule.nextChangeAt(emptyList(), now, after))
    }
}
