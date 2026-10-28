package com.cryptochecker.app

import android.content.Context
import android.database.DatabaseErrorHandler
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseOpenPlanner
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.DatabaseKeyStore
import com.cryptochecker.app.data.local.DatabaseSystemBackup
import com.cryptochecker.app.data.local.EncryptingOpenHelperFactory
import com.cryptochecker.app.data.local.SqlCipherDatabaseFiles
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verschlüsselung der Datenbanken auf dem Gerät mit echtem SQLCipher und echtem Keystore: ohne Schlüssel unlesbar;
 * verlorener Schlüssel legt beiseite, ein früherer holt zurück; Schlüssel gerade nicht lesbar lässt die Datei unberührt;
 * Kopie für die Systemsicherung hin und zurück — gleich bei der Wiederherstellung verschlüsselt oder, ohne Schlüssel, beim
 * nächsten Start. Die Ablauflogik selbst prüft `DatabaseOpenPlannerTest` (Unit).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDirectory: File get() = File(context.noBackupFilesDir, "db_keys_test")
    private val keys get() = DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory)

    /** Keystore, in dem kein Schlüssel entstehen kann (z. B. im eingeschränkten Prozess der Systemsicherung). */
    private val noKeys = object : DatabaseOpenPlanner.Keys {
        override fun load(database: String) = DatabaseOpenPlanner.KeyLookup.Absent
        override fun create(database: String): ByteArray = throw IllegalStateException("Test: kein Schlüssel möglich")
    }

    /** Dateischicht wie die App, mit der Testkopie als wiederhergestellter Kopie der Testdatenbank. */
    private val testFiles get() = SqlCipherDatabaseFiles(context, restoredCopies = mapOf(MAIN to EXPORT))

    @Before
    @After
    fun clean() {
        for (name in listOf(MAIN, EXPORT)) {
            context.deleteDatabase(name)
            val path = context.getDatabasePath(name).path
            context.getDatabasePath(name).parentFile?.listFiles()
                ?.filter {
                    it.path.startsWith(path + SqlCipherDatabaseFiles.COPY_SUFFIX) || it.path.startsWith(path + SqlCipherDatabaseFiles.ASIDE_SUFFIX) ||
                        it.path.startsWith(path + SqlCipherDatabaseFiles.REPLACED_SUFFIX)
                }
                ?.forEach { it.delete() }
        }
        keyDirectory.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Keystore-Schlüssel der Testdatenbank (je Datenbank ein eigener)
        keyStore.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, MAIN))
        context.getSharedPreferences(StorageRecoveryNotice.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        DatabaseEncryptionNotice.clearInMemoryForTest(MAIN)
    }

    /**
     * Schlüssel weg (Keystore zurückgesetzt): im ersten Lauf nur «gerade nicht verfügbar» (Datei
     * unverändert, [com.cryptochecker.app.data.local.DatabaseKeyRetry]); erst der nächste Lauf legt die Datei beiseite,
     * nicht gelöscht; App beginnt leer und nennt es einmal.
     */
    @Test
    fun lostKeySetsDatabaseAsideAndStartsEmpty() {
        withMain { main -> insertWatchAndAlarm(main) }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, MAIN))
        val file = context.getDatabasePath(MAIN)
        val before = file.readBytes()

        // Erster Lauf: nicht als verloren werten
        val firstRun = DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory, runSeen = mutableSetOf())
        val failure = runCatching { withMain(EncryptingOpenHelperFactory(context, firstRun)) { } }.exceptionOrNull()
        assertTrue(failure?.isDatabaseKeyUnavailable() == true)
        assertTrue(before.contentEquals(file.readBytes()))
        assertFalse(File(file.path + SqlCipherDatabaseFiles.ASIDE_SUFFIX).exists())

        // Nächster Lauf (neuer Prozess): jetzt verloren → beiseite
        val nextRun = DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory, runSeen = mutableSetOf())
        withMain(EncryptingOpenHelperFactory(context, nextRun)) { main -> assertEquals(0, count(main, "watches")) }
        val aside = File(context.getDatabasePath(MAIN).path + SqlCipherDatabaseFiles.ASIDE_SUFFIX)
        assertTrue(aside.exists())
        assertEquals(FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(aside))
        // Hinweis bleibt, bis er bestätigt ist, danach keiner mehr
        assertEquals(StorageRecoveryNotice.Kind.SET_ASIDE, StorageRecoveryNotice.pending(context))
        assertEquals(StorageRecoveryNotice.Kind.SET_ASIDE, StorageRecoveryNotice.pending(context))
        StorageRecoveryNotice.acknowledge(context, StorageRecoveryNotice.Kind.SET_ASIDE)
        assertEquals(null, StorageRecoveryNotice.pending(context))
    }

    /**
     * Keystore gibt den Schlüssel einer vorhandenen verschlüsselten Datei gerade nicht heraus: im Hintergrund
     * eine Ausnahme, im Vordergrund (Portfolio/Merkliste der App) leer im Arbeitsspeicher — die Datei bleibt Byte für Byte
     * gleich, und der nächste Start mit Schlüssel sieht wieder alles.
     */
    @Test
    fun unavailableKeyKeepsFileAndOpensEmptyInMemoryInForeground() {
        withMain { main -> insertWatchAndAlarm(main) }
        val file = context.getDatabasePath(MAIN)
        val before = file.readBytes()
        val locked = object : DatabaseOpenPlanner.Keys by keys {
            override fun load(database: String) =
                DatabaseOpenPlanner.KeyLookup.Unavailable(IllegalStateException("Test: Keystore beschäftigt"))
        }
        val background = EncryptingOpenHelperFactory(context, locked, inMemoryWhenKeyUnavailable = { true }, foreground = { false })
        val failure = runCatching { withMain(background) { } }.exceptionOrNull()
        assertTrue(failure?.isDatabaseKeyUnavailable() == true)
        assertFalse(DatabaseEncryptionNotice.isInMemory(MAIN))

        val visible = EncryptingOpenHelperFactory(context, locked, inMemoryWhenKeyUnavailable = { true }, foreground = { true })
        withMain(visible) { main ->
            assertEquals(0, count(main, "watches"))
            // Änderungen dieses Laufs bleiben im Arbeitsspeicher
            insertWatchAndAlarm(main)
        }
        assertTrue(DatabaseEncryptionNotice.isStoredKept(MAIN))
        assertTrue(DatabaseEncryptionNotice.isInMemory(MAIN))
        assertTrue(before.contentEquals(file.readBytes()))
        assertFalse(File(file.path + "-wal").let { it.exists() && it.length() > 0 })
        assertFalse(File(file.path + SqlCipherDatabaseFiles.ASIDE_SUFFIX).exists())

        withMain { main -> assertEquals(1, count(main, "watches")) }
    }

    /**
     * «Endgültig» war doch nur vorübergehend: Ein zweiter Schlüssel wurde angelegt, die Datei aber nie beiseitegelegt — der
     * frühere (nummeriert behalten) öffnet sie wieder und gilt danach als gespeicherter. Nichts beiseite, kein Hinweis.
     */
    @Test
    fun previousKeyStillOpeningTheFileIsTakenBack() {
        withMain { main -> insertWatchAndAlarm(main) }
        keys.create(MAIN) // bisheriger Schlüssel → «.previous», ein neuer (falscher) gespeichert

        withMain { main -> assertEquals(1, count(main, "watches")) }
        assertFalse(File(context.getDatabasePath(MAIN).path + SqlCipherDatabaseFiles.ASIDE_SUFFIX).exists())
        assertEquals(null, StorageRecoveryNotice.pending(context))
        // Danach öffnet der gespeicherte Schlüssel direkt
        withMain { main -> assertEquals(1, count(main, "alarms")) }
    }

    /**
     * Beiseitegelegte Datei und ein früherer Schlüssel, der sie öffnet: Der nächste Start holt sie zurück, die inzwischen
     * neu begonnene Datei bleibt daneben; Hinweis «wieder entsperrt» statt «nicht entsperrt».
     */
    @Test
    fun setAsideDatabaseComesBackWithKeptPreviousKey() {
        withMain { main -> insertWatchAndAlarm(main) }
        // Wie die Entscheidung bei einem fälschlich «endgültigen» Fehler: Datei beiseite, neuer Schlüssel, Hinweis vorgemerkt
        SqlCipherDatabaseFiles(context).setAside(MAIN)
        keys.create(MAIN)
        StorageRecoveryNotice.record(context, MAIN)
        withMain { main -> assertEquals(0, count(main, "watches")) } // leer neu begonnen (neuer Schlüssel)

        withMain { main -> assertEquals(1, count(main, "watches")) } // nächster Start: zurückgeholt
        val dir = context.getDatabasePath(MAIN).parentFile!!
        assertTrue(dir.listFiles()!!.any { it.name.startsWith(MAIN + SqlCipherDatabaseFiles.REPLACED_SUFFIX) && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") })
        assertEquals(StorageRecoveryNotice.Kind.KEY_RESTORED, StorageRecoveryNotice.pending(context))
        StorageRecoveryNotice.acknowledge(context, StorageRecoveryNotice.Kind.KEY_RESTORED)
        assertEquals(null, StorageRecoveryNotice.pending(context))
    }

    /**
     * Kopie für die Systemsicherung: unverschlüsselt und vollständig. Die Wiederherstellung macht sie gleich zur
     * verschlüsselten Hauptdatenbank (neuer Schlüssel) und löscht sie; nie liegt eine unverschlüsselte Hauptdatenbank da.
     */
    @Test
    fun systemBackupCopyRoundTrip() {
        val export = writeBackupCopyIntoFreshInstall()
        val backup = DatabaseSystemBackup(context, keys, MAIN, EXPORT)
        backup.adoptRestored()
        assertFalse(export.exists())
        assertEncryptedAndUnreadableWithoutKey(MAIN)

        withMain { main ->
            assertEquals(1, count(main, "watches"))
            assertEquals(1, count(main, "alarms"))
        }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
    }

    /**
     * Kein Schlüssel bei der Wiederherstellung: Die Kopie bleibt unter ihrem Namen, es entsteht keine Hauptdatenbank. Ohne
     * Schlüssel auch beim Start nur im Arbeitsspeicher (Kopie bleibt); der nächste Start mit Schlüssel verschlüsselt sie,
     * bevor Room die Datenbank öffnet, und löscht sie danach.
     */
    @Test
    fun restoredCopyWithoutKeyIsEncryptedAtNextStart() {
        val export = writeBackupCopyIntoFreshInstall()
        val main = context.getDatabasePath(MAIN)
        DatabaseSystemBackup(context, noKeys, MAIN, EXPORT).adoptRestored()
        assertTrue(export.exists())
        assertFalse(main.exists())

        withMain(EncryptingOpenHelperFactory(context, noKeys, testFiles)) { db -> assertEquals(0, count(db, "watches")) }
        assertTrue(DatabaseEncryptionNotice.isInMemory(MAIN))
        assertTrue(export.exists())
        assertFalse(main.exists())
        DatabaseEncryptionNotice.clearInMemoryForTest(MAIN)

        withMain(EncryptingOpenHelperFactory(context, keys, testFiles)) { db ->
            assertEquals(1, count(db, "watches"))
            assertEquals(1, count(db, "alarms"))
        }
        assertFalse(export.exists())
        assertEncryptedAndUnreadableWithoutKey(MAIN)
    }

    /** Hauptdatenbank mit einem Paar und einem Alarm sichern, dann wie eine frische Installation: nur die Kopie bleibt. */
    private fun writeBackupCopyIntoFreshInstall(): File {
        withMain { main -> insertWatchAndAlarm(main) }
        DatabaseSystemBackup(context, keys, MAIN, EXPORT).write()
        val export = context.getDatabasePath(EXPORT)
        assertTrue(isPlainSqlite(export))
        assertEquals(1L, SqlCipherDatabaseFiles(context).fingerprint(export, passphrase = null).tables.getValue("watches").rows)
        context.deleteDatabase(MAIN)
        keyDirectory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, MAIN))
        return export
    }

    // ── Helfer ──

    private fun factory() = EncryptingOpenHelperFactory(context, keys)

    private fun withMain(factory: EncryptingOpenHelperFactory = factory(), block: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, MAIN)
            .openHelperFactory(factory)
            .build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    private fun insertWatchAndAlarm(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO exchanges (id, exchangeKey, updateDate) VALUES (1, 'Binance', 1)")
        db.execSQL(
            "INSERT INTO watches (id, exchangeKey, exchangeName, baseAsset, quoteAsset, contractType, pairId, sortOrder, " +
                "notificationEnabled, ttsEnabled, lastPrice, previousPrice, lastUpdate, notifiedPrice, notifiedAt, lastError) " +
                "VALUES (1, 'Binance', 'Binance', 'BTC', 'USDT', 'NONE', 'BTCUSDT', 0, 1, 0, 98000.0, 97000.0, " +
                "1700000000000, 97500.0, 1700000000000, NULL)"
        )
        db.execSQL(
            "INSERT INTO alarms (watchId, condition, threshold, enabled, repeating, sound, vibrate, speak, " +
                "referencePrice, lastTriggeredAt, lastTriggeredPrice) VALUES (1, 'PRICE_BELOW', 90000.0, 1, 0, 1, 1, 0, NULL, 0, NULL)"
        )
    }

    /** Beginnt [file] mit dem Kopf einer unverschlüsselten SQLite-Datei? */
    private fun isPlainSqlite(file: File): Boolean =
        file.length() >= 16 && String(file.readBytes().copyOf(16), Charsets.US_ASCII) == "SQLite format 3\u0000"

    /**
     * Verschlüsselt (kein SQLite-Kopf), keine Kopie und kein unverschlüsseltes WAL daneben, und das Android-SQLite liest
     * die Datei ohne Schlüssel nicht. Eigener Fehlerbehandler: Der Standard löschte die «beschädigte» Datei.
     */
    private fun assertEncryptedAndUnreadableWithoutKey(name: String) {
        val file = context.getDatabasePath(name)
        assertEquals(name, FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(file))
        assertFalse(name, isPlainSqlite(file))
        assertFalse(name, File(file.path + SqlCipherDatabaseFiles.COPY_SUFFIX).exists())
        val walHead = File(file.path + "-wal").takeIf { it.length() > 0 }?.readBytes()?.take(16)
        assertFalse(name, walHead != null && String(walHead.toByteArray(), Charsets.US_ASCII).contains("SQLite format 3"))
        val plainRead = runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { },
            ).use { db -> db.rawQuery("SELECT COUNT(*) FROM sqlite_master", null).use { it.moveToFirst() } }
        }
        assertTrue("$name ohne Schlüssel lesbar", plainRead.isFailure)
        assertTrue(name, file.exists())
    }

    private fun count(db: SupportSQLiteDatabase, from: String): Int =
        db.query("SELECT COUNT(*) FROM $from").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val MAIN = "encryption-test.db"
        const val EXPORT = "encryption-test-backup.db"
        const val TEST_ALIAS = "cryptochecker_db_key_test"
    }
}
