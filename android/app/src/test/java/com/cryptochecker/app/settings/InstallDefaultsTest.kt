package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standards einer neuen Installation und Merker «Portfolio-Daten prüfen» — wie `InstallDefaultsTests` (iOS). */
class InstallDefaultsTest {

    @Test
    fun newDefaults() {
        val d = AppSettings()
        assertTrue(d.watchlistNames)
        assertTrue(d.showConverted)
        assertFalse(d.includeAlphaTokens)
        assertFalse(d.portfolioEnabled)
        assertFalse(d.ongoingNotifications)
        assertEquals(UpdateMode.DEFAULT, d.updateMode)
    }

    @Test
    fun keyNames() {
        assertEquals("portfolio_enabled", SettingsRepository.KEY_PORTFOLIO_ENABLED)
        assertEquals("portfolio_data_check", SettingsRepository.KEY_PORTFOLIO_DATA_CHECK)
    }

    /**
     * Neu angelegte Einstellungen (z. B. beschädigt und durch leere ersetzt) bei vorhandenem Portfolio: Portfolio aus
     * mit Merker, die Prüfung beim Start schaltet das Portfolio wieder ein.
     */
    @Test
    fun resetSettingsWithPortfolioData() {
        assertTrue(AppSettings.enablePortfolioForData(resetDefaults = true, portfolioEnabled = false, hasPortfolioData = true))
        // Ohne Daten (echte neue Installation): bleibt aus
        assertFalse(AppSettings.enablePortfolioForData(resetDefaults = true, portfolioEnabled = false, hasPortfolioData = false))
        // Kein Merker oder schon an: nichts zu tun
        assertFalse(AppSettings.enablePortfolioForData(resetDefaults = false, portfolioEnabled = false, hasPortfolioData = true))
        assertFalse(AppSettings.enablePortfolioForData(resetDefaults = true, portfolioEnabled = true, hasPortfolioData = true))
    }
}
