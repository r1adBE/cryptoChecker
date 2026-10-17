package com.cryptochecker.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portfolio-Tab ab Build 44 für neue Installationen ab Werk aus, bestehende behalten «an»
 * (InstallDefaultsMigration, siehe InstallDefaultsTest) — wie `testPortfolioEnabledDefaults…` (iOS).
 */
class PortfolioDefaultTest {

    @Test
    fun portfolioTabOffForNewInstalls() {
        assertFalse(AppSettings().portfolioEnabled)
        assertTrue(AppSettings.LEGACY_PORTFOLIO_ENABLED)
    }
}
