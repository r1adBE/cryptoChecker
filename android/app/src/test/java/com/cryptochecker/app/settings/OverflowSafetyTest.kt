package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.refresh.OutdatedRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Riesige Werte aus einer Sicherung werden gekürzt (Setter und Lesen der Einstellungen, [AppSettings.clampBackgroundInterval]
 * usw.); gleiche Grenzen wie iOS (`OverflowSafetyTests.swift`).
 */
class OverflowSafetyTest {

    @Test
    fun `huge settings are clamped to the maxima`() {
        assertEquals(AppSettings.MAX_BACKGROUND_INTERVAL_MINUTES, AppSettings.clampBackgroundInterval(Int.MAX_VALUE))
        assertEquals(AppSettings.MAX_ALARM_COOLDOWN_MINUTES, AppSettings.clampAlarmCooldown(Int.MAX_VALUE))
        assertEquals(1_440, AppSettings.MAX_BACKGROUND_INTERVAL_MINUTES)
        assertEquals(1_440, AppSettings.MAX_ALARM_COOLDOWN_MINUTES)
    }

    @Test
    fun `lower bounds stay, choices are kept`() {
        assertEquals(AppSettings.MIN_BACKGROUND_INTERVAL_MINUTES, AppSettings.clampBackgroundInterval(1))
        assertEquals(0, AppSettings.clampAlarmCooldown(-5))
        AppSettings.BACKGROUND_INTERVAL_CHOICES.forEach { assertEquals(it, AppSettings.clampBackgroundInterval(it)) }
        AppSettings.ALARM_COOLDOWN_CHOICES.forEach { assertEquals(it, AppSettings.clampAlarmCooldown(it)) }
    }

    @Test
    fun `time rules stay positive at the maxima`() {
        assertTrue(
            OutdatedRule.afterMillis(AppSettings.clampBackgroundInterval(Int.MAX_VALUE)) > 0
        )
    }
}
