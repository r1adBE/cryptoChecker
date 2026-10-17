package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupRestorePolicy.AppLockDecision
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wie `BackupRestorePolicyTests.swift`: gleiche Fälle auf beiden Seiten. */
class BackupRestorePolicyTest {

    @Test
    fun unencryptedBackupNeverTurnsLockOff() {
        // Sperre an, Sicherung ohne Passwort will sie aus → bleibt an, Hinweis
        assertEquals(AppLockDecision(null, true), BackupRestorePolicy.appLock(false, current = true, encrypted = false, canAuthenticate = true))
        // Mit Passwort: aus wie gewünscht
        assertEquals(AppLockDecision(false, false), BackupRestorePolicy.appLock(false, current = true, encrypted = true, canAuthenticate = true))
        // Sperre schon aus: nichts zu schützen
        assertEquals(AppLockDecision(false, false), BackupRestorePolicy.appLock(false, current = false, encrypted = false, canAuthenticate = true))
    }

    @Test
    fun lockOnOnlyIfDeviceCanUnlock() {
        assertEquals(AppLockDecision(true, false), BackupRestorePolicy.appLock(true, current = false, encrypted = false, canAuthenticate = true))
        assertEquals(AppLockDecision(null, false), BackupRestorePolicy.appLock(true, current = false, encrypted = true, canAuthenticate = false))
        // Feld fehlt: unverändert
        assertEquals(AppLockDecision(null, false), BackupRestorePolicy.appLock(null, current = true, encrypted = false, canAuthenticate = true))
    }

    @Test
    fun noticeTotalSumsWithoutOverflow() {
        assertEquals(5, BackupRestorePolicy.noticeTotal(2, 3))
        assertEquals(3, BackupRestorePolicy.noticeTotal(-1, 3))
        assertEquals(Int.MAX_VALUE, BackupRestorePolicy.noticeTotal(Int.MAX_VALUE, 1))
    }

    @Test
    fun systemBackupCopyCountsInvalidEntries() {
        val text = """
            {"format":"cryptochecker-portfolio-system-backup","version":1,
             "portfolio":[
               {"id":1,"coin":"BTC","type":"BUY","amount":0.5,"amountText":"0.5","time":1},
               {"id":2,"coin":"ETH","type":"BUY","time":2},
               {"id":3,"coin":"ETH","type":"KAUFEN","amount":1,"time":3},
               "kein Objekt"
             ],
             "portfolioAlarms":[{"kind":"UNBEKANNT"}]}
        """.trimIndent()
        val content = PortfolioBackupMirror.parse(text)
        assertEquals(1, content.txs.size)
        assertEquals(0, content.alarms.size)
        assertEquals(4, content.invalid)
        // Kopie wie geschrieben: nichts ungültig
        val roundTrip = PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(content.txs, emptyList()))
        assertEquals(1, roundTrip.txs.size)
        assertEquals(0, roundTrip.invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun systemBackupCopyRejectsForeignFormat() {
        PortfolioBackupMirror.parse("""{"format":"anderes","portfolio":[]}""")
    }
}
