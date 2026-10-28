package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.KeyLookup
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merkliste und Portfolio ohne Schlüssel: `cryptochecker.db` und das Portfolio entstehen nie unverschlüsselt — ohne
 * Schlüssel und ohne Datei nur im Arbeitsspeicher ([Decision.Refused]).
 */
class EncryptingOpenHelperFactoryDefaultsTest {

    /** Nur `kind`; jeder andere Schritt wäre eine Änderung an der Datei und schlägt den Test fehl. */
    private class UntouchedFiles(private val kind: FileKind) : DatabaseOpenPlanner.Files {
        override fun kind(database: String) = kind
        override fun setAside(database: String) = fail()
        override fun opens(database: String, passphrase: ByteArray): Boolean = fail()
        override fun encryptRestoredCopy(database: String, passphrase: ByteArray) = fail()
        private fun fail(): Nothing = throw AssertionError("Datei darf nicht angefasst werden")
    }

    /** Keystore ohne Schlüssel, Anlegen scheitert (dauerhaft kaputt oder gerade nicht verfügbar). */
    private object NoKeys : DatabaseOpenPlanner.Keys {
        override fun load(database: String): KeyLookup = KeyLookup.Absent
        override fun create(database: String): ByteArray = throw IllegalStateException("Keystore")
    }

    private fun prepare(database: String) = DatabaseOpenPlanner(UntouchedFiles(FileKind.ABSENT), NoKeys).prepare(database)

    @Test
    fun newDatabaseWithoutKeyIsNeverCreated() {
        assertEquals(Decision.Refused, prepare(AppDatabase.DB_NAME))
        assertEquals(Decision.Refused, prepare(PortfolioDatabase.DB_NAME))
    }

    @Test
    fun refusedWatchlistCountsAsEncryptionPendingButIsNotLocked() {
        // «Verschlüsselung noch nicht aktiv» unter Einstellungen › Sicherung, Hinweis «wird nicht gespeichert» in der Merkliste
        assertEquals(setOf(AppDatabase.DB_NAME), EncryptionPending.updated(emptySet(), AppDatabase.DB_NAME, Decision.Refused))
        val inMemory = setOf(AppDatabase.DB_NAME)
        assertEquals(WatchlistStorageLock.Notice.NOT_SAVED, WatchlistStorageLock.notice(inMemory, emptySet()))
        assertTrue(!WatchlistStorageLock.blocksAlarmSave(emptySet()))
    }

    @Test
    fun prepareLockIsSharedPerDatabaseAcrossFactories() {
        // App und Systemsicherung (eigene Fabrik) bereiten dieselbe Datei nie gleichzeitig vor
        assertTrue(EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME) === EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME))
        assertTrue(EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME) !== EncryptingOpenHelperFactory.prepareLock(PortfolioDatabase.DB_NAME))
    }
}
