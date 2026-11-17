package com.cryptochecker.app.domain.settings

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Darstellung «Nach Uhrzeit» als gemeinsame Testfälle (`testdata/parity/theme_schedule.json`, Kopie in iOS
 * `Tests/Parity/`, gelesen von `ThemeScheduleParityTests.swift`): Zeitraum über Mitternacht, gleiche und ungültige Zeiten,
 * Vorrang vor fest Hell/Dunkel und der nächste Wechsel über die Sommerzeit.
 */
class ThemeScheduleParityTest {

    private val data = ParityJson.load("theme_schedule.json")

    @Test
    fun isDarkAt() {
        data["isDark"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val dark = ThemeSchedule.isDarkAt(
                c["darkFrom"].num()!!.toInt(),
                c["lightFrom"].num()!!.toInt(),
                c["minute"].num()!!.toInt()
            )
            assertEquals(c["name"] as String, c["dark"], dark)
        }
    }

    @Test
    fun forcedDark() {
        data["forced"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val forced = ThemeSchedule.forcedDark(
                c["darkMode"] as Boolean?,
                c["scheduled"] as Boolean,
                c["darkFrom"].num()!!.toInt(),
                c["lightFrom"].num()!!.toInt(),
                c["minute"].num()!!.toInt()
            )
            assertEquals(c["name"] as String, c["forced"], forced)
        }
    }

    @Test
    fun nextSwitch() {
        data["nextSwitch"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val now = c["nowMillis"].num()!!.toLong()
            assertEquals("$name: now", Instant.parse(c["now"] as String).toEpochMilli(), now)
            val next = ThemeSchedule.nextSwitchAt(
                c["darkFrom"].num()!!.toInt(),
                c["lightFrom"].num()!!.toInt(),
                now,
                TimeZone.getTimeZone(c["zone"] as String)
            )
            assertEquals(name, c["nextMillis"].num()?.toLong(), next)
            (c["next"] as String?)?.let { assertEquals("$name: next", Instant.parse(it).toEpochMilli(), next) }
        }
    }

    @Test
    fun withoutScheduleNoSwitch() {
        assertEquals(null, ThemeSchedule.nextSwitchAt(AppSettings(themeScheduled = false), 0L))
    }
}
