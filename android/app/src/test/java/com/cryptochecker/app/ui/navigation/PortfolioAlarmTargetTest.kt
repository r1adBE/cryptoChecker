package com.cryptochecker.app.ui.navigation

import com.cryptochecker.app.ui.OpenTargetGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Ziel der Mitteilung eines Portfolio-Alarms (wie iOS `PortfolioAlarmTarget`). */
class PortfolioAlarmTargetTest {

    @Test
    fun roundTrip() {
        val target = PortfolioAlarmTarget.of(7)
        assertEquals("portfolioAlarm/7", target)
        assertEquals(7L, PortfolioAlarmTarget.parse(target))
    }

    @Test
    fun otherOrBrokenTargetsAreIgnored() {
        assertNull(PortfolioAlarmTarget.parse(null))
        assertNull(PortfolioAlarmTarget.parse("portfolio"))
        assertNull(PortfolioAlarmTarget.parse("alarms"))
        assertNull(PortfolioAlarmTarget.parse("portfolioAlarm/"))
        assertNull(PortfolioAlarmTarget.parse("portfolioAlarm/x"))
        assertNull(PortfolioAlarmTarget.parse("portfolioAlarm/0"))
        assertNull(PortfolioAlarmTarget.parse("portfolioAlarm/-3"))
    }

    /** Nur mit dem Geheimnis der App: eine fremde App kann nicht in die Portfolio-Alarme springen. */
    @Test
    fun notPublic() {
        assertFalse(OpenTargetGuard.isPublic(PortfolioAlarmTarget.of(7)))
    }
}
