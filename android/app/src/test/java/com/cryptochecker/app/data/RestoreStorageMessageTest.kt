package com.cryptochecker.app.data

import com.cryptochecker.app.data.backup.BackupJournalUnavailableException
import com.cryptochecker.app.data.backup.BackupRestoreStorageException
import com.cryptochecker.app.data.backup.RestoreRun
import com.cryptochecker.app.domain.local.DatabaseKeyUnavailableException
import com.cryptochecker.app.ui.features.settings.BackupMessage
import com.cryptochecker.app.ui.features.settings.BackupResultPresentation
import com.cryptochecker.resources.R
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // Anbindung an das Schreiben in BackupManager.restore und die Sperre der Adress-Aktualisierung beim Wiederherstellen
    // und Zurückrollen: Regel wiring in .github/scripts/source_rules.py
}
