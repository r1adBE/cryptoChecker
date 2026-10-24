package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.alarm.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Nachtruhe als gemeinsame Testfälle (`testdata/parity/quiet_hours.json`, Kopie in iOS `Tests/Parity/`, gelesen von
 * `QuietHoursParityTests.swift`): Grenzen, Zeitraum über Mitternacht, Beginn = Ende, ungültige Zeiten und die Minute
 * des Tages an den Sommerzeit-Wechseln (Europe/Zurich, America/New_York).
 */
class QuietHoursParityTest {

    private val data = ParityJson.load("quiet_hours.json")

    @Test
    fun isQuietAndHoldsAlarms() {
        val cases = data["isQuiet"].list()
        assertTrue(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val enabled = c["enabled"] as Boolean
            val start = c["start"].num()!!.toInt()
            val end = c["end"].num()!!.toInt()
            val minute = c["minute"].num()!!.toInt()
            assertEquals(name, c["quiet"], QuietHours.isQuiet(enabled, start, end, minute))
            assertEquals("$name (holdsAlarms)", c["quiet"], QuietHours.holdsAlarms(enabled, start, end, minute))
        }
    }

    @Test
    fun minuteOfDayAcrossDaylightSavingChanges() {
        data["minuteOfDay"].list().also { assertTrue("minuteOfDay: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val millis = c["millis"].num()!!.toLong()
            assertEquals("${c["name"]}: utc", Instant.parse(c["utc"] as String).toEpochMilli(), millis)
            val minute = QuietHours.minuteOfDay(millis, TimeZone.getTimeZone(c["zone"] as String))
            assertEquals(c["name"] as String, c["minute"].num()!!.toInt(), minute)
        }
    }

    @Test
    fun quietAtInstantInZone() {
        data["quietAt"].list().also { assertTrue("quietAt: keine Fälle", it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val minute = QuietHours.minuteOfDay(c["millis"].num()!!.toLong(), TimeZone.getTimeZone(c["zone"] as String))
            val quiet = QuietHours.isQuiet(true, c["start"].num()!!.toInt(), c["end"].num()!!.toInt(), minute)
            assertEquals(c["name"] as String, c["quiet"], quiet)
        }
    }
}
