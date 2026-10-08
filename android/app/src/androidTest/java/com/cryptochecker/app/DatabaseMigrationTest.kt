package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Update-Pfad der Datenbank: eine alte Datenbank anlegen, mit Daten füllen und dann von Room
 * öffnen lassen. Room führt die Migrationen aus und prüft danach das Schema gegen die Entities
 * (sonst «Migration didn't properly handle …»). So ist die ganze Kette 3 → 12 und der reale
 * Update-Weg der Version 16.2.x (Datenbank v10 → v12) geprüft, ohne die Schema-Dateien 4–11.
 *
 * Schema v3 entspricht `app/schemas/…/3.json`; v10 entsteht daraus mit den App-Migrationen.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clean() {
        context.deleteDatabase(TEST_DB)
    }

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
            assertEquals(0, count(db, "portfolio_tx"))
            assertEquals(0, count(db, "portfolio_alarms"))
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
            db.query("SELECT coin, amount FROM portfolio_tx").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("BTC", c.getString(0))
                assertEquals(0.05, c.getDouble(1), 0.0)
            }
            assertEquals(0, count(db, "portfolio_alarms"))
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
            assertEquals(0, count(db, "portfolio_alarms"))
        }
    }

    // ── Helfer ──

    /** Legt die Datenbank mit Schema v3 an, bringt sie mit den App-Migrationen auf [version] und füllt sie. */
    private fun oldDatabase(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = V3_SCHEMA.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        AppDatabase.ALL_MIGRATIONS
            .filter { it.startVersion >= 3 && it.endVersion <= version }
            .forEach { it.migrate(db) }
        db.execSQL("PRAGMA user_version = $version")
        fill(db)
        helper.close()
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

    private fun count(db: SupportSQLiteDatabase, from: String): Int =
        db.query("SELECT COUNT(*) FROM $from").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val TEST_DB = "migration-test.db"

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
