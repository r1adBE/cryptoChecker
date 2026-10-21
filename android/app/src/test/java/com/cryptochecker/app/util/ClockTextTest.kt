package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Uhrzeiten nach der 12/24-h-Einstellung des Systems ([ClockText]); die Einstellung wird hier eingesetzt. */
class ClockTextTest {

    private val us = Locale.US

    @Test
    fun englischAuf24Stunden() {
        assertEquals("HH:mm:ss", ClockText.pattern("h:mm:ss a", is24 = true, locale = us))
        assertEquals("HH:mm:ss", ClockText.pattern("h:mm:ss a", is24 = true, locale = us))
        assertEquals("M/d/yy, HH:mm", ClockText.pattern("M/d/yy, h:mm a", is24 = true, locale = us))
    }

    @Test
    fun deutschAuf12Stunden() {
        assertEquals("h:mm:ss a", ClockText.pattern("HH:mm:ss", is24 = false, locale = Locale.GERMANY))
        assertEquals("dd.MM.yy, h:mm a", ClockText.pattern("dd.MM.yy, HH:mm", is24 = false, locale = Locale.GERMANY))
        // Uhrzeit vor dem Datum (vi): Tageszeit direkt hinter die Uhrzeit, nicht ans Ende
        assertEquals("h:mm a, dd/MM/y", ClockText.pattern("HH:mm, dd/MM/y", is24 = false, locale = Locale.forLanguageTag("vi-VN")))
    }

    @Test
    fun tageszeitVorDerStundeInOstasien() {
        assertEquals("ah:mm:ss", ClockText.pattern("H:mm:ss", is24 = false, locale = Locale.JAPAN))
        assertEquals("a h:mm:ss", ClockText.pattern("HH:mm:ss", is24 = false, locale = Locale.KOREA))
        assertEquals("HH:mm:ss", ClockText.pattern("a h:mm:ss", is24 = true, locale = Locale.KOREA))
        assertEquals("HH:mm", ClockText.pattern("ah:mm", is24 = true, locale = Locale.CHINA))
    }

    @Test
    fun schonPassendBleibtUnveraendert() {
        assertEquals("HH:mm:ss", ClockText.pattern("HH:mm:ss", is24 = true, locale = Locale.GERMANY))
        assertEquals("h:mm:ss a", ClockText.pattern("h:mm:ss a", is24 = false, locale = us))
        // Text in Hochkommas (fr_CA «14 h 02») bleibt
        assertEquals("HH 'h' mm", ClockText.pattern("HH 'h' mm", is24 = true, locale = Locale.CANADA_FRENCH))
    }

    @Test
    fun formatMitEingesetzterEinstellung() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val millis = 14L * 3_600_000 + 2 * 60_000 + 11_000
        val f24 = ClockText.format(us, withSeconds = true, is24 = true).apply { timeZone = utc }
        assertEquals("14:02:11", f24.format(java.util.Date(millis)))
        val f12 = ClockText.format(Locale.GERMANY, withSeconds = false, is24 = false).apply { timeZone = utc }
        val twelve = f12.format(java.util.Date(millis))
        assertTrue(twelve, twelve.startsWith("2:02 ") && !twelve.contains("14"))
        // Ohne Einstellung: wie die Sprache
        val de = ClockText.format(Locale.GERMANY, withSeconds = true, is24 = null).apply { timeZone = utc }
        assertEquals("14:02:11", de.format(java.util.Date(millis)))
    }

    @Test
    fun ohneInstallUnbekannt() {
        assertNull(ClockText.systemIs24Hour())
        assertEquals("—", PriceFormat.time(0, is24 = true))
    }
}
