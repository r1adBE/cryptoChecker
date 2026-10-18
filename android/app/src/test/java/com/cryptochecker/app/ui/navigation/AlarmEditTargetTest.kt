package com.cryptochecker.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ziel «Anpassen» aus der Alarm-Mitteilung (wie iOS `AppRouter.alarmEditTarget`). */
class AlarmEditTargetTest {

    @Test
    fun roundTrip() {
        val target = AlarmEditTarget.of(12, 345)
        assertEquals("alarm/12/345", target)
        assertEquals(12L to 345L, AlarmEditTarget.parse(target))
    }

    @Test
    fun otherOrBrokenTargetsAreIgnored() {
        assertNull(AlarmEditTarget.parse(null))
        assertNull(AlarmEditTarget.parse("alarms"))
        assertNull(AlarmEditTarget.parse("why/12"))
        assertNull(AlarmEditTarget.parse("alarm/12"))
        assertNull(AlarmEditTarget.parse("alarm/12/x"))
        assertNull(AlarmEditTarget.parse("alarm/0/5"))
        assertNull(AlarmEditTarget.parse("alarm/12/5/7"))
    }
}
