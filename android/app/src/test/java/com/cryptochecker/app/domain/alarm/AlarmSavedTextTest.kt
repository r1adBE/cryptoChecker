package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Bestätigung nach dem Speichern eines Alarms — gleiche Fälle wie `AlarmLogicTests.testSavedText` (iOS). */
class AlarmSavedTextTest {

    private val de = Locale.GERMAN

    @Test
    fun conditionFrequencyAndNotificationsOff() {
        val condition = AlarmSavedText.pairCondition("BTC", "Kurs über 70’000 USDT")
        assertEquals("BTC Kurs über 70’000 USDT", condition)
        assertEquals("BTC Kurs über 70’000 USDT · einmalig", AlarmSavedText.detail(condition, "Einmalig", null, de))
        assertEquals(
            "BTC Kurs über 70’000 USDT · wiederholend · Mitteilungen aus",
            AlarmSavedText.detail(condition, "Wiederholend", "Mitteilungen aus", de),
        )
        // Portfolio-Alarm: fertige Bedingung
        assertEquals("Portfolio über 50’000.00 CHF · once", AlarmSavedText.detail("Portfolio über 50’000.00 CHF", "Once", null, Locale.ENGLISH))
    }

    @Test
    fun emptyPartsAreSkipped() {
        assertEquals("Kurs über 70’000", AlarmSavedText.pairCondition(" ", " Kurs über 70’000 "))
        assertEquals("einmalig", AlarmSavedText.detail(" ", "Einmalig", " ", de))
        assertEquals("BTC", AlarmSavedText.detail("BTC", "", null, de))
    }
}
