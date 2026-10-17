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
import com.cryptochecker.app.data.portfolio.toDomain
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
 * (sonst «Migration didn't properly handle …»). So ist die ganze Kette 3 → 15 und der reale
 * Update-Weg der Version 16.2.x (Datenbank v10/v12/v13/v14 → v15) geprüft, ohne die Schema-Dateien 4–12.
 * Ab v13 liegt das Portfolio in einer eigenen Datei (PortfolioDatabase); die Tests unten prüfen
 * auch die einmalige Übernahme (PortfolioLegacyImport) samt Wiederholung und Systemsicherungs-Kopie.
 *
 * Schema v3 entspricht `app/schemas/…/3.json`; v10/v12 entstehen daraus mit den App-Migrationen.
 * Schema v1 entspricht `app/schemas/…/1.json`: damit laufen auch MIGRATION_1_2 und MIGRATION_2_3
 * (die ganze Kette 1 → 15) durch Room. Die Schema-Dateien 4–15 (AppDatabase) und 1 (PortfolioDatabase)
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
            db.query("SELECT disabled FROM watches LIMIT 1").close()
            db.query("SELECT priceChangedAt, exchangeTime FROM watches LIMIT 1").close()
            // Portfolio liegt nicht (mehr) in der Hauptdatenbank
            assertFalse(tableExists(db, "portfolio_tx"))
            assertFalse(tableExists(db, "portfolio_alarms"))
            assertFalse(tableExists(db, PortfolioLegacyImport.LEGACY_TX))
        }
    }

    @Test
    fun upgradeFromVersion13AddsDisabledKeepsEverythingElse() {
        oldDatabase(version = 13) { db ->
            insertV3Data(db)
            db.execSQL("UPDATE watches SET favorite = 1, groupName = 'Layer 1', note = 'Ø 77 500', change24h = 2.5")
        }

        withRoom { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            // 13 → 14: bestehende Paare bleiben aktiv, alles andere unverändert
            db.query("SELECT disabled, favorite, groupName, note, change24h, lastPrice FROM watches").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(1, c.getInt(1))
                assertEquals("Layer 1", c.getString(2))
                assertEquals("Ø 77 500", c.getString(3))
                assertEquals(2.5, c.getDouble(4), 0.0)
                assertEquals(98_000.0, c.getDouble(5), 0.0)
            }
            db.query("SELECT threshold, enabled FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(90_000.0, c.getDouble(0), 0.0)
                assertEquals(1, c.getInt(1))
            }
            // Neue Paare ohne Angabe: aktiv (Standardwert der Spalte)
            db.execSQL(
                "INSERT INTO watches (marketKey, marketName, baseAsset, quoteAsset, contractType, sortOrder, " +
                    "notificationEnabled, ttsEnabled, lastUpdate, notifiedAt) " +
                    "VALUES ('Kraken', 'Kraken', 'ETH', 'EUR', 'NONE', 1, 1, 0, 0, 0)"
            )
            db.query("SELECT disabled FROM watches WHERE marketKey = 'Kraken'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun upgradeFromVersion14AddsPriceFreshnessKeepsEverythingElse() {
        oldDatabase(version = 14) { db ->
            insertV3Data(db)
            db.execSQL("UPDATE watches SET disabled = 1, favorite = 1, note = 'Ø 77 500', change24h = 2.5")
        }

        withRoom { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            // 14 → 15: Kursänderung unbekannt (0, setzt die nächste Aktualisierung), keine Börsenzeit
            db.query(
                "SELECT priceChangedAt, exchangeTime, disabled, favorite, note, change24h, lastPrice, lastUpdate FROM watches"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0L, c.getLong(0))
                assertTrue(c.isNull(1))
                assertEquals(1, c.getInt(2))
                assertEquals(1, c.getInt(3))
                assertEquals("Ø 77 500", c.getString(4))
                assertEquals(2.5, c.getDouble(5), 0.0)
                assertEquals(98_000.0, c.getDouble(6), 0.0)
                assertEquals(1_700_000_000_000L, c.getLong(7))
            }
            db.query("SELECT threshold, enabled FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(90_000.0, c.getDouble(0), 0.0)
                assertEquals(1, c.getInt(1))
            }
            // Neue Paare ohne Angabe: Standardwerte der Spalten
            db.execSQL(
                "INSERT INTO watches (marketKey, marketName, baseAsset, quoteAsset, contractType, sortOrder, " +
                    "notificationEnabled, ttsEnabled, lastUpdate, notifiedAt) " +
                    "VALUES ('Kraken', 'Kraken', 'ETH', 'EUR', 'NONE', 1, 1, 0, 0, 0)"
            )
            db.query("SELECT priceChangedAt, exchangeTime FROM watches WHERE marketKey = 'Kraken'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0L, c.getLong(0))
                assertTrue(c.isNull(1))
            }
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
            // Hauptdatenbank verdichtet (Merker gesetzt: kein erneutes VACUUM bei jedem Start)
            assertEquals(
                1,
                count(portfolio, "${PortfolioLegacyImport.META_TABLE} WHERE `key` = '${PortfolioLegacyImport.VACUUM_DONE_KEY}'")
            )
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
    fun portfolioVersion1BecomesExactTextWithFeeAndExchange() {
        // Portfolio-Datenbank v1 (REAL-Spalten) wie aus 16.2.2 bis Build 34
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_PORTFOLIO_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `portfolio_tx` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`coin` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `priceUsdt` REAL, " +
                            "`time` INTEGER NOT NULL, `note` TEXT)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `portfolio_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`kind` TEXT NOT NULL, `threshold` REAL NOT NULL, `currency` TEXT, `enabled` INTEGER NOT NULL, " +
                            "`repeating` INTEGER NOT NULL, `referenceAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER NOT NULL, " +
                            "`lastTriggeredValue` REAL)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.apply {
            execSQL("INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) VALUES (3, 'BTC', 'BUY', 0.1, 60000.5, 1, 'a')")
            execSQL("INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) VALUES (9, 'BTC', 'BUY', 0.00000001, NULL, 2, NULL)")
        }
        helper.close()

        val main = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, main, mirrorFile))
            .build()
        try {
            // Room prüft nach der Migration das Schema gegen die Entity (TEXT-Spalten, neue Spalten)
            val db = room.openHelper.writableDatabase
            db.query("SELECT id, amount, priceUsdt, fee, feeUnit, exchange FROM portfolio_tx ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3L, c.getLong(0))
                assertEquals(0, java.math.BigDecimal("0.1").compareTo(java.math.BigDecimal(c.getString(1))))
                assertEquals(0, java.math.BigDecimal("60000.5").compareTo(java.math.BigDecimal(c.getString(2))))
                assertTrue(c.isNull(3)); assertTrue(c.isNull(4)); assertTrue(c.isNull(5))
                assertTrue(c.moveToNext())
                assertEquals(0, java.math.BigDecimal("0.00000001").compareTo(java.math.BigDecimal(c.getString(1))))
                assertTrue(c.isNull(2))
            }
            // Neue Ids laufen nach der höchsten übernommenen weiter
            db.execSQL("INSERT INTO portfolio_tx (coin, type, amount, time) VALUES ('ETH', 'BUY', '1', 3)")
            db.query("SELECT MAX(id) FROM portfolio_tx").use { c -> assertTrue(c.moveToFirst()); assertEquals(10L, c.getLong(0)) }
        } finally {
            room.close()
            main.close()
        }
    }

    @Test
    fun portfolioVersion2AddsTransferFeeImportKeyAndWatchedAddresses() {
        // Portfolio-Datenbank v2 (Text-Spalten, Gebühr, Börse) wie bis Build 45
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_PORTFOLIO_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `portfolio_tx` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`coin` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` TEXT NOT NULL, `priceUsdt` TEXT, " +
                            "`time` INTEGER NOT NULL, `note` TEXT, `fee` TEXT, `feeUnit` TEXT, `exchange` TEXT)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `portfolio_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`kind` TEXT NOT NULL, `threshold` REAL NOT NULL, `currency` TEXT, `enabled` INTEGER NOT NULL, " +
                            "`repeating` INTEGER NOT NULL, `referenceAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER NOT NULL, " +
                            "`lastTriggeredValue` REAL)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.execSQL(
            "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, fee, feeUnit, exchange) " +
                "VALUES (4, 'BTC', 'BUY', '0.1', '60000.5', 1, 'a', '1.5', 'QUOTE', 'Kraken')"
        )
        helper.close()

        val main = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, main, mirrorFile))
            .build()
        try {
            // Room prüft nach der Migration das Schema gegen die Entity (neue Spalten)
            val db = room.openHelper.writableDatabase
            db.query(
                "SELECT id, amount, fee, exchange, transferFrom, transferTo, feeCoin, feeCoinAmount, feeCoinPriceUsdt, " +
                    "importKey, acquiredTime, toCoin, toAmount, swapValueUsdt, importBatch FROM portfolio_tx"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(4L, c.getLong(0))
                assertEquals("0.1", c.getString(1))
                assertEquals("1.5", c.getString(2))
                assertEquals("Kraken", c.getString(3))
                for (i in 4..14) assertTrue(c.isNull(i))
            }
            // Index für die Suche nach Doppelten (CSV-Import) und neue Tabelle der beobachteten Adressen
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_portfolio_tx_importKey'")
                .use { c -> assertTrue(c.moveToFirst()) }
            assertEquals(0, count(db, "watched_addresses"))
            db.query("SELECT observations FROM watched_addresses").use { c -> assertEquals(1, c.columnCount) }
            // Neue Art mit den neuen Spalten über Room
            val dao = room.getPortfolioDao()
            kotlinx.coroutines.runBlocking {
                dao.insert(
                    com.cryptochecker.app.data.portfolio.PortfolioTxEntity(
                        coin = "BTC", type = com.cryptochecker.app.domain.portfolio.PortfolioTxType.TRANSFER,
                        amount = java.math.BigDecimal("0.1"), time = 2, transferFrom = "Kraken", transferTo = "Ledger",
                        feeCoin = "BNB", feeCoinAmount = java.math.BigDecimal("0.00000001"), importKey = "relai:abc",
                    )
                )
                val back = dao.getAll().first { it.type == com.cryptochecker.app.domain.portfolio.PortfolioTxType.TRANSFER }
                assertEquals("Ledger", back.transferTo)
                assertEquals(0, java.math.BigDecimal("0.00000001").compareTo(back.feeCoinAmount))
                assertEquals(null, back.feeCoinPriceUsdt)
                assertEquals("relai:abc", back.importKey)
                // Einzahlung mit ursprünglichem Kaufdatum (Spalte `acquiredTime`, gleiche Migration MIGRATION_2_3)
                dao.insert(
                    com.cryptochecker.app.data.portfolio.PortfolioTxEntity(
                        coin = "ETH", type = com.cryptochecker.app.domain.portfolio.PortfolioTxType.DEPOSIT,
                        amount = java.math.BigDecimal("1"), time = 3, acquiredTime = 1_600_000_000_000L,
                    )
                )
                assertEquals(
                    1_600_000_000_000L,
                    dao.getAll().first { it.type == com.cryptochecker.app.domain.portfolio.PortfolioTxType.DEPOSIT }.acquiredTime,
                )
                // Tausch und Importlauf (Spalten `toCoin`, `toAmount`, `swapValueUsdt`, `importBatch`, gleiche Migration)
                dao.insert(
                    com.cryptochecker.app.data.portfolio.PortfolioTxEntity(
                        coin = "BTC", type = com.cryptochecker.app.domain.portfolio.PortfolioTxType.SWAP,
                        amount = java.math.BigDecimal("0.01"), time = 4, toCoin = "ETH",
                        toAmount = java.math.BigDecimal("0.25000001"), swapValueUsdt = java.math.BigDecimal("600.5"),
                        importBatch = "RELAI:5:0000abcd",
                    )
                )
                val swap = dao.getAll().first { it.type == com.cryptochecker.app.domain.portfolio.PortfolioTxType.SWAP }
                assertEquals("ETH", swap.toCoin)
                assertEquals(0, java.math.BigDecimal("0.25000001").compareTo(swap.toAmount))
                assertEquals(0, java.math.BigDecimal("600.5").compareTo(swap.swapValueUsdt))
                assertEquals(1, dao.getByImportBatch("RELAI:5:0000abcd").size)
                assertEquals(1, dao.deleteImportBatch("RELAI:5:0000abcd"))
                assertTrue(dao.getAll().none { it.type == com.cryptochecker.app.domain.portfolio.PortfolioTxType.SWAP })
                // Beobachtete Adressen über Room; gleiche Adresse ein zweites Mal wird ignoriert
                val addresses = room.getWatchedAddressDao()
                val row = com.cryptochecker.app.data.portfolio.WatchedAddressEntity(
                    chain = "BTC", address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label = "Cold", addedAt = 5,
                )
                assertTrue(addresses.insert(row) > 0)
                assertEquals(-1L, addresses.insert(row))
                assertEquals("Cold", addresses.getAll().single().label)
                // Beobachtete Guthaben (Spalte `observations`, JSON) — gleiche Migration MIGRATION_2_3
                val id = addresses.getAll().single().id
                assertEquals(null, addresses.getObservations(id))
                addresses.setObservations(id, "[{\"t\":5,\"b\":\"0.1\"}]")
                assertEquals(
                    listOf(com.cryptochecker.app.domain.portfolio.AddressObservation(5, java.math.BigDecimal("0.1"))),
                    addresses.getAll().single().toDomain()!!.observations,
                )
            }
        } finally {
            room.close()
            main.close()
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
                        amount = java.math.BigDecimal("2"), priceUsdt = java.math.BigDecimal("3000"),
                        time = 1_700_000_000_000L, note = "Ledger",
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
                assertEquals("2", c.getString(2)) // seit Portfolio-Datenbank v2 exakt als Text
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

    /**
     * Portfolio-Datenbank v3 → v4 (Build 58): Partner-Merker «@spouse» im Feld «nach» und die in `importKey` gepackten
     * Verknüpfungen (beide Schlüssel, Zustand Fassung 1 und 2) werden eigene Spalten; alles andere bleibt unverändert.
     */
    @Test
    fun portfolioVersion3MovesLegacyFormsIntoColumns() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_PORTFOLIO_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = PORTFOLIO_V3_SCHEMA.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.apply {
            val insert = "INSERT INTO portfolio_tx (id, coin, type, amount, time, transferFrom, transferTo, importKey, importBatch) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            execSQL(insert, arrayOf<Any?>(1, "BTC", "GIFT", "0.5", 10, null, "@spouse", null, null))
            execSQL(insert, arrayOf<Any?>(2, "BTC", "TRANSFER", "1", 20, "Kraken", "Coinbase", "GENERIC|W ⇄ GENERIC|D", "GENERIC:1:00000001"))
            execSQL(insert, arrayOf<Any?>(3, "BTC", "TRANSFER", "0.005", 1704987000000L, "Binance", "Coinbase", V2_PACKED, "COINBASE:1729100000000:1b2c3d4e"))
            execSQL(insert, arrayOf<Any?>(4, "BTC", "TRANSFER", "0.005", 1704987000000L, "Binance", "Coinbase", V1_PACKED, "COINBASE:1729100000000:1b2c3d4e"))
            execSQL(insert, arrayOf<Any?>(5, "ETH", "TRANSFER", "2", 30, "Kraken", "@spouse", "KRAKEN|R1", null))
            execSQL(insert, arrayOf<Any?>(6, "ETH", "BUY", "1", 40, null, null, null, null))
        }
        helper.close()

        val main = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, main, mirrorFile))
            .build()
        try {
            // Room prüft nach der Migration das Schema gegen die Entity (neue Spalten samt DEFAULT 0)
            val db = room.openHelper.writableDatabase
            db.query("SELECT id, transferTo, giftToSpouse, importKey, linkedImportKey, importRestoreState FROM portfolio_tx ORDER BY id").use { c ->
                val rows = buildList {
                    while (c.moveToNext()) add(List(6) { i -> if (c.isNull(i)) null else c.getString(i) })
                }
                assertEquals(listOf("1", null, "1", null, null, null), rows[0])
                assertEquals(listOf("2", "Coinbase", "0", "GENERIC|W", "GENERIC|D", null), rows[1])
                assertEquals(listOf("3", "Coinbase", "0", "BINANCE|W", "COINBASE|D"), rows[2].take(5))
                assertTrue(rows[2][5]!!.startsWith("{\"v\":1"))
                assertEquals(listOf("4", "Coinbase", "0", "BINANCE|W5", "COINBASE|D5"), rows[3].take(5))
                // Kein Merker ausserhalb einer Schenkung: Umbuchungsziel «@spouse» bleibt Text
                assertEquals(listOf("5", "@spouse", "0", "KRAKEN|R1", null, null), rows[4])
                assertEquals(listOf("6", null, "0", null, null, null), rows[5])
            }
            kotlinx.coroutines.runBlocking {
                val dao = room.getPortfolioDao()
                val all = dao.getAll().associateBy { it.id }
                assertTrue(all.getValue(1).giftToSpouse)
                // Zustand Fassung 2 (Build 57): gespeicherte Auszahlung und aufgegangene Einzahlung
                val v2 = all.getValue(3).importRestoreState!!
                assertEquals(com.cryptochecker.app.domain.portfolio.PortfolioTxType.WITHDRAWAL, v2.type)
                assertEquals("BINANCE|W", v2.importKey)
                assertEquals("BINANCE:1729000000000:0a1b2c3d", v2.importBatch)
                assertEquals("COINBASE|D", v2.absorbed!!.importKey)
                assertEquals(0, java.math.BigDecimal("0.0049").compareTo(v2.absorbed!!.amount))
                // Fassung 1 (Build 56): nur die gespeicherte Seite
                val v1 = all.getValue(4).importRestoreState!!
                assertEquals("BINANCE|W5", v1.importKey)
                assertEquals(null, v1.absorbed)
                // Doppelte: beide Seiten verknüpfter Umbuchungen gelten als importiert
                assertTrue(dao.getImportKeys().containsAll(listOf("GENERIC|W", "GENERIC|D", "BINANCE|W", "COINBASE|D", "KRAKEN|R1")))
                assertTrue(dao.getImportKeys().none { "⇄" in it || "⟲" in it })
            }
        } finally {
            room.close()
            main.close()
        }
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

    internal companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_PORTFOLIO_DB = "migration-test-portfolio.db"

        /** Portfolio-Datenbank v3, wie in `app/schemas/com.cryptochecker.app.data.portfolio.PortfolioDatabase/3.json`. */
        val PORTFOLIO_V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `portfolio_tx` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `coin` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `amount` TEXT NOT NULL, `priceUsdt` TEXT, `time` INTEGER NOT NULL, `note` TEXT, `fee` TEXT, " +
                "`feeUnit` TEXT, `exchange` TEXT, `transferFrom` TEXT, `transferTo` TEXT, `feeCoin` TEXT, `feeCoinAmount` TEXT, " +
                "`feeCoinPriceUsdt` TEXT, `importKey` TEXT, `acquiredTime` INTEGER, `toCoin` TEXT, `toAmount` TEXT, " +
                "`swapValueUsdt` TEXT, `importBatch` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_portfolio_tx_importKey` ON `portfolio_tx` (`importKey`)",
            "CREATE TABLE IF NOT EXISTS `portfolio_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`threshold` REAL NOT NULL, `currency` TEXT, `enabled` INTEGER NOT NULL, `repeating` INTEGER NOT NULL, " +
                "`referenceAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER NOT NULL, `lastTriggeredValue` REAL)",
            "CREATE TABLE IF NOT EXISTS `watched_addresses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chain` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, `label` TEXT, `lastBalance` TEXT, `lastUpdated` INTEGER NOT NULL, " +
                "`lastAttempt` INTEGER NOT NULL, `lastError` TEXT, `lastProvider` TEXT, `addedAt` INTEGER NOT NULL, `observations` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_watched_addresses_chain_address` ON `watched_addresses` (`chain`, `address`)",
        )

        /** Verknüpfung mit Zustand Fassung 2 im Schlüssel (Build 57; wie `legacy_storage_migration.json`). */
        const val V2_PACKED = "BINANCE|W ⇄ COINBASE|D ⟲ 2|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000" +
            "||||||||| ⟲ DEPOSIT|0.0049||1704989100000||||"

        /** Verknüpfung mit Zustand Fassung 1 im Schlüssel (Build 56). */
        const val V1_PACKED = "BINANCE|W5 ⇄ COINBASE|D5 ⟲ 1|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000|||||||||"

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
