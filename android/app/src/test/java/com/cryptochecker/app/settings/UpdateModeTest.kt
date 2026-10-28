package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Wie aktuell?»: Standard, gespeicherter Name und abgeleitete Schalter — wie iOS `UpdateModeTests`. */
class UpdateModeTest {

    @Test
    fun defaultIsSparsam() {
        assertEquals(UpdateMode.SPARSAM, UpdateMode.DEFAULT)
        assertEquals(UpdateMode.SPARSAM, AppSettings().updateMode)
    }

    @Test
    fun selectable() {
        assertEquals(listOf(UpdateMode.SPARSAM, UpdateMode.LIVE_OFFEN), UpdateMode.selectable)
    }

    @Test
    fun fromName_trimmedCaseInsensitive_unknownNull() {
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromNameOrNull("sparsam"))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromNameOrNull(" LIVE_OFFEN "))
        assertNull(UpdateMode.fromNameOrNull("TURBO"))
        assertNull(UpdateMode.fromNameOrNull(""))
        assertNull(UpdateMode.fromNameOrNull(null))
    }

    @Test
    fun derivedSwitches() {
        assertFalse(AppSettings(updateMode = UpdateMode.SPARSAM).liveWebSocket)
        assertTrue(AppSettings(updateMode = UpdateMode.LIVE_OFFEN).liveWebSocket)
    }

    /** «Kurs vorlesen» wirkt nur mit Sprachausgabe an und «Nur Alarme» aus (Standard: beides nicht). */
    @Test
    fun speaksPairPricesNeedsSpeechWithoutAlarmsOnly() {
        assertFalse(AppSettings().speaksPairPrices)
        assertFalse(AppSettings(ttsEnabled = true, ttsAlarmsOnly = true).speaksPairPrices)
        assertFalse(AppSettings(ttsEnabled = false, ttsAlarmsOnly = false).speaksPairPrices)
        assertTrue(AppSettings(ttsEnabled = true, ttsAlarmsOnly = false).speaksPairPrices)
    }
}
