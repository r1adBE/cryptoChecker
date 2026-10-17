package com.cryptochecker.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/** Alarm-Ansage: Kurs in der Alarm-Währung nennt diese Währung ([SpokenText.spokenAlarmPrice]). */
class SpokenAlarmPriceTest {

    @Test
    fun currencyOnlyWhenItDiffersFromTheQuote() {
        assertEquals("60000", SpokenText.spokenAlarmPrice("60000", "USDT", null))
        assertEquals("60000", SpokenText.spokenAlarmPrice("60000", "USDT", "usdt"))
        assertEquals("60000 CHF", SpokenText.spokenAlarmPrice("60000", "USDT", "chf"))
        assertEquals("60000", SpokenText.spokenAlarmPrice("60000", "USDT", " "))
    }
}
