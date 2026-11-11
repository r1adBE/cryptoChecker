package com.cryptochecker.app.parity

import com.cryptochecker.app.lock.PortfolioLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sicherung mit Portfolio-Daten (`testdata/parity/backup_portfolio_data.json`): Transaktionen, beobachtete Adressen
 * und Portfolio-Alarme zählen gleich ([PortfolioLockPolicy.backupHasPortfolioData]) — dann Passwort Pflicht und
 * Entsperren vor dem Sichern. Gegenstück: `Tests/BackupPortfolioDataParityTests.swift`.
 */
class BackupPortfolioDataParityTest {

    private val data = ParityJson.load("backup_portfolio_data.json")

    @Test
    fun portfolioDatenWieGemeinsameTestfaelle() {
        val cases = data["cases"].list()
        assertEquals(6, cases.size)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val has = PortfolioLockPolicy.backupHasPortfolioData(
                transactions = (c["transactions"] as Number).toInt(),
                watchedAddresses = (c["watchedAddresses"] as Number).toInt(),
                portfolioAlarms = (c["portfolioAlarms"] as Number).toInt(),
            )
            assertEquals(name, c["hasPortfolioData"] as Boolean, has)
            assertEquals(name, has, PortfolioLockPolicy.backupExportRequiresPassword(has))
            assertEquals(
                name, c["allowedWithoutPassword"] as Boolean,
                PortfolioLockPolicy.backupExportAllowed(hasPassword = false, hasPortfolioData = has),
            )
            assertTrue(name, PortfolioLockPolicy.backupExportAllowed(hasPassword = true, hasPortfolioData = has))
            assertEquals(
                name, c["needsUnlockWhenLocked"] as Boolean,
                PortfolioLockPolicy.backupExportNeedsUnlock(locked = true, hasPortfolioData = has),
            )
            assertFalse(name, PortfolioLockPolicy.backupExportNeedsUnlock(locked = false, hasPortfolioData = has))
        }
    }
}
