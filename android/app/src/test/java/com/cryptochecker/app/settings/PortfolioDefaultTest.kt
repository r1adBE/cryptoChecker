package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Test

/** Portfolio-Tab ab Werk aus — wie `testPortfolioEnabledDefaults…` (iOS). */
class PortfolioDefaultTest {

    @Test
    fun portfolioTabOffForNewInstalls() {
        assertFalse(AppSettings().portfolioEnabled)
    }
}
