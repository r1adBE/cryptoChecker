package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Karte «Ersten Alarm setzen» — gleiche Fälle wie `FirstAlarmCardTests.swift`. */
class FirstAlarmCardTest {

    @Test
    fun `shown with pairs and no alarm`() {
        assertTrue(FirstAlarmCard.shouldShow(alarmCount = 0, watchCount = 1, dismissed = false, busy = false))
        assertTrue(FirstAlarmCard.shouldShow(alarmCount = 0, watchCount = 25, dismissed = false, busy = false))
    }

    @Test
    fun `hidden once an alarm exists or before loading`() {
        assertFalse(FirstAlarmCard.shouldShow(alarmCount = 1, watchCount = 3, dismissed = false, busy = false))
        assertFalse(FirstAlarmCard.shouldShow(alarmCount = null, watchCount = 3, dismissed = false, busy = false))
    }

    @Test
    fun `hidden without pairs, after dismissing or while busy`() {
        assertFalse(FirstAlarmCard.shouldShow(alarmCount = 0, watchCount = 0, dismissed = false, busy = false))
        assertFalse(FirstAlarmCard.shouldShow(alarmCount = 0, watchCount = 3, dismissed = true, busy = false))
        assertFalse(FirstAlarmCard.shouldShow(alarmCount = 0, watchCount = 3, dismissed = false, busy = true))
    }
}
