package com.cryptochecker.app.domain.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alarme nicht auf einem laut Börse über 2 Stunden alten Kurs; ohne Börsenzeit wie bisher. Wie iOS. */
class AlarmFreshnessTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun withoutExchangeTimeNothingChanges() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(null, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(0L, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(-5L, now))
    }

    @Test
    fun oldExchangeTimeHoldsAlarms() {
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour - 1, now))
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 26 * hour, now))
    }

    @Test
    fun recentOrFutureExchangeTimeFires() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 5 * 60_000L, now))
        // Uhr der Börse etwas voraus: kein Grund zu schweigen
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now + 60_000L, now))
    }
}
