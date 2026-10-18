package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Fingerprint
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.OpenPlan
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.TableFingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ablauf der Datenbank-Verschlüsselung ([DatabaseEncryptionMigration]) gegen eine Fälschung der Datei- und
 * Schlüsselschicht: jeder Zweig, vor allem jeder Fehler — die unverschlüsselte Datei bleibt dann unverändert.
 * Der echte Export (SQLCipher) läuft im instrumentierten Test `DatabaseEncryptionTest`.
 */
class DatabaseEncryptionMigrationTest {

    private val db = "cryptochecker.db"
    private val content = Fingerprint(
        userVersion = 15,
        tables = mapOf("watches" to TableFingerprint(2, 42), "alarms" to TableFingerprint(1, 7)),
    )

    /** Datei, Kopie und Nebendateien im Speicher; jeder Schritt kann scheitern. */
    private class FakeFiles(var kind: FileKind, var original: Fingerprint?) : DatabaseEncryptionMigration.Files {
        var copy: Fingerprint? = null
        var walBytes = 0L
        var walAfterCheckpoint = 0L
        var sidecars = true
        var setAsideCount = 0
        val calls = mutableListOf<String>()
        var failAt: String? = null
        /** Export verändert den Inhalt (z. B. fehlende Zeilen). */
        var exportMutation: (Fingerprint) -> Fingerprint = { it }

        private fun step(name: String) {
            calls += name
            if (failAt == name) throw IllegalStateException("Fehler in $name")
        }

        override fun kind(database: String) = kind
        override fun hasExportCopy(database: String) = copy != null
        override fun deleteExportCopy(database: String) { step("deleteCopy"); copy = null }
        override fun checkpoint(database: String) { step("checkpoint"); walBytes = walAfterCheckpoint }
        override fun walBytes(database: String) = walBytes
        override fun exportEncrypted(database: String, passphrase: ByteArray) {
            step("export"); copy = exportMutation(original!!)
        }
        override fun fingerprintOriginal(database: String): Fingerprint { step("fingerprintOriginal"); return original!! }
        override fun fingerprintExportCopy(database: String, passphrase: ByteArray): Fingerprint {
            step("fingerprintCopy"); return copy!!
        }
        override fun deleteSidecars(database: String) { step("deleteSidecars"); sidecars = false }
        override fun replaceWithExportCopy(database: String) {
            step("replace"); original = copy; copy = null; kind = FileKind.ENCRYPTED
        }
        override fun setAside(database: String) { step("setAside"); setAsideCount++; kind = FileKind.ABSENT; original = null }
        /** Passt der gespeicherte Schlüssel zur Datei? */
        var keyMatches = true
        override fun opens(database: String, passphrase: ByteArray): Boolean { step("opens"); return keyMatches }
    }

    private class FakeKeys(var stored: KeyLookup) : DatabaseEncryptionMigration.Keys {
        var created = 0
        var failCreate = false
        var discarded = 0
        var failDiscard = false
        override fun load(database: String) = stored
        override fun discardPrevious(database: String) {
            if (failDiscard) throw IllegalStateException("gesperrt")
            discarded++
        }
        override fun create(database: String): ByteArray {
            if (failCreate) throw IllegalStateException("Keystore")
            created++
            val key = "neu$created".toByteArray()
            stored = KeyLookup.Found(key)
            return key
        }
    }

    private val found = KeyLookup.Found("alt".toByteArray())

    private fun prepare(files: FakeFiles, keys: FakeKeys) = DatabaseEncryptionMigration(files, keys).prepare(db)

    // ── Neue Installation ──

