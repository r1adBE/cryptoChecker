package com.cryptochecker.app.data

import com.cryptochecker.app.R
import com.cryptochecker.app.backup.RestoreRun
import com.cryptochecker.app.domain.local.DatabaseKeyUnavailableException
import com.cryptochecker.app.ui.features.settings.BackupMessage
import com.cryptochecker.app.ui.features.settings.BackupResultPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wiederherstellen scheitert am Speicher der App ([RestoreRun.forMessage]): eigene Meldung
 * `backup_restore_storage_unavailable` statt «Das hat nicht geklappt» — die bisherigen Daten sind dann schon
 * zurückgerollt bzw. das Journal bleibt (der nächste Start rollt zurück, [RestoreRunTest]). Wie iOS
 * `BackupManager.isStorageFailure` (`RestoreStorageMessageTests.swift`).
 */
class RestoreStorageMessageTest {

    /** Wie SQLite bzw. SQLCipher (die Prüfung geht nach dem Klassennamen). */
    private class SQLiteFullException : RuntimeException("database or disk is full")
    private class SQLiteReadOnlyDatabaseException : RuntimeException("attempt to write a readonly database")

    @Test
    fun storageFailuresGetTheOwnMessage() {
        val full = RestoreRun.forMessage(IllegalStateException("Merkliste", SQLiteFullException()))
        assertTrue(full is BackupRestoreStorageException)
        assertEquals(BackupMessage.StorageUnavailable, BackupMessage.failure(full))
        assertEquals(R.string.backup_restore_storage_unavailable, BackupResultPresentation.messageRes(BackupMessage.StorageUnavailable))
        assertTrue(RestoreRun.forMessage(SQLiteReadOnlyDatabaseException()) is BackupRestoreStorageException)
        // Einstellungen (DataStore) nicht geschrieben
        assertTrue(RestoreRun.forMessage(IOException("No space left on device")) is BackupRestoreStorageException)
        // Schlüssel der Datenbank gerade nicht verfügbar
        assertTrue(RestoreRun.isStorageWrite(DatabaseKeyUnavailableException("watchlist", IllegalStateException("Keystore"))))
        // Lang in jeder Sprache: Dialog statt Toast
        assertTrue("backup_restore_storage_unavailable" in BackupResultPresentation.LONG_KEYS)
    }

    @Test
    fun otherFailuresStayAsTheyAre() {
        val journal = BackupJournalUnavailableException(IOException("Speicher voll"))
        assertSame(journal, RestoreRun.forMessage(journal))
        val broken = IllegalArgumentException("kaputt")
        assertSame(broken, RestoreRun.forMessage(broken))
        assertEquals(BackupMessage.Failed, BackupMessage.failure(broken))
        val cancel = CancellationException("abgebrochen")
        assertSame(cancel, RestoreRun.forMessage(cancel))
    }

    /** Angebunden an das Schreiben in `BackupManager.restore` (nicht an Sichern oder Lesen der Datei). */
    @Test
    fun wiredAtTheRestoreWrite() {
        val app = File(com.cryptochecker.app.parity.ParityJson.dir(), "../../app/src/main/java/com/cryptochecker/app")
        val manager = File(app, "backup/BackupManager.kt").readText()
        assertTrue(
            manager.contains(
                "val settingsNotes = try { addressRefresher.withRefreshLock { writeRestored(input, previous, encrypted) } } " +
                    "catch (e: Exception) { throw RestoreRun.forMessage(e) }",
            ),
        )
    }

    /**
     * S3-03: Adressen und eigener Server kommen in getrennten Schritten — Wiederherstellen und Zurückrollen laufen unter der
     * Sperre der Adress-Aktualisierung, die Reihenfolge der Schritte spielt keine Rolle.
     */
    @Test
    fun restoreAndRollBackHoldTheAddressRefreshLock() {
        val app = File(com.cryptochecker.app.parity.ParityJson.dir(), "../../app/src/main/java/com/cryptochecker/app")
        val recovery = File(app, "backup/BackupManagerRecovery.kt").readText()
        assertTrue(recovery.contains("addressRefresher.withRefreshLock {\n            rollBack(previous, Step.entries.toSet()"))
        val data = File(com.cryptochecker.app.parity.ParityJson.dir(), "../../data/src/main/java/com/cryptochecker/app/data")
        val refresher = File(data, "portfolio/WatchedAddressRefresher.kt").readText()
        assertTrue(refresher.contains("suspend fun <T> withRefreshLock(block: suspend () -> T): T = gate.withLock { block() }"))
    }
}
