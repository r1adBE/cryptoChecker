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
 * Datenbanken mit Room öffnen: Hauptdatenbank bei einer Neuinstallation (Schema-Prüfung gegen die Entities) und
 * Update-Pfad der Portfolio-Datenbank — eine alte Portfolio-Datenbank anlegen, mit Daten füllen und dann von Room
 * öffnen lassen. Room führt die Migrationen aus und prüft danach das Schema gegen die Entities
 * (sonst «Migration didn't properly handle …»). Dazu die Übernahme der Systemsicherungs-Kopie beim Anlegen.
 * Die alten Portfolio-Tabellen legen die Tests hier von Hand an; gegen die exportierten Schemas selbst
 * (Portfolio v1–v5) prüft [RoomSchemaMigrationTest] mit dem MigrationTestHelper.
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
    fun freshInstallOpensAtCurrentVersion() {
        withRoom { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            // Neueste Spalte und Tabelle sind da (die Abfrage schlüge sonst fehl)
            db.query("SELECT tradFi FROM exchange_pairs LIMIT 1").close()
            db.query("SELECT disabled FROM watches LIMIT 1").close()
            db.query("SELECT priceChangedAt, exchangeTime FROM watches LIMIT 1").close()
            // Portfolio liegt in der eigenen Datenbank, nicht in der Hauptdatenbank
            assertFalse(tableExists(db, "portfolio_tx"))
            assertFalse(tableExists(db, "portfolio_alarms"))
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

        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
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

        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
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

        withPortfolio { portfolio ->
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
        // Übernommen: die Klartext-Kopie ist danach gelöscht
        assertFalse(mirrorFile.exists())
        // Nur beim Anlegen: eine spätere Kopie ändert eine bestehende Datenbank nicht
        mirrorFile.writeText(com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.encode(emptyList(), emptyList()))
        withPortfolio { portfolio -> assertEquals(1, count(portfolio, "portfolio_tx")) }
    }

    @Test
    fun unreadableSystemBackupCopyIsSetAsideNotLost() {
        mirrorFile.writeText("{ kaputt")

        withPortfolio { portfolio -> assertEquals(0, count(portfolio, "portfolio_tx")) }
        // Leere Datenbank, aber die Kopie liegt unverändert beiseite (wird von der nächsten Kopie nicht überschrieben)
        assertFalse(mirrorFile.exists())
        val failed = File(context.cacheDir, com.cryptochecker.app.data.portfolio.PortfolioBackupMirror.FAILED_FILE_NAME)
        assertEquals("{ kaputt", failed.readText())

        // Nächster Start: ein zweiter Versuch, dann gelöscht (kein dauerhafter Klartext) — und die App nennt es einmal
        com.cryptochecker.app.data.local.StorageRecoveryNotice.acknowledge(
            context, com.cryptochecker.app.data.local.StorageRecoveryNotice.Kind.SYSTEM_BACKUP_NOT_IMPORTED,
        )
        withPortfolio { portfolio -> assertEquals(0, count(portfolio, "portfolio_tx")) }
        assertFalse(failed.exists())
        assertEquals(
            com.cryptochecker.app.data.local.StorageRecoveryNotice.Kind.SYSTEM_BACKUP_NOT_IMPORTED,
            com.cryptochecker.app.data.local.StorageRecoveryNotice.pending(context),
        )
        com.cryptochecker.app.data.local.StorageRecoveryNotice.acknowledge(
            context, com.cryptochecker.app.data.local.StorageRecoveryNotice.Kind.SYSTEM_BACKUP_NOT_IMPORTED,
        )
    }

    /**
     * Portfolio-Datenbank v3 → v4: Partner-Merker «@spouse» im Feld «nach» und die in `importKey` gepackten
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

        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
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
                // Zustand Fassung 2: gespeicherte Auszahlung und aufgegangene Einzahlung
                val v2 = all.getValue(3).importRestoreState!!
                assertEquals(com.cryptochecker.app.domain.portfolio.PortfolioTxType.WITHDRAWAL, v2.type)
                assertEquals("BINANCE|W", v2.importKey)
                assertEquals("BINANCE:1729000000000:0a1b2c3d", v2.importBatch)
                val absorbed = v2.absorbed!!
                assertEquals("COINBASE|D", absorbed.importKey)
                assertEquals(0, java.math.BigDecimal("0.0049").compareTo(absorbed.amount))
                // Fassung 1: nur die gespeicherte Seite
                val v1 = all.getValue(4).importRestoreState!!
                assertEquals("BINANCE|W5", v1.importKey)
                assertEquals(null, v1.absorbed)
                // Doppelte: beide Seiten verknüpfter Umbuchungen gelten als importiert
                assertTrue(dao.getImportKeys().containsAll(listOf("GENERIC|W", "GENERIC|D", "BINANCE|W", "COINBASE|D", "KRAKEN|R1")))
                assertTrue(dao.getImportKeys().none { "⇄" in it || "⟲" in it })
            }
        } finally {
            room.close()
        }
    }

    /**
     * Portfolio v4 → v5: Gebühr-Zeile und Zins als eigene Merker, einmal nach der früheren Regel abgeleitet —
     * Verkauf zum Preis 0 MIT Herkunft = Gebühr-Zeile (auch «0.00»), von Hand zum Preis 0 nicht; Belohnung mit Notiz
     * «Lending interest» = Zins, Staking nicht.
     */
    @Test
    fun portfolioVersion4DerivesFeeRowAndInterestOnce() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_PORTFOLIO_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    PORTFOLIO_V3_SCHEMA.forEach(db::execSQL)
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `giftToSpouse` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `linkedImportKey` TEXT")
                    db.execSQL("ALTER TABLE `portfolio_tx` ADD COLUMN `importRestoreState` TEXT")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.apply {
            val insert = "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, importKey) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            execSQL(insert, arrayOf<Any?>(1, "BTC", "SELL", "0.0001", "0", 10, "Fee", "KRAKEN|F1"))
            execSQL(insert, arrayOf<Any?>(2, "BTC", "SELL", "0.0002", "0.00", 20, null, "BINANCE|F2"))
            execSQL(insert, arrayOf<Any?>(3, "BTC", "SELL", "0.4", "0", 30, null, null))
            execSQL(insert, arrayOf<Any?>(4, "BTC", "SELL", "0.1", "50000", 40, null, "KRAKEN|S4"))
            execSQL(insert, arrayOf<Any?>(5, "ETH", "REWARD", "0.1", "2500", 50, "Lending interest", "KRAKEN|R5"))
            execSQL(insert, arrayOf<Any?>(6, "ETH", "REWARD", "0.1", "2500", 60, "staking", "KRAKEN|R6"))
            execSQL(insert, arrayOf<Any?>(7, "ETH", "BUY", "1", "2000", 70, "interest", null))
        }
        helper.close()

        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
            .build()
        try {
            // Room prüft nach der Migration das Schema gegen die Entity (zwei neue Spalten samt DEFAULT 0)
            room.openHelper.writableDatabase
            kotlinx.coroutines.runBlocking {
                val all = room.getPortfolioDao().getAll().associateBy { it.id }
                assertEquals(listOf(1L, 2L), all.values.filter { it.feeRow }.map { it.id }.sorted())
                assertEquals(listOf(5L), all.values.filter { it.interest }.map { it.id })
                // Danach zählt nur das Feld: Gebühr-Zeile bleibt eine, auch wenn die Herkunft fehlt
                assertTrue(PortfolioLedgerCheck.isFeeRow(all.getValue(1).copy(importKey = null)))
                assertFalse(PortfolioLedgerCheck.isFeeRow(all.getValue(3)))
            }
        } finally {
            room.close()
        }
    }

    /** Gebühr-Zeile nach der Rechnung (über die Umwandlung in [com.cryptochecker.app.domain.portfolio.PortfolioTrade]). */
    private object PortfolioLedgerCheck {
        fun isFeeRow(t: com.cryptochecker.app.data.portfolio.PortfolioTxEntity): Boolean =
            com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal(t.toTrade())
    }

    // ── Helfer ──

    /**
     * Öffnet die Portfolio-Datenbank wie die App (LocalDataModule): Der erste Zugriff löst die Übernahme einer
     * Systemsicherungs-Kopie aus (Callback), danach prüft der Block die Datenbank.
     */
    private fun withPortfolio(block: (portfolio: SupportSQLiteDatabase) -> Unit) {
        val portfolio = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
            .build()
        try {
            block(portfolio.openHelper.writableDatabase)
        } finally {
            portfolio.close()
        }
    }

    /** Öffnet die Hauptdatenbank wie die App (Schema-Prüfung von Room). */
    private fun withRoom(block: (SupportSQLiteDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB).build()
        try {
            block(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
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

        /** Verknüpfung mit Zustand Fassung 2 im Schlüssel (wie `legacy_storage_migration.json`). */
        const val V2_PACKED = "BINANCE|W ⇄ COINBASE|D ⟲ 2|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000" +
            "||||||||| ⟲ DEPOSIT|0.0049||1704989100000||||"

        /** Verknüpfung mit Zustand Fassung 1 im Schlüssel. */
        const val V1_PACKED = "BINANCE|W5 ⇄ COINBASE|D5 ⟲ 1|BINANCE:1729000000000:0a1b2c3d|WITHDRAWAL|0.005||1704987000000|||||||||"
    }
}
