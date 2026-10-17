package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseKeyStore
import com.cryptochecker.app.data.local.EncryptingOpenHelperFactory
import com.cryptochecker.app.data.local.SqlCipherDatabaseFiles
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Überführung echter Datenbankdateien eines Testgeräts (Build 57/58, vom Inhaber geliefert): `cryptochecker.db` und
 * `cryptochecker_portfolio.db` samt `-wal`/`-shm` unter `app/src/androidTest/assets/realdb/` ablegen (nur Testgerät,
 * keine echten Bestände ins Repository). Fehlen sie, wird der Test übersprungen.
 *
 * Geprüft: Jede Tabelle hat nach Verschlüsselung und Room-Migrationen mindestens so viele Zeilen wie vorher (Room-Migrationen
 * fügen höchstens hinzu; die Merkliste-/Portfolio-Tabellen exakt gleich viele), beide Dateien sind verschlüsselt.
 */
@RunWith(AndroidJUnit4::class)
class RealDatabaseEncryptionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val keyDirectory: File get() = File(context.noBackupFilesDir, "db_keys_realdb_test")

    @Before
    @After
    fun clean() {
        listOf(MAIN, PORTFOLIO).forEach { context.deleteDatabase(it) }
        keyDirectory.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(MAIN, PORTFOLIO).forEach { keyStore.deleteEntry(DatabaseKeyStore.aliasFor(TEST_ALIAS, it)) }
    }

    @Test
    fun realDeviceDatabasesAreEncryptedWithoutLoss() {
        val available = testAssets.list(ASSET_DIR).orEmpty().toSet()
        assumeTrue("Keine echten Dateien unter assets/$ASSET_DIR", AppDatabase.DB_NAME in available && PortfolioDatabase.DB_NAME in available)

        // Unter Testnamen ablegen, Nebendateien (-wal/-shm) mit gleichem Stamm — sonst fehlten die letzten Änderungen
        for ((source, target) in listOf(AppDatabase.DB_NAME to MAIN, PortfolioDatabase.DB_NAME to PORTFOLIO)) {
            for (suffix in listOf("", "-wal", "-shm")) {
                if (source + suffix !in available) continue
                val out = File(context.getDatabasePath(target).path + suffix)
                out.parentFile?.mkdirs()
                testAssets.open("$ASSET_DIR/$source$suffix").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        val files = SqlCipherDatabaseFiles(context)
        val mainBefore = files.fingerprint(context.getDatabasePath(MAIN), passphrase = null)
        val portfolioBefore = files.fingerprint(context.getDatabasePath(PORTFOLIO), passphrase = null)

        val factory = EncryptingOpenHelperFactory(context, DatabaseKeyStore(context, alias = TEST_ALIAS, directory = keyDirectory))
        val main = Room.databaseBuilder(context, AppDatabase::class.java, MAIN)
            .openHelperFactory(factory)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val portfolio = Room.databaseBuilder(context, PortfolioDatabase::class.java, PORTFOLIO)
            .openHelperFactory(factory)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, main, File(context.cacheDir, "realdb-mirror.json")))
            .build()
        try {
            val portfolioDb = portfolio.openHelper.writableDatabase
            val mainDb = main.openHelper.writableDatabase
            for ((before, db) in listOf(mainBefore to mainDb, portfolioBefore to portfolioDb)) {
                for ((table, fp) in before.tables) {
                    if (table.startsWith("legacy_")) continue // übernimmt PortfolioLegacyImport in die Portfolio-Datenbank
                    val rows = db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getLong(0) }
                    if (table in EXACT_TABLES) assertEquals(table, fp.rows, rows) else assertTrue(table, rows >= fp.rows)
                }
            }
        } finally {
            portfolio.close()
            main.close()
        }
        assertEquals(FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(context.getDatabasePath(MAIN)))
        assertEquals(FileKind.ENCRYPTED, SqlCipherDatabaseFiles.kindOf(context.getDatabasePath(PORTFOLIO)))
    }

    private companion object {
        const val ASSET_DIR = "realdb"
        const val MAIN = "realdb-test.db"
        const val PORTFOLIO = "realdb-test-portfolio.db"
        const val TEST_ALIAS = "cryptochecker_db_key_realdb_test"
        /** Nutzerdaten: exakt gleich viele Zeilen (Zwischenspeicher wie market_pairs dürfen sich ändern). */
        val EXACT_TABLES = setOf("watches", "alarms", "portfolio_tx", "portfolio_alarms", "watched_addresses")
    }
}
