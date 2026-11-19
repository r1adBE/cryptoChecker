package com.cryptochecker.app.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wie `WidgetHeaderStampTests.swift`. */
class WidgetHeaderStampTest {

    @Test
    fun durationOnlyForTheSameData() {
        val stamp = WidgetHeaderStamp.finished(dataAt = 5_000L, startedAt = 10_000L, now = 10_840L)
        assertEquals(840L, stamp.durationFor(5_000L))
        assertEquals(10_840L, stamp.finishedAt)
        // Neuere Daten aus der App: ohne Dauer
        assertEquals(0L, stamp.durationFor(6_000L))
        assertEquals(0L, stamp.durationFor(0L))
    }

    @Test
    fun clockSetBackGivesNoNegativeDuration() {
        assertEquals(0L, WidgetHeaderStamp.finished(dataAt = 1L, startedAt = 2_000L, now = 1_000L).durationMillis)
    }

    @Test
    fun emptyStampHasNoDuration() {
        assertEquals(0L, WidgetHeaderStamp().durationFor(0L))
    }
}
