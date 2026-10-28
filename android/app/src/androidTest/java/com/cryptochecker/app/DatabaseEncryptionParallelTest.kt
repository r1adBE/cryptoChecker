package com.cryptochecker.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.DatabaseOpenPlanner
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseKeyStore
import com.cryptochecker.app.data.local.EncryptingOpenHelperFactory
import com.cryptochecker.app.data.local.SqlCipherDatabaseFiles
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Echter Paralleltest für [EncryptingOpenHelperFactory.prepareLock] — zwei Fabriken (wie App und Systemsicherung)
 * öffnen dieselbe Datenbank gleichzeitig aus zwei Threads, während eine wiederhergestellte, unverschlüsselte Kopie der
 * Systemsicherung bereitliegt. Erwartet: genau ein Verschlüsseln der Kopie und ein Schlüssel, Daten vollständig, keine
 * Ausnahme, nichts beiseitegelegt, keine Kopie übrig. Hilfen und Aufräumen wie `DatabaseEncryptionTest` (eigener
 * Schlüsselname und Ordner, damit sich die Tests nicht stören).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionParallelTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDirectory: File get() = File(context.noBackupFilesDir, "db_keys_parallel_test")

    /** Zählt das Verschlüsseln der Kopie, Beiseitelegen und neue Schlüssel über beide Fabriken. */
    private val exports = AtomicInteger()
    private val asides = AtomicInteger()
    private val createdKeys = AtomicInteger()

    private val plainFiles = SqlCipherDatabaseFiles(context, restoredCopies = mapOf(DB to COPY))

    private val files = object : DatabaseOpenPlanner.Files by plainFiles {
        override fun encryptRestoredCopy(database: String, passphrase: ByteArray) {
            exports.incrementAndGet()
            plainFiles.encryptRestoredCopy(database, passphrase)
        }

        override fun setAside(database: String) {
            asides.incrementAndGet()
            plainFiles.setAside(database)
        }
    }

    /** Je Runde ein frischer Schlüsselspeicher (eigene Menge «in diesem Lauf vermerkt» = neuer Prozess), von beiden Fabriken geteilt. */
    private fun keys(): DatabaseOpenPlanner.Keys {
        val keyStore = DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory, runSeen = mutableSetOf())
        return object : DatabaseOpenPlanner.Keys by keyStore {
            override fun create(database: String): ByteArray {
                createdKeys.incrementAndGet()
                return keyStore.create(database)
            }
        }
    }

    @Before
    @After
    fun clean() {
        context.deleteDatabase(DB)
        context.deleteDatabase(COPY)
        val path = context.getDatabasePath(DB).path
        context.getDatabasePath(DB).parentFile?.listFiles()?.filter { leftover(it, path) }?.forEach { it.delete() }
        keyDirectory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, DB))
        context.getSharedPreferences(StorageRecoveryNotice.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun twoFactoriesOpenWithRestoredCopyAtOnce() {
        restoredCopy()
        repeat(ROUNDS) { round ->
            if (round > 0) {
                // Jede Runde wieder nur mit der Kopie und ohne Schlüssel
                clean()
                restoredCopy()
            }
            exports.set(0)
            asides.set(0)
            createdKeys.set(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val counts = ConcurrentLinkedQueue<Int>()
            // Zwei eigene Fabriken: @Synchronized im Helfer gilt nur je Instanz, die Sperre muss prozessweit greifen
            val keys = keys()
            val helpers = List(2) { EncryptingOpenHelperFactory(context, keys, files).create(config()) }
            helpers.forEach { helper ->
                Thread {
                    try {
                        start.await()
                        counts += count(helper.writableDatabase)
                    } catch (e: Throwable) {
                        errors += e
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("Runde $round: Zeitgrenze", done.await(60, TimeUnit.SECONDS))
            helpers.forEach { it.close() }

            assertTrue("Runde $round: ${errors.firstOrNull()}", errors.isEmpty())
            assertEquals("Runde $round", listOf(ROWS, ROWS), counts.toList())
            assertEquals("Runde $round: genau einmal verschlüsselt", 1, exports.get())
            assertEquals("Runde $round: genau ein Schlüssel", 1, createdKeys.get())
            assertEquals("Runde $round: nichts beiseitegelegt", 0, asides.get())
            assertFalse("Runde $round", StorageRecoveryNotice.isSetAsidePending(context, DB))
            val file = context.getDatabasePath(DB)
            assertEquals("Runde $round", FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(file))
            val rest = file.parentFile?.listFiles()?.filter { leftover(it, file.path) }.orEmpty()
            assertTrue("Runde $round: übrig $rest", rest.isEmpty())
            assertFalse("Runde $round: Kopie übrig", context.getDatabasePath(COPY).exists())
        }
        // Danach: eine dritte Fabrik liest alles, ohne erneut zu verschlüsseln
        exports.set(0)
        val helper = EncryptingOpenHelperFactory(context, keys(), files).create(config())
        try {
            assertEquals(ROWS, count(helper.writableDatabase))
        } finally {
            helper.close()
        }
        assertEquals(0, exports.get())
    }

    // ── Helfer ──

    private fun config(name: String = DB) = SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(name)
        .callback(object : SupportSQLiteOpenHelper.Callback(VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(SCHEMA)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build()

    /**
     * Wiederhergestellte Kopie der Systemsicherung (unverschlüsselt, Android-SQLite) mit [ROWS] Zeilen — gross genug für
     * Überlappung; die Datenbank selbst fehlt wie nach einer Wiederherstellung ohne Schlüssel.
     */
    private fun restoredCopy() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(config(COPY))
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            repeat(ROWS) { db.execSQL("INSERT INTO items (name) VALUES (?)", arrayOf<Any?>("item-$it-" + "x".repeat(200))) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        helper.close()
        assertEquals(FileKind.ABSENT, SqlCipherDatabaseFiles.kindOf(context.getDatabasePath(DB)))
    }

    private fun leftover(file: File, path: String) =
        file.path.startsWith(path + SqlCipherDatabaseFiles.COPY_SUFFIX) || file.path.startsWith(path + SqlCipherDatabaseFiles.ASIDE_SUFFIX) ||
            file.path.startsWith(path + SqlCipherDatabaseFiles.REPLACED_SUFFIX)

    private fun count(db: SupportSQLiteDatabase): Int = db.query("SELECT COUNT(*) FROM items").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val DB = "encryption-parallel-test.db"
        const val COPY = "encryption-parallel-test-restored.db"
        const val TEST_ALIAS = "cryptochecker_db_key_parallel_test"
        const val VERSION = 1
        const val SCHEMA = "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)"
        const val ROWS = 2_000
        const val ROUNDS = 3
    }
}
