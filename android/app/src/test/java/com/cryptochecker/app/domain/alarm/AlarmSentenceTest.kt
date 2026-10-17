package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.alarm.AlarmSentence.Kind
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
