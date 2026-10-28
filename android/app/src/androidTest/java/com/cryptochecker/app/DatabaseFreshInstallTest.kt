package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * Datenbanken mit Room öffnen wie bei einer Neuinstallation: Hauptdatenbank und Portfolio-Datenbank (Room prüft das
 * Schema gegen die Entities), dazu die Übernahme der Systemsicherungs-Kopie beim Anlegen der Portfolio-Datenbank. Gegen
 * die exportierten Schemas selbst prüft [RoomSchemaMigrationTest].
 */
@RunWith(AndroidJUnit4::class)
class DatabaseFreshInstallTest {

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
            // Spalten von Paarliste und Merkliste sind da (die Abfrage schlüge sonst fehl)
            db.query("SELECT tradFi FROM exchange_pairs LIMIT 1").close()
            db.query("SELECT disabled FROM watches LIMIT 1").close()
            db.query("SELECT priceChangedAt, exchangeTime FROM watches LIMIT 1").close()
            // Portfolio liegt in der eigenen Datenbank, nicht in der Hauptdatenbank
            assertFalse(tableExists(db, "portfolio_tx"))
            assertFalse(tableExists(db, "portfolio_alarms"))
        }
    }

    @Test
    fun freshPortfolioDatabaseStoresAllColumns() {
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, TEST_PORTFOLIO_DB)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(PortfolioDatabase.VERSION, db.version)
            // Index für die Suche nach Doppelten (CSV-Import) und Tabelle der beobachteten Adressen
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_portfolio_tx_importKey'")
                .use { c -> assertTrue(c.moveToFirst()) }
            assertEquals(0, count(db, "watched_addresses"))
            db.query("SELECT observations FROM watched_addresses").use { c -> assertEquals(1, c.columnCount) }
            // Umbuchung mit Gebühr-Coin und Herkunft über Room
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
                // Einzahlung mit ursprünglichem Kaufdatum (Spalte `acquiredTime`)
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
                // Tausch und Importlauf (Spalten `toCoin`, `toAmount`, `swapValueUsdt`, `importBatch`)
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
                // Beobachtete Guthaben (Spalte `observations`, JSON)
                val id = addresses.getAll().single().id
                assertEquals(null, addresses.getObservations(id))
                addresses.setObservations(id, "[{\"t\":5,\"b\":\"0.1\"}]")
                assertEquals(
                    listOf(com.cryptochecker.app.domain.portfolio.AddressObservation(5, java.math.BigDecimal("0.1"))),
                    addresses.getAll().single().toDomain()!!.observations,
                )
                // Eröffnungsposten (Spalte `costLots`, JSON); ein Abruf (observations) lässt sie stehen
                val lot = com.cryptochecker.app.domain.portfolio.AddressCostLot(
                    1, "BTC", 1_600_000_000_000L, java.math.BigDecimal("0.3"), java.math.BigDecimal("10000"),
                )
                addresses.setCostLots(id, com.cryptochecker.app.domain.portfolio.AddressCostLots.encode(listOf(lot)))
                addresses.setObservations(id, "[{\"t\":6,\"b\":\"0.2\"}]")
                assertEquals(listOf(lot), addresses.getById(id)!!.toDomain()!!.costLots)
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
                assertEquals("2", c.getString(2)) // exakt als Text
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
    }
}
