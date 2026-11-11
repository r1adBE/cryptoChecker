package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.KeyLookup
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.OpenPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entscheidung vor dem Öffnen einer Datenbank ([DatabaseOpenPlanner]) gegen eine Fälschung der Datei- und
 * Schlüsselschicht: jeder Zweig, vor allem jeder Fehler — eine vorhandene Datei bzw. Kopie bleibt dann unverändert.
 * Der echte Export (SQLCipher) läuft im instrumentierten Test `DatabaseEncryptionTest`.
 */
class DatabaseOpenPlannerTest {

    private val db = "cryptochecker.db"

    /** Datei und wiederhergestellte Kopie im Speicher; jeder Schritt kann scheitern. */
    private class FakeFiles(var kind: FileKind, var restoredCopy: Boolean = false) : DatabaseOpenPlanner.Files {
        var setAsideCount = 0
        val calls = mutableListOf<String>()
        var failAt: String? = null

        private fun step(name: String) {
            calls += name
            if (failAt == name) throw IllegalStateException("Fehler in $name")
        }

        override fun kind(database: String) = kind
        override fun setAside(database: String) { step("setAside"); setAsideCount++; kind = FileKind.ABSENT }
        /** Passt der gespeicherte Schlüssel zur Datei? */
        var keyMatches = true
        override fun opens(database: String, passphrase: ByteArray): Boolean { step("opens"); return keyMatches }
        override fun hasRestoredCopy(database: String) = restoredCopy
        var encryptedWith: ByteArray? = null
        override fun encryptRestoredCopy(database: String, passphrase: ByteArray) {
            step("encryptRestoredCopy")
            encryptedWith = passphrase
            restoredCopy = false
            kind = FileKind.ENCRYPTED
        }
    }

    private class FakeKeys(var stored: KeyLookup) : DatabaseOpenPlanner.Keys {
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

    private fun prepare(files: FakeFiles, keys: FakeKeys) = DatabaseOpenPlanner(files, keys).prepare(db)

    // ── Neue Datei ──

