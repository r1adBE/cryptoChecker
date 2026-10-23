package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portfolio-Sperre bei unlesbaren Einstellungen «unbekannt → an» ([SettingsLockFallback]) statt still
 * aus (Standardwert) — gleiche Regel wie iOS `SharedStorage.LoadedSettings.appLockAssumed`.
 */
class SettingsLockFallbackTest {

    @Test
    fun unbekannteSperreGiltAlsAn() {
        val reset = AppSettings()
        assertFalse(reset.appLock)
        assertTrue(SettingsLockFallback.effective(reset, lockUnknown = true).appLock)
        // Übrige Einstellungen bleiben die Standardwerte
        assertEquals(reset.copy(appLock = true), SettingsLockFallback.effective(reset, lockUnknown = true))
    }

    @Test
    fun bekannteSperreBleibtWieGespeichert() {
        assertFalse(SettingsLockFallback.effective(AppSettings(appLock = false), lockUnknown = false).appLock)
        assertTrue(SettingsLockFallback.effective(AppSettings(appLock = true), lockUnknown = false).appLock)
        assertTrue(SettingsLockFallback.effective(AppSettings(appLock = true), lockUnknown = true).appLock)
    }

    @Test
    fun lesefehlerLiefertStandardwerteMitSperre() {
        val read = appSettingsOf(SettingsLockFallback.unreadable())
        assertTrue(read.appLock)
        // Sonst Standardwerte (Stichproben)
        val defaults = AppSettings()
        assertEquals(defaults.backgroundIntervalMinutes, read.backgroundIntervalMinutes)
        assertEquals(defaults.portfolioEnabled, read.portfolioEnabled)
        assertEquals(defaults.hidePortfolioAmounts, read.hidePortfolioAmounts)
    }
}
