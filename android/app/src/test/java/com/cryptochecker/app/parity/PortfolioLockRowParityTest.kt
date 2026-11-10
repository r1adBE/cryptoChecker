package com.cryptochecker.app.parity

import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.settings.SettingsSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zeile «Portfolio-Sperre» und Kurzwert «Portfolio» (`testdata/parity/portfolio_lock_row.json`):
 * [PortfolioLockPolicy.showSetting] und [SettingsSummary.portfolio]. Gegenstück: `Tests/PortfolioLockRowParityTests.swift`.
 */
class PortfolioLockRowParityTest {

    private val data = ParityJson.load("portfolio_lock_row.json")

    private fun summaryName(p: SettingsSummary.Portfolio): String = when (p) {
        SettingsSummary.Portfolio.OFF -> "off"
        SettingsSummary.Portfolio.OFF_LOCKED -> "offLocked"
        SettingsSummary.Portfolio.ON -> "on"
        SettingsSummary.Portfolio.LOCKED -> "locked"
    }

    @Test
    fun zeileUndKurzwertWieGemeinsameTestfaelle() {
        val cases = data["cases"].list()
        assertEquals(4, cases.size)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val enabled = c["portfolioEnabled"] as Boolean
            val lock = c["lock"] as Boolean
            assertEquals(name, c["showSetting"] as Boolean, PortfolioLockPolicy.showSetting(enabled, lock))
            assertEquals(name, c["summary"] as String, summaryName(SettingsSummary.portfolio(enabled, lock)))
        }
    }
}
