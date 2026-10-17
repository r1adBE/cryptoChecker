package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.BackgroundSchedule.ExactState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `BackgroundScheduleTests` (iOS, CheckRunLogTests.swift). */
class BackgroundScheduleTest {

    private val now = 1_700_000_000_000L
    private val window = BackgroundSchedule.DEDUPE_WINDOW_MILLIS
    private val interval = BackgroundSchedule.EXACT_INTERVAL_MILLIS

    @Test
    fun exactStateOrder() {
        assertEquals(ExactState.OFF, BackgroundSchedule.exactState(enabled = false, permitted = false, hasWork = false))
        assertEquals(ExactState.OFF, BackgroundSchedule.exactState(enabled = false, permitted = true, hasWork = true))
        assertEquals(ExactState.PERMISSION_MISSING, BackgroundSchedule.exactState(enabled = true, permitted = false, hasWork = true))
        assertEquals(ExactState.PERMISSION_MISSING, BackgroundSchedule.exactState(enabled = true, permitted = false, hasWork = false))
        assertEquals(ExactState.NOTHING_TO_CHECK, BackgroundSchedule.exactState(enabled = true, permitted = true, hasWork = false))
        assertEquals(ExactState.ACTIVE, BackgroundSchedule.exactState(enabled = true, permitted = true, hasWork = true))
    }

    @Test
    fun armOnlyWhenActive() {
        assertTrue(BackgroundSchedule.shouldArmExact(enabled = true, permitted = true, hasWork = true))
        assertFalse(BackgroundSchedule.shouldArmExact(enabled = false, permitted = true, hasWork = true))
        assertFalse(BackgroundSchedule.shouldArmExact(enabled = true, permitted = false, hasWork = true))
        assertFalse(BackgroundSchedule.shouldArmExact(enabled = true, permitted = true, hasWork = false))
    }

    @Test
    fun rearmFifteenMinutesLater() {
        assertEquals(now + 15 * 60_000L, BackgroundSchedule.nextExactAt(now))
        // Über dem Doze-Minimum für allow-while-idle (rund 9 Min.)
        assertTrue(interval >= 9 * 60_000L)
    }

    @Test
    fun needsArm() {
        assertTrue(BackgroundSchedule.needsArm(armedFor = 0, now = now))
        assertTrue(BackgroundSchedule.needsArm(armedFor = now, now = now))
        assertTrue(BackgroundSchedule.needsArm(armedFor = now - 1, now = now))
        assertFalse(BackgroundSchedule.needsArm(armedFor = now + 60_000, now = now))
        assertFalse(BackgroundSchedule.needsArm(armedFor = now + interval, now = now))
        // Uhr zurückgestellt: unplausibel weit weg → neu stellen
        assertTrue(BackgroundSchedule.needsArm(armedFor = now + interval + 1, now = now))
        // Neustart/Update: immer
        assertTrue(BackgroundSchedule.needsArm(armedFor = now + 60_000, now = now, force = true))
    }

    @Test
    fun dedupeOnlyScheduledWithinWindow() {
        assertTrue(BackgroundSchedule.shouldSkip(CheckTrigger.WORKER, now - 1_000, now))
        assertTrue(BackgroundSchedule.shouldSkip(CheckTrigger.EXACT, now, now))
        assertTrue(BackgroundSchedule.shouldSkip(CheckTrigger.BG_TASK, now - window + 1, now))
        assertFalse(BackgroundSchedule.shouldSkip(CheckTrigger.EXACT, now - window, now))
        assertFalse(BackgroundSchedule.shouldSkip(CheckTrigger.WORKER, 0, now))
        // Uhr verstellt: nicht sperren
        assertFalse(BackgroundSchedule.shouldSkip(CheckTrigger.WORKER, now + 10_000, now))
        // Knopf im Widget und in der App: immer
        assertFalse(BackgroundSchedule.shouldSkip(CheckTrigger.WIDGET, now - 1_000, now))
        assertFalse(BackgroundSchedule.shouldSkip(CheckTrigger.FOREGROUND, now - 1_000, now))
    }

    @Test
    fun batteryHint() {
        assertTrue(BackgroundSchedule.showBatteryHint(batteryRestricted = true, hasWork = true))
        assertFalse(BackgroundSchedule.showBatteryHint(batteryRestricted = true, hasWork = false))
        assertFalse(BackgroundSchedule.showBatteryHint(batteryRestricted = false, hasWork = true))
    }

    @Test
    fun `only a full check counts for the dedupe`() {
        assertTrue(BackgroundSchedule.isFullCheck(attempted = 10, failed = 0))
        assertTrue(BackgroundSchedule.isFullCheck(attempted = 10, failed = 4))
        // Hälfte oder mehr ohne Kurs: kein voller Lauf
        assertFalse(BackgroundSchedule.isFullCheck(attempted = 10, failed = 5))
        assertFalse(BackgroundSchedule.isFullCheck(attempted = 1, failed = 1))
        assertTrue(BackgroundSchedule.isFullCheck(attempted = 1, failed = 0))
        // Nichts abgefragt (alles live bzw. pausiert): kein voller Lauf
        assertFalse(BackgroundSchedule.isFullCheck(attempted = 0, failed = 0))
    }
}