    @Test
    fun noFileWithKeyOpensEncrypted() {
        val files = FakeFiles(FileKind.ABSENT)
        val decision = prepare(files, FakeKeys(found))
        assertArrayEquals("alt".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun noFileWithoutKeyCreatesOne() {
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepare(FakeFiles(FileKind.ABSENT), keys)
        assertArrayEquals("neu1".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertEquals(1, keys.created)
    }

    @Test
    fun noFileAndNoKeyPossibleIsRefused() {
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        val files = FakeFiles(FileKind.ABSENT)
        assertEquals(Decision.Refused, prepare(files, keys))
        // Nichts angelegt, nichts angefasst
        assertEquals(FileKind.ABSENT, files.kind)
        assertTrue(files.calls.isEmpty())
    }

    @Test
    fun noFileWithUnreadableKeyAndNoNewKeyIsRefused() {
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore"))).apply { failCreate = true }
        assertEquals(Decision.Refused, prepare(FakeFiles(FileKind.ABSENT), keys))
    }

    @Test
    fun noFileWithUnreadableKeyGetsNewKey() {
        // Nichts Verschlüsseltes hängt am alten Schlüssel: ersetzen ist gefahrlos
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore")))
        assertTrue(prepare(FakeFiles(FileKind.ABSENT), keys) is Decision.Encrypted)
        assertEquals(1, keys.created)
    }

    // ── Wiederhergestellte Kopie der Systemsicherung ──

    @Test
    fun restoredCopyIsEncryptedIntoTheDatabase() {
        val files = FakeFiles(FileKind.ABSENT, restoredCopy = true)
        val decision = prepare(files, FakeKeys(KeyLookup.Absent))
        assertArrayEquals("neu1".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertArrayEquals("neu1".toByteArray(), files.encryptedWith)
        assertEquals(listOf("encryptRestoredCopy"), files.calls)
        assertEquals(FileKind.ENCRYPTED, files.kind)
    }

    @Test
    fun restoredCopyWithoutKeyStaysAndNothingIsCreated() {
        val files = FakeFiles(FileKind.ABSENT, restoredCopy = true)
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        assertEquals(Decision.Refused, prepare(files, keys))
        assertTrue(files.restoredCopy)
        assertTrue(files.calls.isEmpty())
        assertEquals(FileKind.ABSENT, files.kind)
    }

    @Test
    fun failedEncryptionOfRestoredCopyKeepsCopyAndCreatesNoFile() {
        // Keine leere Datei: Sie verdrängte die Kopie beim nächsten Start
        val files = FakeFiles(FileKind.ABSENT, restoredCopy = true).apply { failAt = "encryptRestoredCopy" }
        assertEquals(Decision.Refused, prepare(files, FakeKeys(found)))
        assertTrue(files.restoredCopy)
        assertEquals(FileKind.ABSENT, files.kind)
    }

    @Test
    fun restoredCopyIsIgnoredWhileTheDatabaseExists() {
        val files = FakeFiles(FileKind.ENCRYPTED, restoredCopy = true)
        assertTrue(prepare(files, FakeKeys(found)) is Decision.Encrypted)
        assertFalse("encryptRestoredCopy" in files.calls)
    }

    // ── Regel ohne Android ──

    @Test
    fun planOpenCoversKeyAndFileStates() {
        for (kind in FileKind.entries) {
            assertEquals(OpenPlan.ENCRYPTED, DatabaseOpenPlanner.planOpen(kind, keyUsable = true))
        }
        // Verschlüsselte Datei: eigene Regeln
        assertEquals(OpenPlan.ENCRYPTED, DatabaseOpenPlanner.planOpen(FileKind.ENCRYPTED, keyUsable = false))
        // Ohne Datei und ohne Schlüssel nichts anlegen (wie iOS)
        assertEquals(OpenPlan.REFUSE, DatabaseOpenPlanner.planOpen(FileKind.ABSENT, keyUsable = false))
    }

    /** Verschlüsselte Datei, Schlüssel gerade nicht lesbar: Arbeitsspeicher nur erlaubt und sichtbar, sonst warten. */
    @Test
    fun planOpenWithUnavailableKeyOfEncryptedFile() {
        fun plan(allowed: Boolean, foreground: Boolean) = DatabaseOpenPlanner.planOpen(
            FileKind.ENCRYPTED, keyUsable = false, keyUnavailable = true, inMemoryAllowed = allowed, foreground = foreground,
        )
        assertEquals(OpenPlan.KEEP_FILE_IN_MEMORY, plan(allowed = true, foreground = true))
        // Hintergrund (Worker, Widget, Systemsicherung): warten, nie eine leere Datenbank für den ganzen Prozess
        assertEquals(OpenPlan.WAIT, plan(allowed = true, foreground = false))
        // Nicht erlaubt (z. B. die eigene Instanz der Systemsicherung): warten
        assertEquals(OpenPlan.WAIT, plan(allowed = false, foreground = true))
        assertEquals(OpenPlan.WAIT, plan(allowed = false, foreground = false))
        // Nur für eine schon verschlüsselte Datei: sonst gelten die Regeln ohne Schlüssel
        for (allowed in listOf(false, true)) {
            assertEquals(
                OpenPlan.REFUSE,
                DatabaseOpenPlanner.planOpen(FileKind.ABSENT, false, keyUnavailable = true, inMemoryAllowed = allowed, foreground = true),
            )
        }
        // Ohne «gerade nicht lesbar» bleibt die verschlüsselte Datei bei ihren eigenen Regeln
        assertEquals(OpenPlan.ENCRYPTED, DatabaseOpenPlanner.planOpen(FileKind.ENCRYPTED, false, inMemoryAllowed = true, foreground = true))
    }

    /** Der Weg in den Arbeitsspeicher verändert die Datei nie: kein Beiseitelegen, kein neuer Schlüssel, kein Löschen. */
    @Test
    fun unavailableKeyLeavesEncryptedFileUntouched() {
        val files = FakeFiles(FileKind.ENCRYPTED)
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore beschäftigt")))
        val decision = prepare(files, keys)
        assertTrue(decision is Decision.KeyUnavailable)
        assertEquals(FileKind.ENCRYPTED, files.kind)
        assertEquals(0, files.setAsideCount)
        assertEquals(0, keys.created)
        assertTrue(files.calls.isEmpty())
        // Einstellungen › Sicherung: der Stand bleibt
        assertEquals(setOf("x.db"), EncryptionPending.updated(setOf("x.db"), db, decision))
        assertEquals(emptySet<String>(), EncryptionPending.updated(emptySet(), db, decision))
    }

    // ── Verschlüsselt ──

    @Test
    fun encryptedWithKeyOpensWithoutTouchingFiles() {
        val files = FakeFiles(FileKind.ENCRYPTED)
        val decision = prepare(files, FakeKeys(found))
        assertArrayEquals("alt".toByteArray(), (decision as Decision.Encrypted).passphrase)
        assertFalse(decision.setAside)
        assertEquals(listOf("opens"), files.calls)
    }

    @Test
    fun encryptedWithNonMatchingKeyIsSetAsideNotHandedToRoom() {
        // Room würde eine Datei, die SQLite nicht als Datenbank erkennt, löschen (onCorruption)
        val files = FakeFiles(FileKind.ENCRYPTED).apply { keyMatches = false }
        val keys = FakeKeys(found)
        val decision = prepare(files, keys)
        assertTrue((decision as Decision.Encrypted).setAside)
        assertArrayEquals("neu1".toByteArray(), decision.passphrase)
        assertEquals(listOf("opens", "setAside"), files.calls)
    }

    @Test
    fun encryptedWithKeyCheckErrorChangesNothing() {
        val files = FakeFiles(FileKind.ENCRYPTED).apply { failAt = "opens" }
        val keys = FakeKeys(found)
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertEquals(0, files.setAsideCount)
        assertEquals(0, keys.created)
    }

    @Test
    fun encryptedWithLostKeyIsSetAsideThenStartsFresh() {
        val files = FakeFiles(FileKind.ENCRYPTED)
        val keys = FakeKeys(KeyLookup.Absent)
        val decision = prepare(files, keys)
        assertTrue((decision as Decision.Encrypted).setAside)
        assertEquals(1, files.setAsideCount)
        assertEquals(1, keys.created)
        assertEquals(listOf("setAside"), files.calls)
    }

    @Test
    fun encryptedWithLostKeyCreatesNoKeyWhenSetAsideFails() {
        val files = FakeFiles(FileKind.ENCRYPTED).apply { failAt = "setAside" }
        val keys = FakeKeys(KeyLookup.Absent)
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertEquals(0, keys.created)
        assertEquals(FileKind.ENCRYPTED, files.kind)
    }

    @Test
    fun encryptedWithUnavailableKeyChangesNothing() {
        val files = FakeFiles(FileKind.ENCRYPTED)
        val keys = FakeKeys(KeyLookup.Unavailable(IllegalStateException("Keystore beschäftigt")))
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        assertTrue(files.calls.isEmpty())
        assertEquals(0, keys.created)
    }

    @Test
    fun encryptedWithLostKeyAndNoNewKeyReportsUnavailable() {
        val files = FakeFiles(FileKind.ENCRYPTED)
        val keys = FakeKeys(KeyLookup.Absent).apply { failCreate = true }
        assertTrue(prepare(files, keys) is Decision.KeyUnavailable)
        // Beiseitegelegt bleibt beiseitegelegt: nichts gelöscht, der nächste Start legt neu an
        assertEquals(1, files.setAsideCount)
    }

    // ── Nur lesen (Systemsicherung) ──

    /** Schlüssel verloren oder passt nicht: nie beiseitelegen und neu beginnen — die Sicherung bricht ab. */
    @Test
    fun readOnlyNeverSetsAsideOrCreatesKey() {
        fun readOnly(files: FakeFiles, keys: FakeKeys) = DatabaseOpenPlanner(files, keys, readOnly = true).prepare(db)
        for (setup in listOf(
            FakeFiles(FileKind.ENCRYPTED) to FakeKeys(KeyLookup.Absent),
            FakeFiles(FileKind.ENCRYPTED).apply { keyMatches = false } to FakeKeys(found),
            FakeFiles(FileKind.ABSENT) to FakeKeys(found),
        )) {
            val (files, keys) = setup
            assertTrue(readOnly(files, keys) is Decision.KeyUnavailable)
            assertEquals(0, files.setAsideCount)
            assertEquals(0, keys.created)
            assertEquals(0, keys.discarded)
        }
        // Passender Schlüssel: öffnen, sonst nichts (kein Aufräumen früherer Schlüssel)
        val files = FakeFiles(FileKind.ENCRYPTED)
        val keys = FakeKeys(found)
        assertArrayEquals("alt".toByteArray(), (readOnly(files, keys) as Decision.Encrypted).passphrase)
        assertEquals(listOf("opens"), files.calls)
        assertEquals(0, keys.discarded)
    }

    // ── Ersetzte Dateien ──

    /** Ersetzte Dateien nach Frist weg; ersetzte Schlüssel erst, wenn keine ersetzte Datei mehr wartet. */
    @Test
    fun replacedCopiesFollowRetentionThenKeysGo() {
        val replaced = mutableListOf("$db.replaced", "$db.replaced-1")
        val files = object : DatabaseOpenPlanner.Files by FakeFiles(FileKind.ENCRYPTED) {
            override fun replacedCopies(database: String) = replaced.toList()
            override fun deleteReplaced(database: String, copy: String) { replaced -= copy }
        }
        var discardedReplaced = 0
        val keys = object : DatabaseOpenPlanner.Keys by FakeKeys(found) {
            override fun discardReplaced(database: String) { discardedReplaced++ }
        }
        var expire = listOf("$db.replaced-1", "fremd.db.replaced")
        val planner = DatabaseOpenPlanner(files, keys, replacedExpired = { _, _ -> expire })
        assertTrue(planner.prepare(db) is Decision.Encrypted)
        // Nur freigegebene und vorhandene; eine ersetzte Datei wartet noch → Schlüssel bleiben
        assertEquals(listOf("$db.replaced"), replaced)
        assertEquals(0, discardedReplaced)
        expire = replaced.toList()
        planner.prepare(db)
        assertTrue(replaced.isEmpty())
        assertEquals(1, discardedReplaced)
        // Nur lesen: nichts gelöscht
        replaced += "$db.replaced"
        DatabaseOpenPlanner(files, keys, replacedExpired = { _, c -> c }, readOnly = true).prepare(db)
        assertEquals(listOf("$db.replaced"), replaced)
    }

    // ── Hilfen ──

    @Test
    fun previousKeyIsDiscardedOnlyAfterVerifiedOpen() {
        // Gespeicherter Schlüssel öffnet die Datei: früherer Schlüssel weg
        val keys = FakeKeys(found)
        assertTrue(prepare(FakeFiles(FileKind.ENCRYPTED), keys) is Decision.Encrypted)
        assertEquals(1, keys.discarded)
        // Passt nicht (beiseitegelegt, neuer Schlüssel): nichts löschen
        val mismatch = FakeKeys(found)
        prepare(FakeFiles(FileKind.ENCRYPTED).apply { keyMatches = false }, mismatch)
        assertEquals(0, mismatch.discarded)
        // Prüfung scheitert: nichts löschen
        val failing = FakeKeys(found)
        prepare(FakeFiles(FileKind.ENCRYPTED).apply { failAt = "opens" }, failing)
        assertEquals(0, failing.discarded)
        // Neue Datei bzw. wiederhergestellte Kopie: noch nicht mit dem Schlüssel geöffnet
        val fresh = FakeKeys(KeyLookup.Absent)
        prepare(FakeFiles(FileKind.ABSENT), fresh)
        prepare(FakeFiles(FileKind.ABSENT, restoredCopy = true), fresh)
        assertEquals(0, fresh.discarded)
    }

    @Test
    fun failedDiscardStillOpensEncrypted() {
        val keys = FakeKeys(found).apply { failDiscard = true }
        assertTrue(prepare(FakeFiles(FileKind.ENCRYPTED), keys) is Decision.Encrypted)
    }

    @Test
    fun loadOrCreateKeepsStoredKeyAndCreatesOtherwise() {
        assertArrayEquals("alt".toByteArray(), FakeKeys(found).loadOrCreate(db))
        val absent = FakeKeys(KeyLookup.Absent)
        assertArrayEquals("neu1".toByteArray(), absent.loadOrCreate(db))
        assertEquals(1, absent.created)
    }

    @Test(expected = IllegalStateException::class)
    fun loadOrCreateThrowsWhenNoKeyIsPossible() {
        FakeKeys(KeyLookup.Absent).apply { failCreate = true }.loadOrCreate(db)
    }

    @Test
    fun rawKeyLiteralIsHexInQuotes() {
        val key = ByteArray(32) { it.toByte() }
        val literal = String(DatabaseOpenPlanner.rawKeyLiteral(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", literal)
        assertEquals(67, literal.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawKeyLiteralRejectsShortKey() {
        DatabaseOpenPlanner.rawKeyLiteral(ByteArray(16))
    }
}
