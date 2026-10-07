package com.cryptochecker.app.domain.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MacroCalendarTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val hour = 3_600_000L

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    private val sample = """
        {"version":1,"generated":"2026-10-06T07:00:00Z","source":"BLS, Federal Reserve, BEA",
         "events":[
           {"type":"CPI","time":"2026-10-14T12:30:00Z"},
           {"type":"FOMC","time":"2026-10-28T18:00:00Z"},
           {"type":"NFP","time":"2026-11-06T13:30:00Z"},
           {"type":"GDP","time":"2026-10-29T12:30:00Z"},
           {"type":"PPI","time":"not a time"},
           {"type":"cpi","time":"2026-10-14T12:30:00Z"},
           {"type":"PCE","time":"2026-10-01T12:30:00Z"},
           {"time":"2026-10-15T12:30:00Z"},
           "x"
         ]}
    """.trimIndent()

    @Test
    fun parse_knownTypesOnly_sorted_distinct_recentOnly() {
        val events = MacroCalendar.parse(sample, now = t("2026-10-06T08:00:00Z"))!!
        assertEquals(
            listOf(
                MacroEvent(MacroEventType.CPI, t("2026-10-14T12:30:00Z")),
                MacroEvent(MacroEventType.FOMC, t("2026-10-28T18:00:00Z")),
                MacroEvent(MacroEventType.NFP, t("2026-11-06T13:30:00Z")),
            ),
            events
        )
    }

    @Test
    fun parse_keepsEventsUpToTwoDaysOld() {
        val events = MacroCalendar.parse(sample, now = t("2026-10-03T12:00:00Z"))!!
        assertEquals(MacroEventType.PCE, events.first().type)
        val later = MacroCalendar.parse(sample, now = t("2026-10-03T12:30:01Z"))!!
        assertTrue(later.none { it.type == MacroEventType.PCE })
    }

    @Test
    fun parse_invalid_returnsNull() {
        assertNull(MacroCalendar.parse("", 0L))
        assertNull(MacroCalendar.parse("[]", 0L))
        assertNull(MacroCalendar.parse("{\"events\":[]}", 0L))
        assertNull(MacroCalendar.parse("{\"version\":1}", 0L))
        assertNull(MacroCalendar.parse("{\"version\":1,\"events\":[", 0L))
        assertNull(MacroCalendar.parse("{\"version\":1,\"events\":[]} x", 0L))
        assertEquals(emptyList<MacroEvent>(), MacroCalendar.parse("{\"version\":1,\"events\":[]}", 0L))
    }

    @Test
    fun parse_offsetsAndEscapes() {
        val json = """{"version":2,"source":"a\"bé","events":[{"type":"FOMC","time":"2026-12-09T14:00:00-05:00"}]}"""
        val events = MacroCalendar.parse(json, now = 0L)!!
        assertEquals(t("2026-12-09T19:00:00Z"), events.single().time)
    }

    @Test
    fun hint_todayUpcoming_thenReleasedAfterTwoHours_thenGoneNextDay() {
        val cpi = MacroEvent(MacroEventType.CPI, t("2026-10-14T12:30:00Z")) // 14:30 in Zürich
        val events = listOf(cpi)
        // Morgens: heute, bevorstehend
        val morning = MacroCalendar.hint(events, t("2026-10-14T06:00:00Z"), zurich)!!
        assertFalse(morning.released)
        assertTrue(morning.allToday)
        // Kurz nach dem Termin (unter 2 h): noch «bevorstehend»
        assertFalse(MacroCalendar.hint(events, t("2026-10-14T14:29:00Z"), zurich)!!.released)
        // Ab 2 h danach: «veröffentlicht», bis Mitternacht
        assertTrue(MacroCalendar.hint(events, t("2026-10-14T14:30:00Z"), zurich)!!.released)
        assertTrue(MacroCalendar.hint(events, t("2026-10-14T21:59:00Z"), zurich)!!.released)
        // Nächster Tag (Zürich): weg
        assertNull(MacroCalendar.hint(events, t("2026-10-14T22:00:00Z"), zurich))
    }

    @Test
    fun hint_tomorrowWithinEighteenHours() {
        // In Auckland liegt 12:30 UTC am 15.10. um 01:30
        val cpi = MacroEvent(MacroEventType.CPI, t("2026-10-14T12:30:00Z"))
        // 14.10. 08:00 Auckland = 13.10. 19:00 UTC → 17.5 h vorher
        val hint = MacroCalendar.hint(listOf(cpi), t("2026-10-13T19:00:00Z"), auckland)!!
        assertTrue(hint.allTomorrow)
        assertFalse(hint.released)
        // 19 h vorher: noch kein Hinweis
        assertNull(MacroCalendar.hint(listOf(cpi), t("2026-10-13T17:29:00Z"), auckland))
    }

    @Test
    fun hint_severalSameDay_oneHint_inOrder() {
        val nfp = MacroEvent(MacroEventType.NFP, t("2026-11-06T13:30:00Z"))
        val fomc = MacroEvent(MacroEventType.FOMC, t("2026-11-06T19:00:00Z"))
        val hint = MacroCalendar.hint(listOf(fomc, nfp), t("2026-11-06T07:00:00Z"), zurich)!!
        assertEquals(listOf(nfp, fomc), hint.items.map { it.event })
        // Erster vorbei, zweiter noch nicht: weiterhin «bevorstehend»
        assertFalse(MacroCalendar.hint(listOf(fomc, nfp), t("2026-11-06T16:00:00Z"), zurich)!!.released)
        assertTrue(MacroCalendar.hint(listOf(fomc, nfp), t("2026-11-06T21:00:00Z"), zurich)!!.released)
    }

    @Test
    fun hint_mixedTodayAndTomorrow() {
        val late = MacroEvent(MacroEventType.FOMC, t("2026-10-28T20:00:00Z")) // 21:00 Zürich
        val early = MacroEvent(MacroEventType.PCE, t("2026-10-29T07:30:00Z")) // 08:30 Zürich, nächster Tag
        val hint = MacroCalendar.hint(listOf(late, early), t("2026-10-28T18:00:00Z"), zurich)!!
        assertFalse(hint.allToday)
        assertFalse(hint.allTomorrow)
        assertEquals(listOf(false, true), hint.items.map { it.tomorrow })
    }

    @Test
    fun hint_nothingNear_null() {
        val far = MacroEvent(MacroEventType.CPI, t("2026-10-20T12:30:00Z"))
        assertNull(MacroCalendar.hint(listOf(far), t("2026-10-14T06:00:00Z"), zurich))
        assertNull(MacroCalendar.hint(emptyList(), t("2026-10-14T06:00:00Z"), zurich))
    }

    @Test
    fun notificationEvents_todayAndUpcomingOnly() {
        val past = MacroEvent(MacroEventType.PPI, t("2026-10-14T05:00:00Z"))
        val cpi = MacroEvent(MacroEventType.CPI, t("2026-10-14T12:30:00Z"))
        val tomorrow = MacroEvent(MacroEventType.NFP, t("2026-10-15T12:30:00Z"))
        val now = t("2026-10-14T06:00:00Z") // 08:00 Zürich
        assertEquals(listOf(cpi), MacroCalendar.notificationEvents(listOf(tomorrow, cpi, past), now, zurich))
    }

    @Test
    fun nextNotifyAt_eightLocal() {
        // 07:59 Zürich → heute 08:00
        assertEquals(t("2026-10-14T06:00:00Z"), MacroCalendar.nextNotifyAt(t("2026-10-14T05:59:00Z"), zurich))
        // genau 08:00 → morgen
        assertEquals(t("2026-10-15T06:00:00Z"), MacroCalendar.nextNotifyAt(t("2026-10-14T06:00:00Z"), zurich))
        // Zeitumstellung (25.10.2026, Ende Sommerzeit): 08:00 MEZ = 07:00 UTC
        assertEquals(t("2026-10-25T07:00:00Z"), MacroCalendar.nextNotifyAt(t("2026-10-24T12:00:00Z"), zurich))
    }

    @Test
    fun notifyWindow_eightToNoon() {
        assertFalse(MacroCalendar.isNotifyWindow(7 * 60 + 59))
        assertTrue(MacroCalendar.isNotifyWindow(8 * 60))
        assertTrue(MacroCalendar.isNotifyWindow(11 * 60 + 59))
        assertFalse(MacroCalendar.isNotifyWindow(12 * 60))
    }

    @Test
    fun freshness() {
        val now = 10 * 24 * hour
        assertTrue(MacroCalendar.isFresh(now - 23 * hour, now))
        assertFalse(MacroCalendar.isFresh(now - 24 * hour, now))
        assertFalse(MacroCalendar.isFresh(now + 1, now))
        assertFalse(MacroCalendar.isFresh(0L, now))
    }

    @Test
    fun miniJson_basics() {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse("""{"a":[1,-2.5e1,true,false,null,"x\n"],"b":{}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, -25.0, true, false, null, "x\n"), o["a"])
        assertNotNull(o["b"])
    }
}
