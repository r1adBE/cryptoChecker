package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merkliste nach der Start-Auswahl schrittweise — gleiche Fälle wie `WatchlistOnboardingTests.swift`. */
class WatchlistOnboardingTest {

    @Test
    fun `first use hides groups hint, pulse and gesture hint`() {
        val v = WatchlistOnboarding.visible(alarmCount = 0, usageDays = 1, hasGroupsOrFavorites = false)
        assertFalse(v.groupsHint)
        assertFalse(v.pulse)
        assertFalse(v.gestureHint)
    }

    @Test
    fun `alarms not loaded yet count as none`() {
        val v = WatchlistOnboarding.visible(alarmCount = null, usageDays = 0, hasGroupsOrFavorites = false)
        assertFalse(v.pulse)
        assertFalse(v.gestureHint)
        assertFalse(v.groupsHint)
    }

    @Test
    fun `first alarm shows everything`() {
        val v = WatchlistOnboarding.visible(alarmCount = 1, usageDays = 0, hasGroupsOrFavorites = false)
        assertTrue(v.groupsHint)
        assertTrue(v.pulse)
        assertTrue(v.gestureHint)
    }

    @Test
    fun `three different days show everything without an alarm`() {
        assertFalse(WatchlistOnboarding.visible(0, WatchlistOnboarding.UNLOCK_DAYS - 1, false).pulse)
        val v = WatchlistOnboarding.visible(alarmCount = 0, usageDays = WatchlistOnboarding.UNLOCK_DAYS, hasGroupsOrFavorites = false)
        assertTrue(v.groupsHint)
        assertTrue(v.pulse)
        assertTrue(v.gestureHint)
    }

    @Test
    fun `groups or favorites show chips instead of the hint`() {
        val v = WatchlistOnboarding.visible(alarmCount = 2, usageDays = 5, hasGroupsOrFavorites = true)
        assertFalse(v.groupsHint)
        assertTrue(v.pulse)
        assertTrue(v.gestureHint)
    }

    @Test
    fun `usage days count each later day once`() {
        val first = UsageDays(0, null).next(100)
        assertEquals(UsageDays(1, 100), first)
        // Derselbe Tag zählt nicht nochmals
        assertEquals(first, first.next(100))
        assertEquals(UsageDays(2, 101), first.next(101))
        // Lücke von mehreren Tagen zählt als ein Tag
        assertEquals(UsageDays(3, 110), first.next(101).next(110))
    }

    @Test
    fun `usage days ignore a clock set back`() {
        assertEquals(UsageDays(2, 99), UsageDays(2, 101).next(99))
        assertEquals(UsageDays(UsageDays.MAX_COUNT, 6), UsageDays(UsageDays.MAX_COUNT, 5).next(6))
    }
}
