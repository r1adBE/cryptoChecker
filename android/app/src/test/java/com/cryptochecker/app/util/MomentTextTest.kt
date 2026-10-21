package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** «zuletzt um …»: heute nur die Uhrzeit, an einem anderen Tag mit Datum ([MomentText]). Gegenstück: `MomentTextTests.swift`. */
class MomentTextTest {

    private val zone = ZoneId.of("Europe/Zurich")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun gleicherTagNachKalender() {
        val now = at(2026, 10, 12, 0, 30)
        assertTrue(MomentText.isSameDay(at(2026, 10, 12, 0, 5), now, zone))
        // Vor Mitternacht = gestern, auch wenn nur 40 Minuten her
        assertFalse(MomentText.isSameDay(at(2026, 10, 11, 23, 50), now, zone))
    }

    @Test
    fun heuteNurUhrzeitSonstMitDatum() {
        val now = at(2026, 10, 12, 18, 0)
        val today = MomentText.moment(at(2026, 10, 12, 14, 2, 11), now, zone, Locale.GERMANY)
        assertEquals("14:02:11", today)
        val earlier = MomentText.moment(at(2026, 10, 9, 14, 2, 11), now, zone, Locale.GERMANY)
        assertTrue(earlier, earlier.contains("09.10.26"))
        assertTrue(earlier, earlier.contains("14:02"))
        assertFalse(earlier, earlier.contains("14:02:11"))
        assertEquals("—", MomentText.moment(0, now, zone, Locale.GERMANY))
    }

    @Test
    fun zwoelfOderVierundzwanzigStundenNachSystem() {
        val now = at(2026, 10, 12, 18, 0)
        // Englisch, Gerät auf 24 h: «14:02:11» statt «2:02:11 PM»
        assertEquals("14:02:11", MomentText.moment(at(2026, 10, 12, 14, 2, 11), now, zone, Locale.US, is24 = true))
        val earlier = MomentText.moment(at(2026, 10, 9, 14, 2, 11), now, zone, Locale.US, is24 = true)
        assertTrue(earlier, earlier.endsWith("14:02"))
        // Deutsch, Gerät auf 12 h
        val twelve = MomentText.moment(at(2026, 10, 12, 14, 2, 11), now, zone, Locale.GERMANY, is24 = false)
        assertTrue(twelve, twelve.startsWith("2:02:11 "))
    }
}
