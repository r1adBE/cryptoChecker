package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warten bis zum Ende der 15-s-Sperre ([WidgetRefreshUnlock.holdStep]): in Schritten unter der Grenze von goAsync,
 * am Ende genau ein Neuzeichnen — damit der Chronometer nie ins Minus zählt.
 */
class WidgetRefreshUnlockTest {
    private val now = 1_700_000_000_000L
    private val slack = WidgetRefreshState.UNLOCK_SLACK_MILLIS

    @Test
    fun fullLockIsSplitIntoHopsAndEndsWithOneRedraw() {
        var clock = now
        val blockedUntil = now + 15_000
        val steps = mutableListOf<WidgetRefreshUnlock.HoldStep>()
        do {
            val step = WidgetRefreshUnlock.holdStep(blockedUntil, clock)
            steps += step
            clock += step.waitMillis
        } while (!step.redraw && steps.size < 10)
        assertEquals(listOf(false, false, true), steps.map { it.redraw })
        assertEquals(blockedUntil + slack, clock)
        steps.forEach { assertTrue(it.waitMillis <= WidgetRefreshUnlock.HOP_MILLIS) }
    }

    @Test
    fun shortRestWaitsOnceThenRedraws() {
        assertEquals(WidgetRefreshUnlock.HoldStep(2_000 + slack, redraw = true),
            WidgetRefreshUnlock.holdStep(now + 2_000, now))
    }

    @Test
    fun lockAlreadyOverRedrawsAtOnce() {
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(now - 60_000, now))
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(0, now))
    }

    @Test
    fun implausibleEndRedrawsAtOnce() {
        // Uhr verstellt: Ende weiter weg als die Sperre selbst — nicht minutenlang warten
        assertEquals(WidgetRefreshUnlock.HoldStep(0, redraw = true), WidgetRefreshUnlock.holdStep(now + 3_600_000, now))
    }
}
