package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.BackupVerification
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.Decision
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.KeyLookup
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.PreviousKey
import com.cryptochecker.app.lock.PortfolioLockPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frühere Datenbank-Schlüssel nummeriert behalten und beim nächsten Start gegen beiseitegelegte Dateien
 * probieren ([DatabaseOpenPlanner]); Prüfung einer geschriebenen Sicherung ([BackupVerification]); Sperre nach
 * dem Hintergrund mit monotoner Uhr ([PortfolioLockPolicy.relockAfterBackground]).
 */
class DatabaseKeyRecoveryTest {

    private val db = "cryptochecker.db"

    /** Aktuelle Datei und beiseitegelegte Dateien, je mit dem Schlüssel, der sie öffnet. */
    private class Files(var currentKey: String?, val aside: MutableMap<String, String> = linkedMapOf()) : DatabaseOpenPlanner.Files {
        val calls = mutableListOf<String>()
        var replaced = mutableListOf<String?>()
        var failRestore = false
        override fun kind(database: String) = if (currentKey == null) FileKind.ABSENT else FileKind.ENCRYPTED
        override fun setAside(database: String) {
            calls += "setAside"
            aside["$database.unreadable-${aside.size}"] = currentKey!!
            currentKey = null
        }
        override fun opens(database: String, passphrase: ByteArray) = String(passphrase) == currentKey
        override fun asideCopies(database: String) = aside.keys.toList().asReversed()
        override fun asideOpens(database: String, copy: String, passphrase: ByteArray) = aside[copy] == String(passphrase)
        override fun restoreAside(database: String, copy: String) {
            if (failRestore) throw IllegalStateException("Umbenennen")
            calls += "restoreAside"
            replaced += currentKey
            currentKey = aside.remove(copy)
        }
    }

    private class Keys(var stored: String?, val previous: MutableList<String> = mutableListOf()) : DatabaseOpenPlanner.Keys {
        var discarded = 0
        val replacedKeys = mutableListOf<String>()
        override fun load(database: String): KeyLookup = stored?.let { KeyLookup.Found(it.toByteArray()) } ?: KeyLookup.Absent
        override fun create(database: String): ByteArray {
            stored?.let { previous += it }
            val key = "neu${previous.size}"
            stored = key
            return key.toByteArray()
        }
        override fun discardPrevious(database: String) {
            discarded++
            previous.clear()
        }
        override fun previous(database: String) = previous.asReversed().map { PreviousKey(it, it.toByteArray()) }
        override fun promote(database: String, id: String) {
            stored?.let { replacedKeys += it }
            previous.remove(id)
            stored = id
        }
    }

    private fun prepare(files: Files, keys: Keys) = DatabaseOpenPlanner(files, keys).prepare(db)

    @Test
    fun setAsideFileComesBackWhenAKeptPreviousKeyOpensIt() {
        // Start 1: gespeicherter Schlüssel «falsch» (z. B. vorübergehender Keystore-Fehler galt als endgültig)
        val files = Files(currentKey = "K1")
        val keys = Keys(stored = "K1")
        keys.stored = null // «Schlüssel verloren»
        val first = prepare(files, keys) as Decision.Encrypted
        assertTrue(first.setAside)
        assertEquals(listOf("setAside"), files.calls)
        // Der Keystore liefert K1 doch noch als früheren Schlüssel (hier: so behalten)
        keys.previous += "K1"
        // Neue, leere Datei mit dem neuen Schlüssel
        files.currentKey = keys.stored

        // Start 2: gespeicherter Schlüssel öffnet die neue Datei — die beiseitegelegte öffnet mit K1 → zurück
        val second = prepare(files, keys) as Decision.Encrypted
        assertTrue(second.restored)
        assertArrayEquals("K1".toByteArray(), second.passphrase)
        assertEquals("K1", files.currentKey)
        assertEquals(listOf("neu0"), files.replaced) // neu begonnene Datei bleibt (bis zur Frist, pruneReplaced)
        assertEquals("K1", keys.stored)
        assertEquals(listOf("neu0"), keys.replacedKeys)
        assertTrue(files.aside.isEmpty())
        assertEquals(0, keys.discarded)

        // Start 3: nichts mehr beiseite → frühere Schlüssel dürfen weg
        val third = prepare(files, keys) as Decision.Encrypted
        assertFalse(third.restored)
        assertEquals(1, keys.discarded)
    }

