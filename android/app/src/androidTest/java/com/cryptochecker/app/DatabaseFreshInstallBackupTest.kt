package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioBackupMirror
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvAbsorbedRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/**
 * Systemsicherung des Portfolios im Rundlauf: [PortfolioBackupMirror.writeForBackup] (Sicherungs-Agent) schreibt die
 * Kopie aus einer Datenbank, [PortfolioBackupMirror.importInto] (Anlegen der Datenbank auf dem neuen Gerät) übernimmt sie
 * — jede Spalte von `portfolio_tx` muss gleich zurückkommen. Die Testdaten belegen JEDE Spalte in mindestens einer Zeile
 * mit einem Wert ungleich null/0 ([everyTxColumnIsCovered]): Eine neue Spalte, die das handgeschriebene INSERT von
 * [PortfolioBackupMirror.importInto] nicht kennt, macht diesen Test rot, bis Testdaten und INSERT sie führen.
 * Eigene Datenbank- und Dateinamen: die Datenbank der App bleibt unberührt (unverschlüsselt geöffnet — die
 * Verschlüsselung prüft `DatabaseEncryptionTest`).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseFreshInstallBackupTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mirrorFile: File get() = File(context.cacheDir, "portfolio-mirror-roundtrip.json")

    @Before
    @After
    fun clean() {
        context.deleteDatabase(SOURCE_DB)
        context.deleteDatabase(TARGET_DB)
        mirrorFile.delete()
        File(context.cacheDir, "${mirrorFile.name}.tmp").delete()
        File(context.cacheDir, PortfolioBackupMirror.FAILED_FILE_NAME).delete()
    }

    @Test
    fun everyTxColumnIsCovered() {
        withDatabase(SOURCE_DB) { room ->
            insertAll(room)
            val db = room.openHelper.writableDatabase
            val columns = columnsOf(db, "portfolio_tx")
            assertTrue("portfolio_tx ohne Spalten", columns.isNotEmpty())
            val uncovered = columns.filterNot { column -> hasValue(db, column) }
            assertTrue("Spalten ohne Wert in den Testdaten (TXS ergänzen, INSERT in importInto prüfen): $uncovered",
                uncovered.isEmpty())
        }
    }

    @Test
    fun systemBackupRoundTripKeepsEveryColumn() {
        withDatabase(SOURCE_DB) { room -> insertAll(room) }

        // Sicherungs-Agent: Kopie aus der bestehenden Datenbank
        PortfolioBackupMirror.writeForBackup(context, SOURCE_DB, mirrorFile, FrameworkSQLiteOpenHelperFactory())
        assertTrue("Kopie nicht geschrieben", mirrorFile.isFile)
        assertFalse(File(context.cacheDir, "${mirrorFile.name}.tmp").exists())
        val content = PortfolioBackupMirror.parse(mirrorFile.readText())
        assertEquals(0, content.invalid)
        assertEquals(TXS, content.txs.sortedBy { it.id })

        // Neues Gerät: die Datenbank wird angelegt und übernimmt die Kopie (Callback wie die App)
        val restored = Room.databaseBuilder(context, PortfolioDatabase::class.java, TARGET_DB)
            .addCallback(PortfolioDatabase.callback(context, mirrorFile))
            .build()
        try {
            val txs = runBlocking { restored.getPortfolioDao().getAll() }.sortedBy { it.id }
            assertEquals(TXS, txs)
            // Alarme scharf und ohne letzte Meldung zurück
            val alarms = runBlocking { restored.getPortfolioAlarmDao().getAll() }.sortedBy { it.id }
            assertEquals(ALARMS.map { it.copy(referenceAt = 0, lastTriggeredAt = 0, lastTriggeredValue = null) }, alarms)
            // Auch Spalte für Spalte (Speicherform wie Room): dieselben Zeilen wie in der Quelle
            withDatabase(SOURCE_DB) { source ->
                assertEquals(rows(source.openHelper.writableDatabase), rows(restored.openHelper.writableDatabase))
            }
        } finally {
            restored.close()
        }
        // Übernommen: die Klartext-Kopie ist weg
        assertFalse(mirrorFile.exists())
    }

    @Test
    fun writeForBackupLeavesPendingRestoreAndSkipsWithoutDatabase() {
        // Ohne Datenbank und ohne Kopie: nichts geschrieben
        PortfolioBackupMirror.writeForBackup(context, SOURCE_DB, mirrorFile, FrameworkSQLiteOpenHelperFactory())
        assertFalse(mirrorFile.exists())
        // Noch nicht übernommene Wiederherstellung (Kopie da, Datenbank nicht): bleibt unverändert
        mirrorFile.writeText("pending")
        PortfolioBackupMirror.writeForBackup(context, SOURCE_DB, mirrorFile, FrameworkSQLiteOpenHelperFactory())
        assertEquals("pending", mirrorFile.readText())
        assertFalse(context.getDatabasePath(SOURCE_DB).exists())
        // Mit Datenbank: eine alte Kopie wird ersetzt
        withDatabase(SOURCE_DB) { room -> insertAll(room) }
        PortfolioBackupMirror.writeForBackup(context, SOURCE_DB, mirrorFile, FrameworkSQLiteOpenHelperFactory())
        assertEquals(TXS, PortfolioBackupMirror.parse(mirrorFile.readText()).txs.sortedBy { it.id })
    }

    // ── Helfer ──

    private fun withDatabase(name: String, block: (PortfolioDatabase) -> Unit) {
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, name).build()
        try {
            block(room)
        } finally {
            room.close()
        }
    }

    private fun insertAll(room: PortfolioDatabase) = runBlocking {
        TXS.forEach { room.getPortfolioDao().insert(it) }
        ALARMS.forEach { room.getPortfolioAlarmDao().insert(it) }
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    /** Mindestens eine Zeile mit einem Wert ungleich null, 0 und leer. */
    private fun hasValue(db: SupportSQLiteDatabase, column: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM portfolio_tx WHERE `$column` IS NOT NULL AND `$column` != 0 AND `$column` != '0' " +
                "AND `$column` != ''"
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }

    /** Alle Zeilen von `portfolio_tx` als Text je Spalte, nach Id. */
    private fun rows(db: SupportSQLiteDatabase): List<Map<String, String?>> =
        db.query("SELECT * FROM portfolio_tx ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.getString(i) })
                }
            }
        }

    private companion object {
        const val SOURCE_DB = "system-backup-roundtrip-source.db"
        const val TARGET_DB = "system-backup-roundtrip-target.db"
        const val T = 1_700_000_000_000L

        /** Je Art die Felder, die nur dort gelten — zusammen jede Spalte mindestens einmal belegt, alle Werte verschieden. */
        val TXS = listOf(
            // Tausch mit Gebühr im dritten Coin, Herkunft, Importlauf, Verknüpfung und Zustand davor
            PortfolioTxEntity(
                id = 11, coin = "ETH", type = PortfolioTxType.SWAP, amount = BigDecimal("1.5"),
                priceUsdt = BigDecimal("3000.25"), time = T, note = "Tausch", fee = BigDecimal("0.01"),
                feeUnit = PortfolioFeeUnit.COIN.name, exchange = "Binance", feeCoin = "BNB",
                feeCoinAmount = BigDecimal("0.002"), feeCoinPriceUsdt = BigDecimal("600.5"), importKey = "binance:swap:1",
                toCoin = "SOL", toAmount = BigDecimal("30.75"), swapValueUsdt = BigDecimal("4500.4"),
                importBatch = "binance:1700000000000:1", linkedImportKey = "kraken:deposit:1",
                importRestoreState = CsvRestoreState(
                    type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1.6"), priceUsdt = BigDecimal("2900.5"),
                    time = T - 1_000, fee = BigDecimal("1.2"), feeUnit = PortfolioFeeUnit.QUOTE, feeCoin = "BNB",
                    feeCoinAmount = BigDecimal("0.003"), feeCoinPrice = BigDecimal("590.5"), transferFrom = "Binance",
                    transferTo = "Kraken", acquiredTime = T - 5_000, exchange = "Binance", importKey = "binance:swap:1",
                    importBatch = "binance:1690000000000:1",
                    absorbed = CsvAbsorbedRow(
                        type = PortfolioTxType.DEPOSIT, amount = BigDecimal("1.55"), priceUsdt = BigDecimal("2950.5"),
                        time = T - 500, fee = BigDecimal("0.004"), feeUnit = PortfolioFeeUnit.COIN, feeCoin = "KFEE",
                        feeCoinAmount = BigDecimal("2.5"), importKey = "kraken:deposit:1",
                    ),
                ),
            ),
            // Staking-Umbuchung von/nach
            PortfolioTxEntity(
                id = 12, coin = "BTC", type = PortfolioTxType.TRANSFER, amount = BigDecimal("0.25"), time = T + 1,
                transferFrom = "Kraken Staking", transferTo = "Kraken", stakingMove = true,
            ),
            // Zins
            PortfolioTxEntity(
                id = 13, coin = "ADA", type = PortfolioTxType.REWARD, amount = BigDecimal("12.5"),
                priceUsdt = BigDecimal("0.45"), time = T + 2, exchange = "Nexo", interest = true,
            ),
            // Einzahlung mit ursprünglichem Kaufdatum
            PortfolioTxEntity(
                id = 14, coin = "BTC", type = PortfolioTxType.DEPOSIT, amount = BigDecimal("0.1"), time = T + 3,
                acquiredTime = 1_600_000_000_000L,
            ),
            // Schenkung an den Partner
            PortfolioTxEntity(
                id = 15, coin = "BTC", type = PortfolioTxType.GIFT, amount = BigDecimal("0.05"), time = T + 4,
                giftToSpouse = true,
            ),
            // Gebühr-Zeile (gespeichert als Verkauf)
            PortfolioTxEntity(
                id = 16, coin = "BTC", type = PortfolioTxType.SELL, amount = BigDecimal("0.0002"),
                priceUsdt = BigDecimal("65000.5"), time = T + 5, feeRow = true,
            ),
        )

        val ALARMS = listOf(
            PortfolioAlarmEntity(
                id = 7, kind = PortfolioAlarmKind.VALUE_ABOVE, threshold = 12_345.5, currency = "CHF", enabled = false,
                repeating = true, referenceAt = 11, lastTriggeredAt = 22, lastTriggeredValue = 33.0,
            ),
            PortfolioAlarmEntity(id = 8, kind = PortfolioAlarmKind.CHANGE_DOWN, threshold = 7.5),
        )
    }
}
