package com.cryptochecker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.CsvImportWritten
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.data.portfolio.insertImportedLinked
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvAbsorbedRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportCancelledException
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Anlegen importierter Zeilen (PortfolioRepository.insertImported, insertImportedLinked) mit echter In-Memory-Datenbank:
 * «Abbrechen» nach einigen Zeilen und eine ungültige Zeile lassen die Tabelle leer (alles zurückgerollt) und kommen als
 * Fehler an; Verknüpfung und Zeilen sind ein Schreibvorgang, eine nicht mehr verknüpfbare Seite behält ihre aufgehende
 * Zeile; Preise behalten ihre gültigen Stellen.
 */
@RunWith(AndroidJUnit4::class)
class PortfolioImportInsertTest {

    private lateinit var db: PortfolioDatabase
    private lateinit var repository: PortfolioRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PortfolioDatabase::class.java).build()
        repository = PortfolioRepository(db, db.getPortfolioDao(), db.getWatchedAddressDao())
    }

    @After
    fun close() {
        db.close()
    }

    private fun row(i: Int, amount: String = "0.5") = PortfolioTxEntity(
        coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal(amount), priceUsdt = BigDecimal("30000"),
        time = 1_700_000_000_000L + i, importKey = "test-$i",
    )

    @Test
    fun allRowsAreSaved() = runBlocking {
        assertEquals(5, repository.insertImported((1..5).map { row(it) }, batch = "b1"))
        assertEquals(5, db.getPortfolioDao().getAll().size)
    }

    @Test
    fun cancelledAfterSomeRowsLeavesNothing() = runBlocking {
        var done = 0
        try {
            repository.insertImported((1..10).map { row(it) }, batch = "b1", onRow = { done = it }, cancelled = { done >= 3 })
            fail("Abbruch erwartet")
        } catch (_: CsvImportCancelledException) {
            // erwartet
        }
        assertEquals(3, done)
        assertEquals(0, db.getPortfolioDao().getAll().size)
    }

    @Test
    fun invalidRowFailsWithoutWriting() = runBlocking {
        val rows = (1..4).map { row(it) } + row(5, amount = "0")
        try {
            repository.insertImported(rows, batch = "b1")
            fail("Fehler erwartet")
        } catch (_: IllegalArgumentException) {
            // erwartet: nicht still übersprungen und als Anzahl gemeldet
        }
        assertEquals(0, db.getPortfolioDao().getAll().size)
    }

    /** Gespeicherte Auszahlung, gegen die eine neue Einzahlung aufgeht (`CsvStoredTransfer` mit Ersatzzeile). */
    private fun link(storedId: Long, deposit: PortfolioTxEntity) = CsvStoredTransfer(
        id = storedId, amount = BigDecimal("0.0049"), fee = BigDecimal("0.0001"), feeUnit = PortfolioFeeUnit.COIN,
        feeCoin = null, feeCoinAmount = null, time = 1_700_000_000_000L, transferFrom = "Binance", transferTo = "Coinbase",
        importKey = "BINANCE|W1", linkedImportKey = deposit.importKey!!,
        restoreState = CsvRestoreState(
            type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("0.005"), priceUsdt = null, time = 1_700_000_000_000L,
            fee = null, feeUnit = null, feeCoin = null, feeCoinAmount = null, feeCoinPrice = null, transferFrom = null,
            transferTo = null, acquiredTime = null, exchange = null, importKey = "BINANCE|W1", importBatch = "b0",
            absorbed = CsvAbsorbedRow(PortfolioTxType.DEPOSIT, BigDecimal("0.0049"), null, deposit.time, null, null, null, null,
                deposit.importKey!!),
        ),
    )

    private val deposit = PortfolioTxEntity(
        coin = "BTC", type = PortfolioTxType.DEPOSIT, amount = BigDecimal("0.0049"), time = 1_700_000_900_000L,
        importKey = "COINBASE|D1",
    )

    private val withdrawal = PortfolioTxEntity(
        coin = "BTC", type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("0.005"), time = 1_700_000_000_000L,
        importKey = "BINANCE|W1", importBatch = "b0",
    )

    @Test
    fun linkAndRowsInOneTransaction() = runBlocking {
        val id = db.getPortfolioDao().insert(withdrawal)
        val written = repository.insertImportedLinked(
            listOf(row(1), deposit), "b1", listOf(link(id, deposit)), mapOf(deposit.importKey!! to id),
        )
        assertEquals(CsvImportWritten(inserted = 1, linked = 1), written)
        val all = db.getPortfolioDao().getAll()
        assertEquals(2, all.size)
        assertEquals(PortfolioTxType.TRANSFER, all.single { it.id == id }.type)
        assertNull("Einzahlung ging in der Umbuchung auf", all.firstOrNull { it.importKey == deposit.importKey })
    }

    @Test
    fun deletedStoredSideKeepsTheAbsorbedRow() = runBlocking {
        val written = repository.insertImportedLinked(
            listOf(row(1), deposit), "b1", listOf(link(99L, deposit)), mapOf(deposit.importKey!! to 99L),
        )
        assertEquals(CsvImportWritten(inserted = 2, linked = 0), written)
        val kept = db.getPortfolioDao().getAll().single { it.importKey == deposit.importKey }
        assertEquals(PortfolioTxType.DEPOSIT, kept.type)
        assertEquals("b1", kept.importBatch)
    }

    @Test
    fun cancelRollsBackTheLinkToo() = runBlocking {
        val id = db.getPortfolioDao().insert(withdrawal)
        try {
            repository.insertImportedLinked(
                listOf(row(1), deposit), "b1", listOf(link(id, deposit)), mapOf(deposit.importKey!! to id), cancelled = { true },
            )
            fail("Abbruch erwartet")
        } catch (_: CsvImportCancelledException) {
            // erwartet
        }
        assertEquals(listOf(PortfolioTxType.WITHDRAWAL), db.getPortfolioDao().getAll().map { it.type })
    }

    @Test
    fun priceKeepsItsSignificantDigits() = runBlocking {
        val price = BigDecimal("0.000000001371733333333333333333333333")
        repository.insertImported(listOf(row(1).copy(priceUsdt = price)), batch = "b1")
        assertEquals(0, price.compareTo(db.getPortfolioDao().getAll().single().priceUsdt))
    }

    /** Room schreibt Mengen, Gebühren und Werte mit der Regel der Mengen (18 Nachkommastellen), Preise mit ihrer. */
    @Test
    fun roomRoundsAmountsButKeepsPrices() = runBlocking {
        val price = BigDecimal("0.000000001371733333333333333333333333")
        db.getPortfolioDao().insert(
            row(1, amount = "1.0000000000000000004").copy(
                priceUsdt = price, fee = BigDecimal("0.0000000000000000015"), feeUnit = PortfolioFeeUnit.COIN.name,
                feeCoin = "BNB", feeCoinAmount = BigDecimal("1E-19"), feeCoinPriceUsdt = price,
            ),
        )
        val stored = db.getPortfolioDao().getAll().single()
        assertEquals(0, BigDecimal.ONE.compareTo(stored.amount))
        assertEquals(0, BigDecimal("0.000000000000000002").compareTo(stored.fee))
        assertEquals(0, BigDecimal.ZERO.compareTo(stored.feeCoinAmount))
        assertEquals(0, price.compareTo(stored.priceUsdt))
        assertEquals(0, price.compareTo(stored.feeCoinPriceUsdt))
    }
}
