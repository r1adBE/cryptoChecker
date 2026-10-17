package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.util.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zeitstempel der Börsen nach Grösse (s/ms/µs/ns) → ms. Gegenstück: `Tests/TimeUtilsTests.swift`. */
class TimeUtilsTest {

    @Test
    fun convertsByMagnitudeStepwise() {
        val ms = 1728540000123L
        assertEquals(1728540000000L, TimeUtils.parseTimeToMillis(1728540000L))
        assertEquals(ms, TimeUtils.parseTimeToMillis(ms))
        assertEquals(ms, TimeUtils.parseTimeToMillis(1728540000123456L))
        // Nanosekunden (Phemex): zweimal durch 1000, nicht nur einmal
        assertEquals(ms, TimeUtils.parseTimeToMillis(1728540000123456789L))
    }
}
