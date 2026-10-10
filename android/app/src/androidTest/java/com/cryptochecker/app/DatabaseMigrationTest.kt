package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioLegacyImport
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Update-Pfad der Datenbank: eine alte Datenbank anlegen, mit Daten füllen und dann von Room
 * öffnen lassen. Room führt die Migrationen aus und prüft danach das Schema gegen die Entities
 * (sonst «Migration didn't properly handle …»). So ist die ganze Kette 3 → 13 und der reale
 * Update-Weg der Version 16.2.x (Datenbank v10/v12 → v13) geprüft, ohne die Schema-Dateien 4–12.
 * Ab v13 liegt das Portfolio in einer eigenen Datei (PortfolioDatabase); die Tests unten prüfen
 * auch die einmalige Übernahme (PortfolioLegacyImport) samt Wiederholung und Systemsicherungs-Kopie.
 *
 * Schema v3 entspricht `app/schemas/…/3.json`; v10/v12 entstehen daraus mit den App-Migrationen.
 * Schema v1 entspricht `app/schemas/…/1.json`: damit laufen auch MIGRATION_1_2 und MIGRATION_2_3
 * (die ganze Kette 1 → 13) durch Room. Die Schema-Dateien 4–13 (AppDatabase) und 1 (PortfolioDatabase)
 * schreibt der nächste Gradle-Build (Room-Plugin, exportSchema); sie gehören ins Repository.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clean() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(TEST_PORTFOLIO_DB)
        mirrorFile.delete()
        File(context.cacheDir, com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.FAILED_FILE_NAME).delete()
    }

    private val mirrorFile: File get() = File(context.cacheDir, "portfolio-mirror-test.json")

    @Test
    fun upgradeFromVersion3KeepsWatchlistAndAlarms() {
        oldDatabase(version = 3) { db -> insertV3Data(db) }

        withRoom { db ->
            db.query("SELECT marketKey, baseAsset, notifiedAt, favorite, groupName, note, change24h FROM watches").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Binance", c.getString(0))
                assertEquals("BTC", c.getString(1))
                assertEquals(1_700_000_000_000L, c.getLong(2))
                assertEquals(0, c.getInt(3)) // favorite: Standard 0
                assertTrue(c.isNull(4))
                assertTrue(c.isNull(5))
                assertTrue(c.isNull(6))
            }
            db.query("SELECT threshold, windowHours, referenceAt, currency FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(90_000.0, c.getDouble(0), 0.0)
                assertEquals(1, c.getInt(1)) // windowHours: Standard 1
                assertEquals(0L, c.getLong(2))
                assertTrue(c.isNull(3))
            }
            // v13: Portfolio-Tabellen nur umbenannt (die Übernahme macht PortfolioLegacyImport)
            assertFalse(tableExists(db, "portfolio_tx"))
            assertEquals(0, count(db, PortfolioLegacyImport.LEGACY_TX))
            assertEquals(0, count(db, PortfolioLegacyImport.LEGACY_ALARMS))
        }
    }

    @Test
    fun upgradeFromVersion1RunsWholeChain() {
        oldDatabase(version = 1) { db -> insertV1Data(db) }

        withRoom { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            db.query("SELECT marketKey, baseAsset, lastPrice, notifiedPrice, notifiedAt, favorite FROM watches").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Binance", c.getString(0))
                assertEquals("BTC", c.getString(1))
                assertEquals(98_000.0, c.getDouble(2), 0.0)
                assertTrue(c.isNull(3))          // 1 → 2: neue Spalte leer
                assertEquals(0L, c.getLong(4))   // 2 → 3: ohne Vergleichskurs kein Zeitstempel
                assertEquals(0, c.getInt(5))
            }
            db.query("SELECT threshold, windowHours FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(90_000.0, c.getDouble(0), 0.0)
                assertEquals(1, c.getInt(1))
            }
            assertFalse(tableExists(db, "portfolio_tx"))
        }
    }

    @Test
    fun upgradeFromVersion2SetsNotifiedAtFromLastUpdate() {
        oldDatabase(version = 2) { db ->
            insertV1Data(db)
            db.execSQL("UPDATE watches SET notifiedPrice = 97500.0")
        }

        withRoom { db ->
            db.query("SELECT notifiedPrice, notifiedAt FROM watches").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(97_500.0, c.getDouble(0), 0.0)
                assertEquals(1_700_000_000_000L, c.getLong(1)) // MIGRATION_2_3: = lastUpdate
            }
        }
    }

    @Test
    fun upgradeFromVersion10KeepsEverythingAndResetsTradFiLists() {
        oldDatabase(version = 10) { db ->
            insertV3Data(db)
            // Spalten und Tabellen, die bis v10 dazukamen
            db.execSQL("UPDATE watches SET favorite = 1, groupName = 'Layer 1', note = 'Ø 77 500', change24h = 2.5")
            db.execSQL("UPDATE alarms SET currency = 'CHF', windowHours = 4")
            db.execSQL(
                "INSERT INTO portfolio_tx (coin, type, amount, priceUsdt, time, note) " +
                    "VALUES ('BTC', 'BUY', 0.05, 77500.0, 1744070400000, NULL)"
            )
            // Futures-Liste (wird von 11 → 12 geleert) und Spot-Liste (bleibt)
            db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (2, 'BinanceFutures', 1)")
            db.execSQL(
                "INSERT INTO market_pairs (marketId, baseAsset, quoteAsset, contractType, marketPairId) " +
                    "VALUES (2, 'BTC', 'USDT', 'PERPETUAL', 'BTCUSDT')"
            )
        }

        withRoom { db ->
            db.query("SELECT favorite, groupName, note, change24h FROM watches").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("Layer 1", c.getString(1))
                assertEquals("Ø 77 500", c.getString(2))
                assertEquals(2.5, c.getDouble(3), 0.0)
            }
            db.query("SELECT currency, windowHours FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("CHF", c.getString(0))
                assertEquals(4, c.getInt(1))
            }
            // Bis zur Übernahme in die Portfolio-Datenbank liegt der Bestand unverändert unter legacy_*
            db.query("SELECT coin, amount FROM ${PortfolioLegacyImport.LEGACY_TX}").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("BTC", c.getString(0))
                assertEquals(0.05, c.getDouble(1), 0.0)
            }
            assertEquals(0, count(db, PortfolioLegacyImport.LEGACY_ALARMS))
            // Spot-Paar bleibt (mit tradFi = 0), Futures-Paar ist weg
            db.query("SELECT baseAsset, tradFi FROM market_pairs WHERE marketId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ETH", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
            assertEquals(0, count(db, "market_pairs WHERE marketId = 2"))
        }
    }

    @Test
    fun freshInstallOpensAtCurrentVersion() {
        withRoom { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            // Neueste Spalte und Tabelle sind da (die Abfrage schlüge sonst fehl)
            db.query("SELECT tradFi FROM market_pairs LIMIT 1").close()
            // Portfolio liegt nicht (mehr) in der Hauptdatenbank
            assertFalse(tableExists(db, "portfolio_tx"))
            assertFalse(tableExists(db, "portfolio_alarms"))
            assertFalse(tableExists(db, PortfolioLegacyImport.LEGACY_TX))
        }
    }

    @Test
    fun upgradeFromVersion12MovesPortfolioIntoOwnDatabaseOnce() {
        oldDatabase(version = 12) { db ->
            insertV3Data(db)
            insertPortfolioV12(db)
        }

        withPortfolio { main, portfolio ->
            // Alte Ids und Werte übernommen
            portfolio.query("SELECT id, coin, type, amount, priceUsdt, time, note FROM portfolio_tx ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7L, c.getLong(0))
                assertEquals("BTC", c.getString(1))
                assertEquals("BUY", c.getString(2))
                assertEquals(0.05, c.getDouble(3), 0.0)
                assertEquals(77_500.0, c.getDouble(4), 0.0)
                assertEquals(1_744_070_400_000L, c.getLong(5))
                assertEquals("DCA", c.getString(6))
                assertTrue(c.moveToNext())
                assertEquals(9L, c.getLong(0))
                assertEquals("SELL", c.getString(2))
                assertTrue(c.isNull(4))
                assertTrue(c.isNull(6))
                assertFalse(c.moveToNext())
            }
            portfolio.query("SELECT id, kind, threshold, currency, enabled, repeating FROM portfolio_alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3L, c.getLong(0))
                assertEquals("VALUE_ABOVE", c.getString(1))
                assertEquals(10_000.0, c.getDouble(2), 0.0)
                assertEquals("CHF", c.getString(3))
                assertEquals(1, c.getInt(4))
                assertEquals(0, c.getInt(5))
            }
            // Altbestand aus der Hauptdatenbank entfernt, Merkliste unberührt
            assertFalse(tableExists(main, PortfolioLegacyImport.LEGACY_TX))
            assertFalse(tableExists(main, PortfolioLegacyImport.LEGACY_ALARMS))
            assertEquals(1, count(main, "watches"))
        }

        // Zweites Öffnen: nichts doppelt
        withPortfolio { _, portfolio ->
            assertEquals(2, count(portfolio, "portfolio_tx"))
            assertEquals(1, count(portfolio, "portfolio_alarms"))
            // Neue Transaktion bekommt eine Id nach den übernommenen (AUTOINCREMENT-Zähler übernommen)
            portfolio.execSQL("INSERT INTO portfolio_tx (coin, type, amount, time) VALUES ('ETH', 'BUY', 1.0, 1)")
            portfolio.query("SELECT MAX(id) FROM portfolio_tx").use { c -> c.moveToFirst(); assertEquals(10L, c.getLong(0)) }
        }
    }

    @Test
    fun copiedButNotDroppedOnlyDropsLegacyOnNextStart() {
        // Zustand nach einem Abbruch zwischen Kopie (Merker gesetzt) und Löschen
        oldDatabase(version = 12) { db -> insertPortfolioV12(db) }
        withPortfolio { _, _ -> }
        withRoom { main ->
            main.execSQL("CREATE TABLE ${PortfolioLegacyImport.LEGACY_TX} AS SELECT 99 AS id, 'XRP' AS coin")
        }

        withPortfolio { main, portfolio ->
            assertFalse(tableExists(main, PortfolioLegacyImport.LEGACY_TX))
            assertEquals(2, count(portfolio, "portfolio_tx")) // kein XRP: schon übernommen
        }
    }

    @Test
    fun failedEarlierImportAppendsWithNewIds() {
        // Portfolio-Datenbank hat schon einen Eintrag (Id 7), der Merker fehlt, Altbestand ist da
        withPortfolio { _, portfolio ->
            portfolio.execSQL("INSERT INTO portfolio_tx (id, coin, type, amount, time) VALUES (7, 'SOL', 'BUY', 3.0, 5)")
        }
        withRoom { main ->
            main.execSQL(
                "CREATE TABLE ${PortfolioLegacyImport.LEGACY_TX} (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`coin` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `priceUsdt` REAL, " +
                    "`time` INTEGER NOT NULL, `note` TEXT)"
            )
            main.execSQL("INSERT INTO ${PortfolioLegacyImport.LEGACY_TX} (id, coin, type, amount, time) VALUES (7, 'BTC', 'BUY', 0.1, 1)")
        }

        withPortfolio { main, portfolio ->
            portfolio.query("SELECT coin FROM portfolio_tx ORDER BY id").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("SOL", c.getString(0)) // nichts überschrieben
                assertTrue(c.moveToNext()); assertEquals("BTC", c.getString(0))  // angehängt
            }
            assertFalse(tableExists(main, PortfolioLegacyImport.LEGACY_TX))
        }
    }

    @Test
    fun freshPortfolioDatabaseTakesOverSystemBackupCopy() {
        // Kopie wie von PortfolioBackupMirror geschrieben (Wiederherstellung auf neuem Gerät)
        mirrorFile.writeText(
            com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.encode(
                txs = listOf(
                    com.cryptochecker.app.data.portfolio.PortfolioTxEntity(
                        id = 4, coin = "ETH", type = com.cryptochecker.app.domain.portfolio.PortfolioTxType.BUY,
                        amount = 2.0, priceUsdt = 3_000.0, time = 1_700_000_000_000L, note = "Ledger",
                    )
                ),
                alarms = listOf(
                    com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity(
                        id = 2, kind = com.cryptochecker.app.domain.alarm.PortfolioAlarmKind.VALUE_BELOW,
                        threshold = 5_000.0, currency = "EUR", referenceAt = 123, lastTriggeredAt = 456,
                    )
                ),
            )
        )

        withPortfolio { _, portfolio ->
            portfolio.query("SELECT id, coin, amount, note FROM portfolio_tx").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(4L, c.getLong(0))
                assertEquals("ETH", c.getString(1))
                assertEquals(2.0, c.getDouble(2), 0.0)
                assertEquals("Ledger", c.getString(3))
            }
            // Alarm scharf und ohne letzte Meldung (wie beim Wiederherstellen der eigenen Sicherung)
            portfolio.query("SELECT kind, currency, referenceAt, lastTriggeredAt FROM portfolio_alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("VALUE_BELOW", c.getString(0))
                assertEquals("EUR", c.getString(1))
                assertEquals(0L, c.getLong(2))
                assertEquals(0L, c.getLong(3))
            }
        }
        // Nur beim Anlegen: eine spätere Kopie ändert eine bestehende Datenbank nicht
        mirrorFile.writeText(com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.encode(emptyList(), emptyList()))
        withPortfolio { _, portfolio -> assertEquals(1, count(portfolio, "portfolio_tx")) }
    }

    @Test
    fun unreadableSystemBackupCopyIsSetAsideNotLost() {
        mirrorFile.writeText("{ kaputt")

        withPortfolio { _, portfolio -> assertEquals(0, count(portfolio, "portfolio_tx")) }
        // Leere Datenbank, aber die Kopie liegt unverändert beiseite (wird von der nächsten Kopie nicht überschrieben)
        assertFalse(mirrorFile.exists())
        val failed = File(context.cacheDir, com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.FAILED_FILE_NAME)
        assertEquals("{ kaputt", failed.readText())
    }

    // ── Helfer ──

    /**
     * Legt die Datenbank mit Schema v3 an (unter v3: mit Schema v1), bringt sie mit den App-Migrationen
     * auf [version] und füllt sie.
     */
    private fun oldDatabase(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val base = if (version < 3) 1 else 3
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(base) {
                override fun onCreate(db: SupportSQLiteDatabase) =
                    (if (base == 1) V1_SCHEMA else V3_SCHEMA).forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        AppDatabase.ALL_MIGRATIONS
            .filter { it.startVersion >= base && it.endVersion <= version }
            .forEach { it.migrate(db) }
        db.execSQL("PRAGMA user_version = $version")
        fill(db)
        helper.close()
    }

    /**
     * Öffnet Haupt- und Portfolio-Datenbank wie die App (LocalDataModule): Der erste Zugriff auf die
     * Portfolio-Datenbank löst die Übernahme aus (Callback), danach prüft der Block beide.
     */
    private fun withPortfolio(block: (main: SupportSQLiteDatabase, portfolio: SupportSQLiteDatabase) -> Unit) {
        val main = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val portfolio = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
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

    /** Öffnet die Datenbank wie die App (alle Migrationen, Schema-Prüfung von Room). */
    private fun withRoom(block: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }

    private fun insertV3Data(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (1, 'Binance', 1)")
        db.execSQL(
            "INSERT INTO market_pairs (marketId, baseAsset, quoteAsset, contractType, marketPairId) " +
                "VALUES (1, 'ETH', 'USDT', 'NONE', 'ETHUSDT')"
        )
        db.execSQL(
            "INSERT INTO watches (id, marketKey, marketName, baseAsset, quoteAsset, contractType, pairId, sortOrder, " +
                "notificationEnabled, ttsEnabled, lastPrice, previousPrice, lastUpdate, notifiedPrice, notifiedAt, lastError) " +
                "VALUES (1, 'Binance', 'Binance', 'BTC', 'USDT', 'NONE', 'BTCUSDT', 0, 1, 0, 98000.0, 97000.0, " +
                "1700000000000, 97500.0, 1700000000000, NULL)"
        )
        db.execSQL(
            "INSERT INTO alarms (watchId, condition, threshold, enabled, repeating, sound, vibrate, speak, " +
                "referencePrice, lastTriggeredAt, lastTriggeredPrice) " +
                "VALUES (1, 'PRICE_BELOW', 90000.0, 1, 0, 1, 1, 0, NULL, 0, NULL)"
        )
    }

    /** Ein Paar mit Alarm im Schema v1 (noch ohne notifiedPrice/notifiedAt). */
    private fun insertV1Data(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (1, 'Binance', 1)")
        db.execSQL(
            "INSERT INTO watches (id, marketKey, marketName, baseAsset, quoteAsset, contractType, pairId, sortOrder, " +
                "notificationEnabled, ttsEnabled, lastPrice, previousPrice, lastUpdate, lastError) " +
                "VALUES (1, 'Binance', 'Binance', 'BTC', 'USDT', 'NONE', 'BTCUSDT', 0, 1, 0, 98000.0, 97000.0, " +
                "1700000000000, NULL)"
        )
        db.execSQL(
            "INSERT INTO alarms (watchId, condition, threshold, enabled, repeating, sound, vibrate, speak, " +
                "referencePrice, lastTriggeredAt, lastTriggeredPrice) " +
                "VALUES (1, 'PRICE_BELOW', 90000.0, 1, 0, 1, 1, 0, NULL, 0, NULL)"
        )
    }

    /** Portfolio wie bis v12 in der Hauptdatenbank: zwei Transaktionen (Ids 7, 9) und ein Alarm (Id 3). */
    private fun insertPortfolioV12(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) " +
                "VALUES (7, 'BTC', 'BUY', 0.05, 77500.0, 1744070400000, 'DCA')"
        )
        db.execSQL(
            "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) " +
                "VALUES (9, 'BTC', 'SELL', 0.01, NULL, 1744156800000, NULL)"
        )
        db.execSQL(
            "INSERT INTO portfolio_alarms (id, kind, threshold, currency, enabled, repeating, referenceAt, " +
                "lastTriggeredAt, lastTriggeredValue) VALUES (3, 'VALUE_ABOVE', 10000.0, 'CHF', 1, 0, 0, 0, NULL)"
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table))
            .use { it.moveToFirst() }

    private fun count(db: SupportSQLiteDatabase, from: String): Int =
        db.query("SELECT COUNT(*) FROM $from").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_PORTFOLIO_DB = "migration-test-portfolio.db"

        /** Schema v1, wie in `app/schemas/com.cryptochecker.app.data.local.AppDatabase/1.json`. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `markets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `marketKey` TEXT NOT NULL COLLATE NOCASE, `updateDate` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_markets_marketKey` ON `markets` (`marketKey`)",
            "CREATE TABLE IF NOT EXISTS `market_pairs` (`marketId` INTEGER NOT NULL, `baseAsset` TEXT NOT NULL, `quoteAsset` TEXT NOT NULL, `contractType` TEXT NOT NULL, `marketPairId` TEXT, PRIMARY KEY(`marketId`, `baseAsset`, `quoteAsset`, `contractType`), FOREIGN KEY(`marketId`) REFERENCES `markets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `watches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `marketKey` TEXT NOT NULL, `marketName` TEXT NOT NULL, `baseAsset` TEXT NOT NULL, `quoteAsset` TEXT NOT NULL, `contractType` TEXT NOT NULL, `pairId` TEXT, `sortOrder` INTEGER NOT NULL, `notificationEnabled` INTEGER NOT NULL, `ttsEnabled` INTEGER NOT NULL, `lastPrice` REAL, `previousPrice` REAL, `lastUpdate` INTEGER NOT NULL, `lastError` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_watches_marketKey_baseAsset_quoteAsset_contractType` ON `watches` (`marketKey`, `baseAsset`, `quoteAsset`, `contractType`)",
            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `watchId` INTEGER NOT NULL, `condition` TEXT NOT NULL, `threshold` REAL NOT NULL, `enabled` INTEGER NOT NULL, `repeating` INTEGER NOT NULL, `sound` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `speak` INTEGER NOT NULL, `referencePrice` REAL, `lastTriggeredAt` INTEGER NOT NULL, `lastTriggeredPrice` REAL, FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_alarms_watchId` ON `alarms` (`watchId`)",
        )

        /** Schema v3, wie in `app/schemas/com.cryptochecker.app.data.local.AppDatabase/3.json`. */
        val V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `markets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `marketKey` TEXT NOT NULL COLLATE NOCASE, `updateDate` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_markets_marketKey` ON `markets` (`marketKey`)",
            "CREATE TABLE IF NOT EXISTS `market_pairs` (`marketId` INTEGER NOT NULL, `baseAsset` TEXT NOT NULL, `quoteAsset` TEXT NOT NULL, `contractType` TEXT NOT NULL, `marketPairId` TEXT, PRIMARY KEY(`marketId`, `baseAsset`, `quoteAsset`, `contractType`), FOREIGN KEY(`marketId`) REFERENCES `markets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `watches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `marketKey` TEXT NOT NULL, `marketName` TEXT NOT NULL, `baseAsset` TEXT NOT NULL, `quoteAsset` TEXT NOT NULL, `contractType` TEXT NOT NULL, `pairId` TEXT, `sortOrder` INTEGER NOT NULL, `notificationEnabled` INTEGER NOT NULL, `ttsEnabled` INTEGER NOT NULL, `lastPrice` REAL, `previousPrice` REAL, `lastUpdate` INTEGER NOT NULL, `notifiedPrice` REAL, `notifiedAt` INTEGER NOT NULL, `lastError` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_watches_marketKey_baseAsset_quoteAsset_contractType` ON `watches` (`marketKey`, `baseAsset`, `quoteAsset`, `contractType`)",
            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `watchId` INTEGER NOT NULL, `condition` TEXT NOT NULL, `threshold` REAL NOT NULL, `enabled` INTEGER NOT NULL, `repeating` INTEGER NOT NULL, `sound` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `speak` INTEGER NOT NULL, `referencePrice` REAL, `lastTriggeredAt` INTEGER NOT NULL, `lastTriggeredPrice` REAL, FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_alarms_watchId` ON `alarms` (`watchId`)",
        )
    }
}
