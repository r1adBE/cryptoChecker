package com.cryptochecker.app.data

import com.cryptochecker.app.data.BackupRestorePolicy.AppLockDecision
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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
    }

    @Test
    fun noticeTotalSumsWithoutOverflow() {
        assertEquals(5, BackupRestorePolicy.noticeTotal(2, 3))
        assertEquals(3, BackupRestorePolicy.noticeTotal(-1, 3))
        assertEquals(Int.MAX_VALUE, BackupRestorePolicy.noticeTotal(Int.MAX_VALUE, 1))
    }

    @Test
    fun systemBackupCopyCountsInvalidEntries() {
        val btc = PortfolioTxEntity(id = 1, coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal("0.5"), time = 1)
        val alarm = PortfolioAlarmEntity(id = 1, kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0)
        val root = JSONObject(PortfolioBackupMirror.encode(listOf(btc, btc.copy(id = 2, coin = "ETH"), btc.copy(id = 3)), listOf(alarm)))
        root.getJSONArray("portfolio").getJSONObject(1).put("amountText", "0")
        root.getJSONArray("portfolio").getJSONObject(2).put("type", "KAUFEN")
        root.getJSONArray("portfolioAlarms").getJSONObject(0).put("kind", "UNBEKANNT")
        val content = PortfolioBackupMirror.parse(root.toString())
        assertEquals(1, content.txs.size)
        assertEquals(0, content.alarms.size)
        assertEquals(3, content.invalid)
        // Kopie wie geschrieben: nichts ungültig
        val roundTrip = PortfolioBackupMirror.parse(PortfolioBackupMirror.encode(content.txs, emptyList()))
        assertEquals(1, roundTrip.txs.size)
        assertEquals(0, roundTrip.invalid)
        // Fehlt ein Feld eines Eintrags: Kopie unlesbar
        root.getJSONArray("portfolio").getJSONObject(0).remove("time")
        assertTrue(runCatching { PortfolioBackupMirror.parse(root.toString()) }.exceptionOrNull() is IllegalArgumentException)
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
        // Kein Schlüssel (nur im Arbeitsspeicher): Sichern ja, Einspielen nein
        assertEquals(null, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = true, restoring = false))
        assertEquals(inMemory, BackupRestorePolicy.storageRefusal(storedKept = false, inMemory = true, restoring = true))
    }

    /** Ohne Passwort: «Portfolio in Systemsicherung» nur aus, nie ein (wie iOS). */
    @Test
    fun unverschluesseltSchaltetSystemsicherungNieEin() {
        assertEquals(null, BackupRestorePolicy.systemBackup(wanted = true, current = false, trusted = false))
        assertEquals(false, BackupRestorePolicy.systemBackup(wanted = false, current = true, trusted = false))
        assertEquals(true, BackupRestorePolicy.systemBackup(wanted = true, current = true, trusted = false))
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
        assertEquals(false, BackupRestorePolicy.hideAmounts(wanted = false, current = true, trusted = true))
        assertEquals(true, BackupRestorePolicy.hideAmounts(wanted = true, current = false, trusted = true))
    }

    /** Mit Passwort neu gesetzter bzw. ersetzter Server wird mit Hostnamen genannt — ohne Passwort bleibt es bei KEPT. */
    @Test
    fun neuGesetzterServerWirdMitHostGenannt() {
        val set = BackupRestorePolicy.AddressServerNote.SET
        assertEquals(set, BackupRestorePolicy.addressServerNote("https://node.example.org:3006/api", null, trusted = true))
        assertEquals("node.example.org", BackupRestorePolicy.addressServerHost("https://node.example.org:3006/api", null, trusted = true))
        assertEquals(null, BackupRestorePolicy.addressServerHost("https://node.example.org/api", null, trusted = false))
        // Ersetzt: ebenfalls mit Hostnamen (wohin die Adressen ab jetzt gehen)
        assertEquals("b.example", BackupRestorePolicy.addressServerHost("https://b.example", "https://a.example", trusted = true))
        assertEquals(null, BackupRestorePolicy.addressServerHost("https://b.example", "https://a.example", trusted = false))
        assertEquals(null, BackupRestorePolicy.addressServerHost(null, "https://a.example", trusted = true))
        assertEquals(null, BackupRestorePolicy.addressServerHost("http://b.example", null, trusted = true))
        assertEquals(null, BackupRestorePolicy.addressServerHost(null, null, trusted = true))
    }

    /** Mit Passwort abgeschalteter Schutz wird genannt; ohne Passwort wird nichts abgeschaltet, also nichts genannt. */
    @Test
    fun abgeschalteterSchutzNieStill() {
        fun lowered(
            sys: Boolean, sysNow: Boolean, hide: Boolean, hideNow: Boolean, trusted: Boolean,
            lock: Boolean = false, lockNow: Boolean = false,
        ) = BackupRestorePolicy.loweredProtection(sys, sysNow, hide, hideNow, trusted, wantedAppLock = lock, currentAppLock = lockNow)
        assertEquals(BackupRestorePolicy.LoweredProtection(true, true), lowered(true, false, false, true, trusted = true))
        assertEquals(BackupRestorePolicy.LoweredProtection(), lowered(true, false, false, true, trusted = false))
        // Schon so eingestellt bzw. Richtung «mehr Schutz»: kein Hinweis
        assertEquals(false, lowered(true, true, false, false, trusted = true).any)
        assertEquals(false, lowered(false, true, true, false, trusted = true).any)
        // Portfolio-Sperre: mit Passwort ausgeschaltet → genannt; ohne Passwort bleibt sie an (appLockKept), nichts genannt
        assertEquals(BackupRestorePolicy.LoweredProtection(lockOff = true), lowered(false, false, true, true, trusted = true, lockNow = true))
        assertEquals(true, lowered(false, false, true, true, trusted = true, lockNow = true).any)
        assertEquals(BackupRestorePolicy.LoweredProtection(), lowered(false, false, true, true, trusted = false, lockNow = true))
        assertEquals(BackupRestorePolicy.LoweredProtection(), lowered(false, false, true, true, trusted = true, lock = true, lockNow = true))
    }

    /** Doppelte Kennungen (S3-08): wie ungültige gezählt, nie still ersetzt (backup_duplicate_ids.json). */
    @Test
    fun duplicateIdsCountAsInvalid() {
        val fixture = JSONObject(java.io.File(com.cryptochecker.app.parity.ParityJson.dir(), "backup_duplicate_ids.json").readText())
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() >= 5)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ids = c.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getLong(it) } }
            val keep = c.getJSONArray("keep").let { a -> (0 until a.length()).map { a.getBoolean(it) } }
            val got = BackupRestorePolicy.keepFirstIds(ids)
            assertEquals(c.getString("name"), keep, got)
            assertEquals(c.getString("name"), c.getInt("invalid"), got.count { !it })
        }
        // Im Lesen der Sicherung: der zweite Eintrag mit Kennung 7 zählt als ungültig
        val tx = com.cryptochecker.app.data.portfolio.PortfolioJson.txToJson(PortfolioTxEntity(id = 7, coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1))
        val parsed = com.cryptochecker.app.data.portfolio.PortfolioJson.parseTxs(org.json.JSONArray().put(tx).put(JSONObject(tx.toString())))
        assertEquals(1, parsed.items.size)
        assertEquals(1, parsed.invalid)
    }
}