    @Test
    fun previousKeysAreKeptWhileAsideFilesWait() {
        val files = Files(currentKey = "K2", aside = linkedMapOf("$db.unreadable" to "verloren"))
        val keys = Keys(stored = "K2", previous = mutableListOf("K0"))
        val d = prepare(files, keys) as Decision.Encrypted
        assertFalse(d.restored)
        // Kein früherer Schlüssel öffnet die beiseitegelegte Datei: alle behalten (vielleicht später)
        assertEquals(0, keys.discarded)
        assertEquals(listOf("K0"), keys.previous)
    }

    @Test
    fun previousKeyOpeningTheCurrentFileIsTakenBackWithoutSettingAside() {
        val files = Files(currentKey = "K1")
        val keys = Keys(stored = "K2", previous = mutableListOf("K1"))
        val d = prepare(files, keys) as Decision.Encrypted
        assertArrayEquals("K1".toByteArray(), d.passphrase)
        assertFalse(d.setAside)
        assertTrue(files.calls.isEmpty())
        assertEquals("K1", keys.stored)
    }

    @Test
    fun failedRestoreLeavesEverythingAndOpensCurrent() {
        val files = Files(currentKey = "K2", aside = linkedMapOf("$db.unreadable" to "K1")).apply { failRestore = true }
        val keys = Keys(stored = "K2", previous = mutableListOf("K1"))
        val d = prepare(files, keys) as Decision.Encrypted
        assertFalse(d.restored)
        assertArrayEquals("K2".toByteArray(), d.passphrase)
        assertEquals("K1", files.aside["$db.unreadable"])
        assertEquals(listOf("K1"), keys.previous)
    }

    @Test
    fun slotNamesAreNumberedNeverOverwritten() {
        val base = "$db.key.previous"
        assertEquals(base, DatabaseKeyStore.nextSlotName(emptySet(), base))
        assertEquals("$base.1", DatabaseKeyStore.nextSlotName(setOf(base), base))
        assertEquals("$base.3", DatabaseKeyStore.nextSlotName(setOf(base, "$base.2"), base))
        assertEquals(0, DatabaseKeyStore.slotNumber(base, base))
        assertEquals(2, DatabaseKeyStore.slotNumber("$base.2", base))
        assertNull(DatabaseKeyStore.slotNumber("$base.x", base))
        assertNull(DatabaseKeyStore.slotNumber("$base.0", base))
        assertNull(DatabaseKeyStore.slotNumber("$db.key", base))
    }

    @Test
    fun backupVerificationComparesSizeAndContent() {
        val written = "{\"format\":\"cryptochecker-backup\"}".toByteArray()
        assertNull(BackupVerification.check(written, written.copyOf()))
        assertTrue(BackupVerification.check(written, written.copyOf(written.size - 1))!!.contains("Grösse"))
        val changed = written.copyOf().also { it[3] = 'X'.code.toByte() }
        assertTrue(BackupVerification.check(written, changed)!!.contains("SHA-256"))
        assertTrue(BackupVerification.check(written, ByteArray(0)) != null)
    }

    @Test
    fun relockWhenClockRunsBackwards() {
        val limit = PortfolioLockPolicy.BACKGROUND_LIMIT_MILLIS
        assertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 10_000L, now = 10_000L + limit))
        assertTrue(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 10_000L, now = 9_999L))
        assertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 0L, now = 1L))
    }
}