    @Test
    fun noFileWithKeyOpensEncrypted() {
        val files = FakeFiles(FileKind.ABSENT, null)
        val decision = prepare(files, FakeKeys(found))
        assertArrayEquals("alt".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertFalse(decision.migrated)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun noFileWithoutKeyCreatesOne() {
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepare(FakeFiles(FileKind.ABSENT, null), keys)
        assertArrayEquals("neu1".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertEquals(1, keys.created)
    }

    @Test
    fun noFileAndNoKeyPossibleOpensPlaintext() {
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        assertEquals(Decision.Plaintext, prepare(FakeFiles(FileKind.ABSENT, null), keys))
    }

    // ── Verschlüsselungspflicht (Portfolio): ohne Schlüssel nie unverschlüsselt anlegen ──

    private fun prepareRequired(files: FakeFiles, keys: FakeKeys) =
        DatabaseEncryptionMigration(files, keys, encryptionRequired = { it == db }).prepare(db)

    @Test
    fun requiredNoFileAndNoKeyPossibleIsRefused() {
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        val files = FakeFiles(FileKind.ABSENT, null)
        assertEquals(Decision.Refused, prepareRequired(files, keys))
        // Nichts angelegt, nichts angefasst
        assertEquals(FileKind.ABSENT, files.kind)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun requiredNoFileWithUnreadableKeyAndNoNewKeyIsRefused() {
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore"))).apply { failCreate = true }
        assertEquals(Decision.Refused, prepareRequired(FakeFiles(FileKind.ABSENT, null), keys))
    }

    @Test
    fun requiredNoFileWithKeyOpensEncrypted() {
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepareRequired(FakeFiles(FileKind.ABSENT, null), keys)
        assertArrayEquals("neu1".toByteArray(), (decision as Decision.Encrypted).passphrase)
    }

    @Test
    fun requiredExistingPlaintextWithoutKeyKeepsWorkingUntouched() {
        // Alte unverschlüsselte Datei aus einem früheren Fehlschlag: weiter öffnen, nie Daten verlieren
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        val files = FakeFiles(FileKind.PLAINTEXT, content)
        assertEquals(Decision.Plaintext, prepareRequired(files, keys))
        assertEquals(FileKind.PLAINTEXT, files.kind)
        assertEquals(content, files.original)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun requiredExistingPlaintextWithKeyIsMigrated() {
        val files = FakeFiles(FileKind.PLAINTEXT, content)
        val decision = prepareRequired(files, FakeKeys(found))
        assertTrue((decision as Decision.Encrypted).migrated)
        assertEquals(FileKind.ENCRYPTED, files.kind)
    }

    @Test
    fun requiredExistingPlaintextWithFailedMigrationStaysPlaintext() {
        val files = FakeFiles(FileKind.PLAINTEXT, content).apply { failAt = "export" }
        assertEquals(Decision.Plaintext, prepareRequired(files, FakeKeys(found)))
        assertEquals(FileKind.PLAINTEXT, files.kind)
        assertEquals(content, files.original)
    }

    @Test
    fun otherDatabaseWithoutRequirementStillOpensPlaintext() {
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        val migration = DatabaseEncryptionMigration(
            FakeFiles(FileKind.ABSENT, null),
            keys,
            encryptionRequired = { it == "portfolio.db" },
        )
        assertEquals(Decision.Plaintext, migration.prepare(db))
    }

    @Test
    fun planOpenCoversKeyAndFileStates() {
        fun plan(kind: FileKind, keyUsable: Boolean, required: Boolean) = DatabaseEncryptionMigration.planOpen(kind, keyUsable, required)
        for (required in listOf(false, true)) {
            for (kind in FileKind.entries) {
                assertEquals(OpenPlan.ENCRYPTED, plan(kind, true, required))
            }
            // Verschlüsselte Datei: eigene Regeln, nie unverschlüsselt
            assertEquals(OpenPlan.ENCRYPTED, plan(FileKind.ENCRYPTED, false, required))
            // Schon unverschlüsselt vorhanden: weiter wie bisher
            assertEquals(OpenPlan.PLAINTEXT, plan(FileKind.PLAINTEXT, false, required))
        }
        assertEquals(OpenPlan.PLAINTEXT, plan(FileKind.ABSENT, false, false))
        assertEquals(OpenPlan.REFUSE, plan(FileKind.ABSENT, false, true))
    }

    @Test
    fun orphanedCopyWithoutSourceIsRemoved() {
        val files = FakeFiles(FileKind.ABSENT, null).apply { copy = content }
        prepare(files, FakeKeys(found))
        assertEquals(null, files.copy)
    }

    // ── Überführen ──

    @Test
    fun plaintextIsExportedVerifiedThenReplaced() {
        val files = FakeFiles(FileKind.PLAINTEXT, content)
        val decision = prepare(files, FakeKeys(found))
        assertTrue((decision as Decision.Encrypted).migrated)
        assertEquals(
            listOf("checkpoint", "fingerprintOriginal", "export", "fingerprintCopy", "deleteSidecars", "replace"),
            files.calls,
        )
        assertEquals(FileKind.ENCRYPTED, files.kind)
        assertEquals(content, files.original)
    }

    @Test
    fun plaintextWithoutStoredKeyCreatesOneAndMigrates() {
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepare(FakeFiles(FileKind.PLAINTEXT, content), keys)
        assertTrue((decision as Decision.Encrypted).migrated)
        assertArrayEquals("neu1".toByteArray(), decision.passphrase)
    }

    @Test
    fun plaintextWithUnreadableOldKeyGetsNewKey() {
        // Nichts Verschlüsseltes hängt am alten Schlüssel: ersetzen ist gefahrlos
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore")))
        val decision = prepare(FakeFiles(FileKind.PLAINTEXT, content), keys)
        assertTrue((decision as Decision.Encrypted).migrated)
        assertEquals(1, keys.created)
    }

    @Test
    fun plaintextWithoutAnyKeyStaysUntouched() {
        val files = FakeFiles(FileKind.PLAINTEXT, content)
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        assertEquals(Decision.Plaintext, prepare(files, keys))
        assertTrue(files.calls.isEmpty())
        assertEquals(FileKind.PLAINTEXT, files.kind)
    }

    @Test
    fun leftoverCopyOfInterruptedRunIsRebuilt() {
        val files = FakeFiles(FileKind.PLAINTEXT, content).apply { copy = content.copy(tables = emptyMap()) }
        val decision = prepare(files, FakeKeys(found))
        assertTrue((decision as Decision.Encrypted).migrated)
        assertEquals("deleteCopy", files.calls.first())
        assertEquals(content, files.original)
    }

    @Test
    fun walLeftAfterCheckpointKeepsPlaintextAndSidecars() {
        val files = FakeFiles(FileKind.PLAINTEXT, content).apply { walBytes = 4096; walAfterCheckpoint = 4096 }
        assertEquals(Decision.Plaintext, prepare(files, FakeKeys(found)))
        assertFalse("export" in files.calls)
        assertTrue(files.sidecars)
        assertEquals(FileKind.PLAINTEXT, files.kind)
    }

    @Test
    fun everyFailingStepKeepsPlaintextAndRemovesCopy() {
        for (step in listOf("checkpoint", "fingerprintOriginal", "export", "fingerprintCopy", "deleteSidecars", "replace")) {
            val files = FakeFiles(FileKind.PLAINTEXT, content).apply { failAt = step }
            assertEquals(step, Decision.Plaintext, prepare(files, FakeKeys(found)))
            assertEquals(step, FileKind.PLAINTEXT, files.kind)
            assertEquals(step, content, files.original)
            assertEquals(step, null, files.copy)
        }
    }

    @Test
    fun missingRowsInCopyKeepPlaintext() {
        val files = FakeFiles(FileKind.PLAINTEXT, content).apply {
            exportMutation = { it.copy(tables = it.tables + ("watches" to TableFingerprint(1, 40))) }
        }
        assertEquals(Decision.Plaintext, prepare(files, FakeKeys(found)))
        assertFalse("deleteSidecars" in files.calls)
        assertEquals(null, files.copy)
        assertEquals(FileKind.PLAINTEXT, files.kind)
    }

    @Test
    fun differentChecksumOrVersionOrMissingTableKeepPlaintext() {
        val mutations = listOf<(Fingerprint) -> Fingerprint>(
            { it.copy(tables = it.tables + ("alarms" to TableFingerprint(1, 8))) },
            { it.copy(userVersion = 0) },
            { it.copy(tables = it.tables - "alarms") },
        )
        for (mutation in mutations) {
            val files = FakeFiles(FileKind.PLAINTEXT, content).apply { exportMutation = mutation }
            assertEquals(Decision.Plaintext, prepare(files, FakeKeys(found)))
            assertEquals(FileKind.PLAINTEXT, files.kind)
        }
    }

    @Test
    fun failedCopyDeletionStillFallsBackToPlaintext() {
        val files = object : DatabaseEncryptionMigration.Files by FakeFiles(FileKind.PLAINTEXT, content) {
            override fun exportEncrypted(database: String, passphrase: ByteArray) = throw IllegalStateException("voll")
            override fun deleteExportCopy(database: String) = throw IllegalStateException("gesperrt")
        }
        assertEquals(Decision.Plaintext, DatabaseEncryptionMigration(files, FakeKeys(found)).prepare(db))
    }

    // ── Verschlüsselt ──

    @Test
    fun encryptedWithKeyOpensWithoutTouchingFiles() {
        val files = FakeFiles(FileKind.ENCRYPTED, content)
        val decision = prepare(files, FakeKeys(found))
        assertArrayEquals("alt".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertFalse(decision.setAside)
        assertEquals(listOf("opens"), files.calls)
        assertEquals(content, files.original)
    }

    @Test
    fun encryptedWithNonMatchingKeyIsSetAsideNotHandedToRoom() {
        // Room würde eine Datei, die SQLite nicht als Datenbank erkennt, löschen (onCorruption)
        val files = FakeFiles(FileKind.ENCRYPTED, content).apply { keyMatches = false }
        val keys = FakeKeys(found)
        val decision = prepare(files, keys)
        assertTrue((decision as Decision.Encrypted).setAside)
        assertArrayEquals("neu1".toByteArray(), decision.passphrase)
        assertEquals(listOf("opens", "setAside"), files.calls)
    }

    @Test
    fun encryptedWithKeyCheckErrorChangesNothing() {
        val files = FakeFiles(FileKind.ENCRYPTED, content).apply { failAt = "opens" }
        val keys = FakeKeys(found)
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertEquals(0, files.setAsideCount)
        assertEquals(0, keys.created)
    }

    @Test
    fun encryptedWithLostKeyIsSetAsideThenStartsFresh() {
        val files = FakeFiles(FileKind.ENCRYPTED, content)
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepare(files, keys)
        assertTrue((decision as Decision.Encrypted).setAside)
        assertEquals(1, files.setAsideCount)
        assertEquals(1, keys.created)
        assertEquals(listOf("setAside"), files.calls)
    }

    @Test
    fun encryptedWithLostKeyCreatesNoKeyWhenSetAsideFails() {
        val files = FakeFiles(FileKind.ENCRYPTED, content).apply { failAt = "setAside" }
        val keys = FakeKeys(KeyLookup.Absent)
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertEquals(0, keys.created)
        assertEquals(FileKind.ENCRYPTED, files.kind)
    }

    @Test
    fun encryptedWithUnavailableKeyChangesNothing() {
        val files = FakeFiles(FileKind.ENCRYPTED, content)
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore beschäftigt")))
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertTrue(files.calls.isEmpty())
        assertEquals(0, keys.created)
    }

    @Test
    fun encryptedWithLostKeyAndNoNewKeyReportsUnavailable() {
        val files = FakeFiles(FileKind.ENCRYPTED, content)
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        // Beiseitegelegt bleibt beiseitegelegt: nichts gelöscht, der nächste Start legt neu an
        assertEquals(1, files.setAsideCount)
    }

    // ── Hilfen ──

    @Test
    fun previousKeyIsDiscardedOnlyAfterVerifiedOpen() {
        // Gespeicherter Schlüssel öffnet die Datei: früherer Schlüssel weg
        val keys = FakeKeys(found)
        assertTrue(prepare(FakeFiles(FileKind.ENCRYPTED, content), keys) is Decision.Encrypted)
        assertEquals(1, keys.discarded)
        // Passt nicht (beiseitegelegt, neuer Schlüssel): nichts löschen
        val mismatch = FakeKeys(found)
        prepare(FakeFiles(FileKind.ENCRYPTED, content).apply { keyMatches = false }, mismatch)
        assertEquals(0, mismatch.discarded)
        // Prüfung scheitert: nichts löschen
        val failing = FakeKeys(found)
        prepare(FakeFiles(FileKind.ENCRYPTED, content).apply { failAt = "opens" }, failing)
        assertEquals(0, failing.discarded)
        // Neue Datei bzw. Überführung: noch nicht mit dem Schlüssel geöffnet
        val fresh = FakeKeys(KeyLookup.Absent)
        prepare(FakeFiles(FileKind.ABSENT, null), fresh)
        prepare(FakeFiles(FileKind.PLAINTEXT, content), fresh)
        assertEquals(0, fresh.discarded)
    }

    @Test
    fun failedDiscardStillOpensEncrypted() {
        val keys = FakeKeys(found).apply { failDiscard = true }
        assertTrue(prepare(FakeFiles(FileKind.ENCRYPTED, content), keys) is Decision.Encrypted)
    }

    @Test
    fun rawKeyLiteralIsHexInQuotes() {
        val key = ByteArray(32) { it.toByte() }
        val literal = String(DatabaseEncryptionMigration.rawKeyLiteral(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", literal)
        assertEquals(67, literal.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawKeyLiteralRejectsShortKey() {
        DatabaseEncryptionMigration.rawKeyLiteral(ByteArray(16))
    }

    @Test
    fun rowChecksumSeparatesTypesAndValues() {
        val sums = listOf(
            listOf<Any?>(1L), listOf<Any?>(1.0), listOf<Any?>("1"), listOf<Any?>(byteArrayOf(1)), listOf<Any?>(null),
            listOf<Any?>(1L, null), listOf<Any?>(null, 1L), listOf<Any?>("ab", "c"), listOf<Any?>("a", "bc"),
        ).map(DatabaseEncryptionMigration::rowChecksum)
        assertEquals(sums.size, sums.toSet().size)
        assertEquals(DatabaseEncryptionMigration.rowChecksum(listOf(5, "x")), DatabaseEncryptionMigration.rowChecksum(listOf(5L, "x")))
    }

    @Test
    fun tableChecksumIgnoresRowOrder() {
        val rows = listOf(listOf<Any?>(1L, "BTC"), listOf<Any?>(2L, "ETH"), listOf<Any?>(3L, null))
        val empty = TableFingerprint(0, 0)
        val forward = rows.fold(empty, DatabaseEncryptionMigration::addRow)
        val backward = rows.reversed().fold(empty, DatabaseEncryptionMigration::addRow)
        assertEquals(forward, backward)
        assertEquals(3, forward.rows)
        assertNotEquals(forward, rows.take(2).fold(empty, DatabaseEncryptionMigration::addRow))
    }

    @Test
    fun differenceNamesTablesNotContents() {
        val after = content.copy(userVersion = 14, tables = mapOf("watches" to TableFingerprint(1, 42), "extra" to TableFingerprint(0, 0)))
        assertEquals(
            "user_version 15 → 14, alarms fehlt in der Kopie, extra nur in der Kopie, watches: 2 → 1 Zeilen",
            DatabaseEncryptionMigration.difference(content, after),
        )
    }
}
