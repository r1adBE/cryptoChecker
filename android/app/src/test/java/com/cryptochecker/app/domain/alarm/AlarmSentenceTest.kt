package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.alarm.AlarmSentence.Kind
import com.cryptochecker.app.util.PercentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AlarmSentenceTest {

    private val price = { v: Double, c: String -> "${v.toLong()} $c" }

    private fun parts(kind: Kind, threshold: Double?, locale: Locale = Locale.US, hours: Int = 4) =
        AlarmSentence.parts(kind, "BTC", threshold, "CHF", hours, locale, price)

    @Test
    fun priceUsesAlarmCurrency() {
        assertEquals(AlarmSentence.Parts(Kind.ABOVE, "BTC", "60000 CHF", 4), parts(Kind.ABOVE, 60000.0))
        assertEquals("60000 CHF", parts(Kind.BELOW, 60000.0)?.value)
    }

    @Test
    fun percentFollowsLocale() {
        assertEquals("5%", parts(Kind.UP_PERCENT, 5.0, Locale.US)?.value)
        // Deutsch: Zahl und Prozentzeichen getrennt (geschütztes Leerzeichen)
        val de = parts(Kind.DOWN_PERCENT, 2.5, Locale.GERMANY)?.value.orEmpty()
        assertEquals("2,5 %", de.replace(' ', ' ').replace(' ', ' '))
        assertEquals("1.25%", parts(Kind.MOVE_PERCENT, 1.25, Locale.US)?.value)
    }

    /** Über PercentText statt getPercentInstance: gleiche Zeichen wie iOS (`PercentCallersTests`). */
    @Test
    fun prozentInDerSchreibweiseDerSprache() {
        assertEquals("%0,5", AlarmSentence.percent(0.5, Locale.forLanguageTag("tr-TR")))
        assertEquals("0,5\u00A0%", AlarmSentence.percent(0.5, Locale.GERMANY))
        assertEquals("0.5%", AlarmSentence.percent(0.5, Locale.US))
        assertEquals("0.5%", AlarmSentence.percent(0.5, Locale.forLanguageTag("de-CH")))
        // höchstens zwei Nachkommastellen, Nullen am Ende fallen weg, kaufmännisch gerundet
        assertEquals("5%", AlarmSentence.percent(5.0, Locale.US))
        assertEquals("1.26%", AlarmSentence.percent(1.255, Locale.US))
        assertEquals("%2,5", parts(Kind.UP_PERCENT, 2.5, Locale.forLanguageTag("tr-TR"))?.value)
    }

    @Test
    fun fundingUndVeraenderungMitEchtemMinus() {
        assertEquals("0.0125%", AlarmSentence.fundingPercent(0.0125, Locale.US))
        assertEquals("\u22120,0125\u00A0%", AlarmSentence.fundingPercent(-0.0125, Locale.GERMANY))
        assertEquals("\u2212%0,05", AlarmSentence.fundingPercent(-0.05, Locale.forLanguageTag("tr-TR")))
        // gerundet 0: nie «−0%»
        assertEquals("0%", AlarmSentence.fundingPercent(-0.00001, Locale.US))
        assertEquals("+%12,3", AlarmSentence.signedPercent(12.34, Locale.forLanguageTag("tr-TR")))
        assertEquals("\u22124\u00A0%", AlarmSentence.signedPercent(-4.0, Locale.GERMANY))
        assertEquals("0%", AlarmSentence.signedPercent(0.04, Locale.US))
    }

    /** Open-Interest-Schwellen bis 1000 %: mit Tausendertrennung wie `getPercentInstance` («1,000%»). */
    @Test
    fun alarmProzentMitTausendertrennung() {
        assertEquals("1,000%", AlarmSentence.percent(1000.0, Locale.US))
        assertEquals("1.000\u00A0%", AlarmSentence.percent(1000.0, Locale.GERMANY))
        assertEquals("%1.000", AlarmSentence.percent(1000.0, Locale.forLanguageTag("tr-TR")))
        assertEquals("1,000%", parts(Kind.OI_UP, 1000.0, Locale.US)?.value)
        assertEquals("+1,234.5%", AlarmSentence.signedPercent(1234.5, Locale.US))
        assertEquals("\u22121.500\u00A0%", AlarmSentence.fundingPercent(-1500.0, Locale.GERMANY))
        // ohne grouping (Standard) ohne Trennung
        assertEquals("1000%", PercentText.unsigned(1000.0, 2, Locale.US, minDecimals = 0))
    }

    @Test
    fun moveKeepsWindowHours() {
        assertEquals(12, parts(Kind.MOVE_PERCENT, 3.0, hours = 12)?.windowHours)
        assertNull(parts(Kind.MOVE_PERCENT, 3.0, hours = 0))
    }

    @Test
    fun volumeFactor() {
        assertEquals("3", parts(Kind.VOLUME, 3.0)?.value)
        assertEquals("4.3", parts(Kind.VOLUME, 4.25)?.value)
    }

    @Test
    fun incompleteInput() {
        assertNull(parts(Kind.ABOVE, null))
        assertNull(parts(Kind.ABOVE, 0.0))
        assertNull(parts(Kind.ABOVE, -1.0))
        assertNull(parts(Kind.UP_PERCENT, Double.NaN))
        assertNull(AlarmSentence.parts(Kind.ABOVE, " ", 1.0, "USD", 1, Locale.US, price))
    }
}
