package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.util.EpochTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zeitstempel der Börsen nach Grösse (s/ms/µs/ns) → ms. Gegenstück: `Tests/EpochTimeTests.swift`. */
class EpochTimeTest {

    @Test
    fun convertsByMagnitudeStepwise() {
        val ms = 1728540000123L
        assertEquals(1728540000000L, EpochTime.parseTimeToMillis(1728540000L))
        assertEquals(ms, EpochTime.parseTimeToMillis(ms))
        assertEquals(ms, EpochTime.parseTimeToMillis(1728540000123456L))
        // Nanosekunden (Phemex): zweimal durch 1000, nicht nur einmal
        assertEquals(ms, EpochTime.parseTimeToMillis(1728540000123456789L))
    }
}
