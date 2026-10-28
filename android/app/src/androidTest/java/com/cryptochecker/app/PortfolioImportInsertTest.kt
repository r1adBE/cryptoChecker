package com.cryptochecker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportCancelledException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Anlegen importierter Zeilen (PortfolioRepository.insertImported) mit echter In-Memory-Datenbank: «Abbrechen» nach
 * einigen Zeilen und eine ungültige Zeile lassen die Tabelle leer (alles zurückgerollt) und kommen als Fehler an.
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
}
