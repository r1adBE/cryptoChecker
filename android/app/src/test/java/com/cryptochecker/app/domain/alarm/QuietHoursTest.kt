package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class QuietHoursTest {

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun offIsNeverQuiet() {
        assertFalse(QuietHours.isQuiet(false, hm(23), hm(7), hm(2)))
    }

    @Test
    fun spanAcrossMidnight() {
        val quiet = { m: Int -> QuietHours.isQuiet(true, hm(23), hm(7), m) }
        assertTrue(quiet(hm(23)))
        assertTrue(quiet(hm(23, 59)))
        assertTrue(quiet(hm(0)))
        assertTrue(quiet(hm(6, 59)))
        assertFalse(quiet(hm(7)))
        assertFalse(quiet(hm(12)))
        assertFalse(quiet(hm(22, 59)))
    }

    @Test
    fun spanWithinDay() {
        val quiet = { m: Int -> QuietHours.isQuiet(true, hm(13), hm(14), m) }
        assertFalse(quiet(hm(12, 59)))
        assertTrue(quiet(hm(13)))
        assertTrue(quiet(hm(13, 59)))
        assertFalse(quiet(hm(14)))
        assertFalse(quiet(hm(2)))
    }

    @Test
    fun equalStartAndEndMeansNever() {
        listOf(0, hm(7), hm(12), hm(23, 59)).forEach {
            assertFalse(QuietHours.isQuiet(true, hm(7), hm(7), it))
        }
    }

    @Test
    fun invalidTimesAreNeverQuiet() {
        assertFalse(QuietHours.isQuiet(true, -1, hm(7), hm(3)))
        assertFalse(QuietHours.isQuiet(true, hm(23), 1440, hm(3)))
    }

    @Test
    fun defaults() {
        assertEquals(1380, QuietHours.DEFAULT_START)
        assertEquals(420, QuietHours.DEFAULT_END)
    }

    @Test
    fun minuteOfDayUsesGivenZone() {
        // 2026-01-01T22:30:00Z
        val millis = 1767306600000L
        assertEquals(hm(22, 30), QuietHours.minuteOfDay(millis, TimeZone.getTimeZone("UTC")))
        assertEquals(hm(23, 30), QuietHours.minuteOfDay(millis, TimeZone.getTimeZone("Europe/Zurich")))
    }
}
