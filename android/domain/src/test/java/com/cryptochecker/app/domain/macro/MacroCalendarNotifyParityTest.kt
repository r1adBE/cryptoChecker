package com.cryptochecker.app.domain.macro

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Morgen-Meldung «Wirtschaftstermine» in der Zone der Termine (`testdata/parity/macro_notify.json`): 08:00 Ortszeit,
 * spätestens 08:00 New York, nie vor 05:00 – so kommen CPI, PPI, Arbeitsmarkt und PCE (08:30 New York) auch in Chicago,
 * Denver und Los Angeles in die Meldung. Gegenstück: `Tests/MacroNotifyParityTests.swift`.
 */
class MacroCalendarNotifyParityTest {

    private val fixture = ParityJson.load("macro_notify.json")

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun zeitpunktJeOrtstag() {
        val cases = fixture["notifyAt"].list().map { it.obj() }
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val zone = ZoneId.of(c["zone"] as String)
            val day = LocalDate.parse(c["day"] as String)
            assertEquals("${c["zone"]} ${c["day"]}", t(c["expected"] as String), MacroCalendar.notifyAtOnDay(day, zone))
        }
    }

    @Test
    fun fensterUndNaechsterLauf() {
        for (c in fixture["window"].list().map { it.obj() }) {
            val zone = ZoneId.of(c["zone"] as String)
            assertEquals("${c["zone"]} ${c["now"]}", c["expected"] as Boolean, MacroCalendar.isNotifyWindow(t(c["now"] as String), zone))
        }
        for (c in fixture["next"].list().map { it.obj() }) {
            val zone = ZoneId.of(c["zone"] as String)
            assertEquals("${c["zone"]} ${c["now"]}", t(c["expected"] as String), MacroCalendar.nextNotifyAt(t(c["now"] as String), zone))
        }
    }

    @Test
    fun termineDerMeldung() {
        for (c in fixture["events"].list().map { it.obj() }) {
            val zone = ZoneId.of(c["zone"] as String)
            val at = MacroCalendar.notifyAtOnDay(LocalDate.parse(c["day"] as String), zone)
            val events = c["events"].list().map { MacroEvent(MacroEventType.CPI, t(it as String)) }
            val expected = c["expected"].list().map { t(it as String) }
            assertEquals(c["note"] as String, expected, MacroCalendar.notificationEvents(events, at, zone).map { it.time })
        }
    }
}
