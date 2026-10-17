package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchJumpTest {

    @Test
    fun eligible_onlyAboveThirtyAndNotSorting() {
        assertFalse(WatchJump.eligible(30, sorting = false))
        assertTrue(WatchJump.eligible(31, sorting = false))
        assertFalse(WatchJump.eligible(100, sorting = true))
    }

    @Test
    fun pointsDown_inUpperHalf() {
        assertTrue(WatchJump.pointsDown(firstVisible = 0, lastVisible = 10, total = 50))
        assertTrue(WatchJump.pointsDown(firstVisible = 15, lastVisible = 25, total = 50))
        assertFalse(WatchJump.pointsDown(firstVisible = 20, lastVisible = 30, total = 50))
        assertFalse(WatchJump.pointsDown(firstVisible = 40, lastVisible = 49, total = 50))
    }

    @Test
    fun animate_onlyShortDistancesWithoutReduceMotion() {
        assertTrue(WatchJump.animate(-40, reduceMotion = false))
        assertFalse(WatchJump.animate(41, reduceMotion = false))
        assertFalse(WatchJump.animate(5, reduceMotion = true))
    }
}
