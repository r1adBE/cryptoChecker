package com.cryptochecker.app

import android.content.Context
import android.database.DatabaseErrorHandler
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseKeyStore
import com.cryptochecker.app.data.local.DatabaseSystemBackup
import com.cryptochecker.app.data.local.EncryptingOpenHelperFactory
import com.cryptochecker.app.data.local.SqlCipherDatabaseFiles
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioLegacyImport
import java.io.File
import java.io.IOException
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verschlüsselung der Datenbanken auf dem Gerät (Build 59): alte unverschlüsselte Dateien (Portfolio v3, Hauptdatenbank v1 und
 * v12 aus 16.2 samt Portfolio-Altbestand) werden beim ersten Öffnen mit echtem SQLCipher und echtem Keystore überführt, die
 * Room-Migrationen laufen danach auf der verschlüsselten Datei. Geprüft: Daten vollständig, keine unverschlüsselte Datei
 * oder Kopie mehr, ohne Schlüssel unlesbar; Fehler lassen die alte Datei stehen; verlorener Schlüssel legt beiseite;
 * Kopie für die Systemsicherung hin und zurück. Die Ablauflogik selbst prüft `DatabaseEncryptionMigrationTest` (Unit).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDirectory: File get() = File(context.noBackupFilesDir, "db_keys_test")
    private val keys get() = DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory)
    private val mirrorFile: File get() = File(context.cacheDir, "portfolio-mirror-encryption-test.json")

    @Before
    @After
    fun clean() {
        for (name in listOf(MAIN, PORTFOLIO, EXPORT)) {
            context.deleteDatabase(name)
            val path = context.getDatabasePath(name).path
            context.getDatabasePath(name).parentFile?.listFiles()
                ?.filter { it.path.startsWith(path + SqlCipherDatabaseFiles.COPY_SUFFIX) || it.path.startsWith(path + SqlCipherDatabaseFiles.ASIDE_SUFFIX) }
                ?.forEach { it.delete() }
        }
        keyDirectory.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Je Datenbank ein eigener Keystore-Schlüssel
        for (name in listOf(MAIN, PORTFOLIO)) keyStore.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, name))
        context.getSharedPreferences(StorageRecoveryNotice.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        mirrorFile.delete()
    }

    @Test
    fun portfolioVersion3IsEncryptedThenMigratedToVersion4() {
        plainDatabase(PORTFOLIO, version = 3, schema = DatabaseMigrationTest.PORTFOLIO_V3_SCHEMA) { db ->
            val insert = "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, transferTo, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            db.execSQL(insert, arrayOf<Any?>(1, "BTC", "BUY", "0.00000001", "65000.5", 10, null, "Satoshi"))
            db.execSQL(insert, arrayOf<Any?>(2, "BTC", "GIFT", "0.5", null, 20, "@spouse", null))
            db.execSQL(insert, arrayOf<Any?>(5, "ETH", "BUY", "2", "3000", 30, null, "Ø 3 000 · ✓"))
            db.execSQL(
                "INSERT INTO portfolio_alarms (id, kind, threshold, currency, enabled, repeating, referenceAt, lastTriggeredAt) " +
                    "VALUES (3, 'VALUE_ABOVE', 10000.0, 'CHF', 1, 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO watched_addresses (chain, address, label, lastUpdated, lastAttempt, addedAt) " +
                    "VALUES ('BTC', 'bc1qtestaddress', 'Cold', 0, 0, 1)"
            )
        }
        val before = SqlCipherDatabaseFiles(context).fingerprint(context.getDatabasePath(PORTFOLIO), passphrase = null)

        withDatabases { _, portfolio ->
            // v3 → v4 lief auf der verschlüsselten Datei (Room prüft das Schema danach)
            assertEquals(PortfolioDatabase.VERSION, portfolio.version)
            portfolio.query("SELECT id, amount, giftToSpouse, transferTo, note FROM portfolio_tx ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(listOf("1", "0.00000001", "0", null, "Satoshi"), row(c, 5))
                assertTrue(c.moveToNext())
                assertEquals(listOf("2", "0.5", "1", null, null), row(c, 5))
                assertTrue(c.moveToNext())
                assertEquals(listOf("5", "2", "0", null, "Ø 3 000 · ✓"), row(c, 5))
                assertFalse(c.moveToNext())
            }
            assertEquals(1, count(portfolio, "portfolio_alarms"))
            assertEquals(1, count(portfolio, "watched_addresses WHERE label = 'Cold'"))
            // AUTOINCREMENT-Zähler übernommen: neue Id nach der höchsten alten
            portfolio.execSQL("INSERT INTO portfolio_tx (coin, type, amount, time) VALUES ('SOL', 'BUY', '1', 40)")
            portfolio.query("SELECT MAX(id) FROM portfolio_tx").use { c -> c.moveToFirst(); assertEquals(6L, c.getLong(0)) }
        }
        // Ausgangslage (Android-SQLite, vor dem ersten Öffnen): 3 Transaktionen, 1 Alarm, 1 Adresse
        assertEquals(5L, before.tables.getValue("portfolio_tx").rows + before.tables.getValue("portfolio_alarms").rows +
            before.tables.getValue("watched_addresses").rows)
        assertEncryptedAndUnreadableWithoutKey(PORTFOLIO)
        assertEncryptedAndUnreadableWithoutKey(MAIN)

        // Zweiter Start: gleicher Schlüssel aus dem Keystore, nichts überführt, alles da
        withDatabases { _, portfolio -> assertEquals(4, count(portfolio, "portfolio_tx")) }
    }

    /** «Datenbank 12» aus 16.2: Portfolio noch in der Hauptdatenbank — Übernahme läuft zwischen zwei verschlüsselten Dateien. */
    @Test
    fun mainDatabaseVersion12From162IsEncryptedAndPortfolioImported() {
        oldMainDatabase(version = 12) { db ->
            insertWatchAndAlarm(db)
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) VALUES (7, 'BTC', 'BUY', 0.05, 77500.0, 1744070400000, 'DCA')"
            )
            db.execSQL(
                "INSERT INTO portfolio_alarms (id, kind, threshold, currency, enabled, repeating, referenceAt, lastTriggeredAt, " +
                    "lastTriggeredValue) VALUES (3, 'VALUE_ABOVE', 10000.0, 'CHF', 1, 0, 0, 0, NULL)"
            )
        }

        withDatabases { main, portfolio ->
            assertEquals(AppDatabase.VERSION, main.version)
            assertEquals(1, count(main, "watches WHERE baseAsset = 'BTC' AND note IS NULL"))
            assertEquals(1, count(main, "alarms WHERE threshold = 90000.0"))
            assertEquals(1, count(portfolio, "portfolio_tx WHERE id = 7 AND note = 'DCA'"))
            assertEquals(1, count(portfolio, "portfolio_alarms WHERE id = 3"))
            assertEquals(0, count(main, "sqlite_master WHERE name = '${PortfolioLegacyImport.LEGACY_TX}'"))
        }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
        assertEncryptedAndUnreadableWithoutKey(PORTFOLIO)
    }

    /** Älteste Hauptdatenbank (v1): die ganze Migrationskette 1 → 15 auf der verschlüsselten Datei. */
    @Test
    fun mainDatabaseVersion1RunsWholeChainEncrypted() {
        plainDatabase(MAIN, version = 1, schema = DatabaseMigrationTest.V1_SCHEMA) { db ->
            db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (1, 'Binance', 1)")
            db.execSQL(
                "INSERT INTO watches (id, marketKey, marketName, baseAsset, quoteAsset, contractType, pairId, sortOrder, " +
                    "notificationEnabled, ttsEnabled, lastPrice, previousPrice, lastUpdate, lastError) " +
                    "VALUES (1, 'Binance', 'Binance', 'BTC', 'USDT', 'NONE', 'BTCUSDT', 0, 1, 0, 98000.0, 97000.0, 1700000000000, NULL)"
            )
        }
        withMain { main ->
            assertEquals(AppDatabase.VERSION, main.version)
            assertEquals(1, count(main, "watches WHERE disabled = 0 AND priceChangedAt = 0"))
        }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
    }

    /** Ein Fehler beim Prüfen der Kopie: alte Datei bleibt, App öffnet sie wie bisher, der nächste Start verschlüsselt. */
    @Test
    fun failedVerificationKeepsPlaintextAndRetriesNextStart() {
        oldMainDatabase(version = 15) { db -> insertWatchAndAlarm(db) }
        val failing = object : com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Files by SqlCipherDatabaseFiles(context) {
            override fun fingerprintExportCopy(database: String, passphrase: ByteArray) = throw IOException("Test: Prüfung scheitert")
        }
        withMain(EncryptingOpenHelperFactory(context, keys, failing)) { main -> assertEquals(1, count(main, "watches")) }
        assertEquals(FileKind.PLAINTEXT, SqlCipherDatabaseFiles.kindOf(context.getDatabasePath(MAIN)))
        assertFalse(File(context.getDatabasePath(MAIN).path + SqlCipherDatabaseFiles.COPY_SUFFIX).exists())

        withMain { main -> assertEquals(1, count(main, "alarms")) }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
    }

    /** Schlüssel weg (Keystore zurückgesetzt): Datei beiseite, nicht gelöscht; App beginnt leer und nennt es einmal. */
    @Test
    fun lostKeySetsDatabaseAsideAndStartsEmpty() {
        withMain { main -> insertWatchAndAlarm(main) }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, MAIN))

        withMain { main -> assertEquals(0, count(main, "watches")) }
        val aside = File(context.getDatabasePath(MAIN).path + SqlCipherDatabaseFiles.ASIDE_SUFFIX)
        assertTrue(aside.exists())
        assertEquals(FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(aside))
        assertTrue(StorageRecoveryNotice.take(context))
        assertFalse(StorageRecoveryNotice.take(context))
    }

    /** Kopie für die Systemsicherung: unverschlüsselt und vollständig; nach der Wiederherstellung wieder verschlüsselt. */
    @Test
    fun systemBackupCopyRoundTrip() {
        withMain { main -> insertWatchAndAlarm(main) }
        val backup = DatabaseSystemBackup(context, keys, MAIN, EXPORT)
        backup.write()
        val export = context.getDatabasePath(EXPORT)
        assertEquals(FileKind.PLAINTEXT, SqlCipherDatabaseFiles.kindOf(export))
        assertEquals(1L, SqlCipherDatabaseFiles(context).fingerprint(export, passphrase = null).tables.getValue("watches").rows)

        // Wiederherstellung in eine frische Installation: keine Datenbank, kein Schlüssel
        context.deleteDatabase(MAIN)
        keyDirectory.deleteRecursively()
        backup.adoptRestored()
        assertFalse(export.exists())
        assertEquals(FileKind.PLAINTEXT, SqlCipherDatabaseFiles.kindOf(context.getDatabasePath(MAIN)))

        withMain { main ->
            assertEquals(1, count(main, "watches"))
            assertEquals(1, count(main, "alarms"))
        }
        assertEncryptedAndUnreadableWithoutKey(MAIN)
        backup.delete()
    }

    // ── Helfer ──

    private fun factory() = EncryptingOpenHelperFactory(context, keys)

    private fun withMain(factory: EncryptingOpenHelperFactory = factory(), block: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, MAIN)
            .openHelperFactory(factory)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    /** Wie die App (LocalDataModule): beide Datenbanken über dieselbe Fabrik, Portfolio-Callback mit Übernahme. */
    private fun withDatabases(block: (main: SupportSQLiteDatabase, portfolio: SupportSQLiteDatabase) -> Unit) {
        val factory = factory()
        val main = Room.databaseBuilder(context, AppDatabase::class.java, MAIN)
            .openHelperFactory(factory)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val portfolio = Room.databaseBuilder(context, PortfolioDatabase::class.java, PORTFOLIO)
            .openHelperFactory(factory)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, main, mirrorFile))
            .build()
        try {
            val portfolioDb = portfolio.openHelper.writableDatabase // öffnet auch die Hauptdatenbank (Callback)
            block(main.openHelper.writableDatabase, portfolioDb)
        } finally {
            portfolio.close()
            main.close()
        }
    }

    /** Unverschlüsselte Datei wie aus einer alten Version (Android-SQLite, ohne Room). */
    private fun plainDatabase(name: String, version: Int, schema: List<String>, fill: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = schema.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        fill(helper.writableDatabase)
        helper.close()
    }

    /** Hauptdatenbank im Schema [version] (aus v3 mit den App-Migrationen, wie `DatabaseMigrationTest`). */
    private fun oldMainDatabase(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        plainDatabase(MAIN, version = 3, schema = DatabaseMigrationTest.V3_SCHEMA) { db ->
            AppDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 3 && it.endVersion <= version }.forEach { it.migrate(db) }
            db.execSQL("PRAGMA user_version = $version")
            fill(db)
        }
    }

    private fun insertWatchAndAlarm(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (1, 'Binance', 1)")
        db.execSQL(
            "INSERT INTO watches (id, marketKey, marketName, baseAsset, quoteAsset, contractType, pairId, sortOrder, " +
                "notificationEnabled, ttsEnabled, lastPrice, previousPrice, lastUpdate, notifiedPrice, notifiedAt, lastError) " +
                "VALUES (1, 'Binance', 'Binance', 'BTC', 'USDT', 'NONE', 'BTCUSDT', 0, 1, 0, 98000.0, 97000.0, " +
                "1700000000000, 97500.0, 1700000000000, NULL)"
        )
        db.execSQL(
            "INSERT INTO alarms (watchId, condition, threshold, enabled, repeating, sound, vibrate, speak, " +
                "referencePrice, lastTriggeredAt, lastTriggeredPrice) VALUES (1, 'PRICE_BELOW', 90000.0, 1, 0, 1, 1, 0, NULL, 0, NULL)"
        )
    }

    /**
     * Verschlüsselt (Kopf), keine Kopie und kein unverschlüsseltes WAL daneben, und das Android-SQLite liest die Datei
     * ohne Schlüssel nicht. Eigener Fehlerbehandler: Der Standard löschte die «beschädigte» Datei.
     */
    private fun assertEncryptedAndUnreadableWithoutKey(name: String) {
        val file = context.getDatabasePath(name)
        assertEquals(name, FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(file))
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

    private fun row(c: android.database.Cursor, columns: Int) = List(columns) { if (c.isNull(it)) null else c.getString(it) }

    private fun count(db: SupportSQLiteDatabase, from: String): Int =
        db.query("SELECT COUNT(*) FROM $from").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val MAIN = "encryption-test.db"
        const val PORTFOLIO = "encryption-test-portfolio.db"
        const val EXPORT = "encryption-test-backup.db"
        const val TEST_ALIAS = "cryptochecker_db_key_test"
    }
}
