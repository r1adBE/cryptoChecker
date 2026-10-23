package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupRestorePolicy.AppLockDecision
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Nur Android: Eine Datenbank leer im Arbeitsspeicher gibt es unter iOS nicht. */
    @Test
    fun noRollBackIntoInMemoryDatabase() {
        assertTrue(BackupRestorePolicy.mayRollBackInto(storedKept = false, inMemory = false))
        // Gespeicherter Stand gerade nicht lesbar bzw. nur im Arbeitsspeicher: Journal behalten
        assertFalse(BackupRestorePolicy.mayRollBackInto(storedKept = true, inMemory = true))
        assertFalse(BackupRestorePolicy.mayRollBackInto(storedKept = false, inMemory = true))
    }

    /** Nur Android: Einspielen in eine Datenbank nur im Arbeitsspeicher meldete Erfolg, nach dem Neustart war alles weg. */
    @Test
    fun noRestoreIntoInMemoryDatabase() {
        val kept = BackupRestorePolicy.StorageRefusal.KEPT
        val inMemory = BackupRestorePolicy.StorageRefusal.IN_MEMORY
        // Gespeicherte Datei normal geöffnet: Sichern und Einspielen
        assertEquals(null, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = false, restoring = false))
        assertEquals(null, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = false, restoring = true))
        // Gespeicherter Stand gerade nicht lesbar: weder Sichern noch Einspielen
        assertEquals(kept, BackupRestorePolicy.storageRefusal(storedKept = true, inMemory = true, restoring = false))
        assertEquals(kept, BackupRestorePolicy.storageRefusal(storedKept = true, inMemory = true, restoring = true))
        // Kein Schlüssel (neu oder Kopie einer alten unverschlüsselten Datei): Sichern ja, Einspielen nein
        assertEquals(null, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = true, restoring = false))
        assertEquals(inMemory, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = true, restoring = true))
    }

    /** Ohne Passwort: «Portfolio in Systemsicherung» nur aus, nie ein (wie iOS). */
    @Test
    fun unverschluesseltSchaltetSystemsicherungNieEin() {
        assertEquals(null, BackupRestorePolicy.systemBackup(wanted = true, current = false, trusted = false))
        assertEquals(false, BackupRestorePolicy.systemBackup(wanted = false, current = true, trusted = false))
        assertEquals(true, BackupRestorePolicy.systemBackup(wanted = true, current = true, trusted = false))
        assertEquals(null, BackupRestorePolicy.systemBackup(wanted = null, current = true, trusted = false))
        // Mit Passwort (oder eigener Stand beim Zurückrollen): wie gesichert
        assertEquals(true, BackupRestorePolicy.systemBackup(wanted = true, current = false, trusted = true))
        assertEquals(false, BackupRestorePolicy.systemBackup(wanted = false, current = true, trusted = true))
    }

    /** Ohne Passwort: «Beträge verbergen» nur ein, nie aus (wie iOS). */
    @Test
    fun unverschluesseltSchaltetBetraegeVerbergenNieAus() {
        assertEquals(null, BackupRestorePolicy.hideAmounts(wanted = false, current = true, trusted = false))
        assertEquals(true, BackupRestorePolicy.hideAmounts(wanted = true, current = false, trusted = false))
        assertEquals(false, BackupRestorePolicy.hideAmounts(wanted = false, current = false, trusted = false))
        assertEquals(null, BackupRestorePolicy.hideAmounts(wanted = null, current = false, trusted = false))
        assertEquals(false, BackupRestorePolicy.hideAmounts(wanted = false, current = true, trusted = true))
        assertEquals(true, BackupRestorePolicy.hideAmounts(wanted = true, current = false, trusted = true))
    }
}
