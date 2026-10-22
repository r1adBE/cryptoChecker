package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Fingerprint
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merkliste ohne Schlüssel (Build 91): Mit dem Standard von [EncryptingOpenHelperFactory] entsteht `cryptochecker.db` nie
 * unverschlüsselt — ohne Schlüssel und ohne Datei nur im Arbeitsspeicher ([Decision.Refused]); eine schon unverschlüsselt
 * vorhandene Datei öffnet weiter wie bisher (nie Daten verlieren) und wird mit Schlüssel beim nächsten Start überführt.
 */
class EncryptingOpenHelperFactoryDefaultsTest {

    /** Nur `kind`; jeder andere Schritt wäre eine Änderung an der Datei und schlägt den Test fehl. */
    private class UntouchedFiles(private val kind: FileKind) : DatabaseEncryptionMigration.Files {
        override fun kind(database: String) = kind
        override fun hasExportCopy(database: String) = false
        override fun deleteExportCopy(database: String) = fail()
        override fun checkpoint(database: String) = fail()
        override fun walBytes(database: String): Long = fail()
        override fun exportEncrypted(database: String, passphrase: ByteArray) = fail()
        override fun fingerprintOriginal(database: String): Fingerprint = fail()
        override fun fingerprintExportCopy(database: String, passphrase: ByteArray): Fingerprint = fail()
        override fun deleteSidecars(database: String) = fail()
        override fun replaceWithExportCopy(database: String) = fail()
        override fun setAside(database: String) = fail()
        override fun opens(database: String, passphrase: ByteArray): Boolean = fail()
        private fun fail(): Nothing = throw AssertionError("Datei darf nicht angefasst werden")
    }

    /** Keystore ohne Schlüssel, Anlegen scheitert (dauerhaft kaputt oder gerade nicht verfügbar). */
    private object NoKeys : DatabaseEncryptionMigration.Keys {
        override fun load(database: String): KeyLookup = KeyLookup.Absent
        override fun create(database: String): ByteArray = throw IllegalStateException("Keystore")
    }

    private fun prepare(kind: FileKind, database: String) = DatabaseEncryptionMigration(
        UntouchedFiles(kind),
        NoKeys,
        encryptionRequired = { it in EncryptingOpenHelperFactory.ENCRYPTION_REQUIRED },
    ).prepare(database)

    @Test
    fun defaultRequiresEncryptionForWatchlistAndPortfolio() {
        assertTrue(AppDatabase.DB_NAME in EncryptingOpenHelperFactory.ENCRYPTION_REQUIRED)
        assertTrue(PortfolioDatabase.DB_NAME in EncryptingOpenHelperFactory.ENCRYPTION_REQUIRED)
    }

    @Test
    fun newWatchlistWithoutKeyIsNeverCreatedInPlaintext() {
        // Bis Build 90: Decision.Plaintext (neue unverschlüsselte Datei)
        assertEquals(Decision.Refused, prepare(FileKind.ABSENT, AppDatabase.DB_NAME))
    }

    @Test
    fun existingPlaintextWatchlistWithoutKeyKeepsWorking() {
        // Alte unverschlüsselte Datei: seit Build 91 nur gelesen (Inhalt im Arbeitsspeicher, keine leere Liste), nie
        // weitergeschrieben — wie iOS (Entscheid 21.10.)
        assertEquals(Decision.PlaintextKept, prepare(FileKind.PLAINTEXT, AppDatabase.DB_NAME))
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
        // Seit Build 103: App und Systemsicherung (eigene Fabrik) überführen dieselbe Datei nie gleichzeitig
        assertTrue(EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME) === EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME))
        assertTrue(EncryptingOpenHelperFactory.prepareLock(AppDatabase.DB_NAME) !== EncryptingOpenHelperFactory.prepareLock(PortfolioDatabase.DB_NAME))
    }
}
