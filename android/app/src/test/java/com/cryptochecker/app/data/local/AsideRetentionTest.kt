package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Decision
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Fingerprint
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.PreviousKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Beiseitegelegte Kopien nach Frist und bestätigtem Hinweis löschen ([AsideRetention],
 * [DatabaseEncryptionMigration.pruneAside]) — nie die aktuelle Datei, frühere Schlüssel danach im selben Lauf.
 */
class AsideRetentionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val db = "cryptochecker.db"

    @Test
    fun neueKopieBeginntDieFristJetzt() {
        val s = AsideRetention.select(listOf("a"), emptyMap(), now = 1_000L, allowed = true)
        assertTrue(s.delete.isEmpty())
        assertEquals(mapOf("a" to 1_000L), s.seen)
    }

    @Test
    fun nachDreissigTagenWegAberNurBestaetigt() {
        val seen = mapOf("a" to 0L, "b" to 20 * day)
        val now = 30 * day
        assertEquals(listOf("a"), AsideRetention.select(listOf("a", "b"), seen, now, allowed = true).delete)
        // Hinweis «nicht entsperrt» noch offen: nichts löschen
        assertTrue(AsideRetention.select(listOf("a", "b"), seen, now, allowed = false).delete.isEmpty())
    }

    @Test
    fun verschwundeneKopienFallenAusDemMerkerUndZukunftZaehltAlsJetzt() {
        val s = AsideRetention.select(listOf("b"), mapOf("a" to 0L, "b" to 99 * day), now = 10 * day, allowed = true)
        assertEquals(mapOf("b" to 10 * day), s.seen)
        assertTrue(s.delete.isEmpty())
    }

    @Test
    fun merkdateiHinUndZurueck() {
        val store = AsideRetention.SeenStore(folder.root.resolve("db_keys/$db.aside-seen"))
        assertTrue(store.read().isEmpty())
        store.write(mapOf("$db.unreadable" to 5L, "$db.unreadable-17" to 7L))
        assertEquals(mapOf("$db.unreadable" to 5L, "$db.unreadable-17" to 7L), store.read())
        // Ablauf über zwei Starts: erst gemerkt, nach der Frist zum Löschen freigegeben
        assertTrue(AsideRetention.expired(store, listOf("$db.unreadable"), now = 6L, allowed = true).isEmpty())
        assertEquals(listOf("$db.unreadable"), AsideRetention.expired(store, listOf("$db.unreadable"), now = 5L + 30 * day, allowed = true))
        store.write(emptyMap())
        assertTrue(store.read().isEmpty())
    }

    /** Aktuelle Datei (öffnet mit [currentKey]) und beiseitegelegte Kopien je mit ihrem Schlüssel. */
    private class Files(val currentKey: String, val aside: MutableMap<String, String>) : DatabaseEncryptionMigration.Files {
        val deleted = mutableListOf<String>()
        override fun kind(database: String) = FileKind.ENCRYPTED
        override fun hasExportCopy(database: String) = false
        override fun deleteExportCopy(database: String) = Unit
        override fun checkpoint(database: String) = Unit
        override fun walBytes(database: String) = 0L
        override fun exportEncrypted(database: String, passphrase: ByteArray) = Unit
        override fun fingerprintOriginal(database: String) = Fingerprint(1, emptyMap())
        override fun fingerprintExportCopy(database: String, passphrase: ByteArray) = Fingerprint(1, emptyMap())
        override fun deleteSidecars(database: String) = Unit
        override fun replaceWithExportCopy(database: String) = Unit
        override fun setAside(database: String) = error("nicht erwartet")
        override fun opens(database: String, passphrase: ByteArray) = String(passphrase) == currentKey
        override fun asideCopies(database: String) = aside.keys.toList()
        override fun asideOpens(database: String, copy: String, passphrase: ByteArray) = aside[copy] == String(passphrase)
        override fun deleteAside(database: String, copy: String) {
            deleted += copy
            aside.remove(copy)
        }
    }

    private class Keys(val stored: String, val previous: MutableList<String>) : DatabaseEncryptionMigration.Keys {
        var discarded = 0
        override fun load(database: String): KeyLookup = KeyLookup.Found(stored.toByteArray())
        override fun create(database: String): ByteArray = error("nicht erwartet")
        override fun discardPrevious(database: String) {
            discarded++
            previous.clear()
        }
        override fun previous(database: String) = previous.map { PreviousKey(it, it.toByteArray()) }
    }

    @Test
    fun freigegebeneKopienWegUndFruehereSchluesselImSelbenLauf() {
        val files = Files("K2", linkedMapOf("$db.unreadable" to "verloren", "$db.unreadable-9" to "auch"))
        val keys = Keys("K2", mutableListOf("K0"))
        val d = DatabaseEncryptionMigration(files, keys, asideExpired = { _, copies -> copies }).prepare(db) as Decision.Encrypted
        assertEquals("K2", String(d.passphrase))
        assertEquals(listOf("$db.unreadable", "$db.unreadable-9"), files.deleted)
        assertEquals(1, keys.discarded)
    }

    @Test
    fun ohneFreigabeBleibtAllesUndFruehereSchluesselAuch() {
        val files = Files("K2", linkedMapOf("$db.unreadable" to "verloren"))
        val keys = Keys("K2", mutableListOf("K0"))
        DatabaseEncryptionMigration(files, keys).prepare(db)
        assertTrue(files.deleted.isEmpty())
        assertEquals(0, keys.discarded)
    }

    @Test
    fun kopieDieEinFruehererSchluesselOeffnetKommtZurueckStattGeloescht() {
        // Erst zurückholen, dann aufräumen: eine wieder lesbare Kopie wird nie gelöscht
        val files = object : DatabaseEncryptionMigration.Files by Files("K2", linkedMapOf("$db.unreadable" to "K1")) {
            var restored = false
            override fun restoreAside(database: String, copy: String) {
                restored = true
            }
            override fun deleteAside(database: String, copy: String) = error("nicht löschen")
        }
        val keys = object : DatabaseEncryptionMigration.Keys by Keys("K2", mutableListOf("K1")) {
            override fun promote(database: String, id: String) = Unit
        }
        val d = DatabaseEncryptionMigration(files, keys, asideExpired = { _, copies -> copies }).prepare(db) as Decision.Encrypted
        assertTrue(d.restored)
        assertTrue(files.restored)
    }

    @Test
    fun nurGemeldeteKopienWerdenGeloescht() {
        // Eine Freigabe für einen fremden Namen (z. B. die aktuelle Datei) wird übergangen
        val files = Files("K2", linkedMapOf("$db.unreadable" to "verloren"))
        DatabaseEncryptionMigration(files, Keys("K2", mutableListOf()), asideExpired = { _, _ -> listOf(db) }).prepare(db)
        assertTrue(files.deleted.isEmpty())
    }
}
