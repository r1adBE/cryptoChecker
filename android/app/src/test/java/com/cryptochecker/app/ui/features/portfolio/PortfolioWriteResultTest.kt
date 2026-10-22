package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.local.DatabaseKeyUnavailableException
import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Seit Build 103: Schreibwege im Portfolio melden ihr Ergebnis statt Fehler zu verschlucken ([portfolioWrite]); die
 * Coin-Ansicht rechnet ausserhalb der Komposition dasselbe wie vorher ([coinDetail]).
 */
class PortfolioWriteResultTest {

    /** Schreibweg wie im Repository: gibt die gelöschten Transaktionen zurück oder wirft. */
    private class FakeRepository(var failure: Exception? = null) {
        val stored = mutableListOf(tx(1, "BTC"), tx(2, "ETH"))
        fun deleteCoin(coin: String): List<PortfolioTxEntity> {
            failure?.let { throw it }
            return stored.filter { it.coin == coin }.also { stored.removeAll(it) }
        }
    }

    @Test
    fun okReturnsValue() = runBlocking {
        val repo = FakeRepository()
        val (result, removed) = portfolioWrite("Test") { repo.deleteCoin("BTC") }
        assertEquals(PortfolioWriteResult.OK, result)
        assertEquals(listOf(1L), removed!!.map { it.id })
        assertEquals(listOf(2L), repo.stored.map { it.id })
    }

    @Test
    fun lockedStorageIsLocked() = runBlocking {
        val repo = FakeRepository(PortfolioStorageLockedException())
        val (result, removed) = portfolioWrite("Test") { repo.deleteCoin("BTC") }
        assertEquals(PortfolioWriteResult.LOCKED, result)
        assertNull(removed)
        assertEquals(2, repo.stored.size)
    }

    @Test
    fun unavailableKeyIsLocked() = runBlocking {
        val wrapped = IllegalStateException("Room", DatabaseKeyUnavailableException("portfolio", RuntimeException()))
        val (result, _) = portfolioWrite("Test") { FakeRepository(wrapped).deleteCoin("BTC") }
        assertEquals(PortfolioWriteResult.LOCKED, result)
    }

    @Test
    fun otherErrorIsFailed() = runBlocking {
        val (result, removed) = portfolioWrite("Test") { FakeRepository(java.io.IOException("Platte voll")).deleteCoin("BTC") }
        assertEquals(PortfolioWriteResult.FAILED, result)
        assertNull(removed)
    }

    @Test
    fun lockedAndFailedNameTheirReason() {
        assertEquals(com.cryptochecker.app.R.string.portfolio_storage_locked, PortfolioWriteResult.LOCKED.notSavedText())
        assertEquals(com.cryptochecker.app.R.string.portfolio_write_failed, PortfolioWriteResult.FAILED.notSavedText())
    }

    @Test
    fun cancellationIsRethrown() {
        try {
            runBlocking { portfolioWrite("Test") { throw CancellationException("weg") } }
            fail("Abbruch muss weitergereicht werden")
        } catch (e: CancellationException) {
            assertEquals("weg", e.message)
        }
    }

    @Test
    fun coinDetailMatchesDirectCalculation() {
        val all = listOf(tx(1, "BTC", PortfolioTxType.BUY, "0.5", "20000"), tx(2, "ETH"), tx(3, "BTC", PortfolioTxType.SELL, "0.1", "30000"))
        val detail = coinDetail("BTC", all, all, emptyList(), 40_000.0, CostMethod.FIFO, null)
        assertEquals(listOf(1L, 3L), detail.own.map { it.id })
        assertEquals(PortfolioCalculator.ledger("BTC", all.map { it.toTrade() }, 40_000.0, CostMethod.FIFO, null), detail.ledger)
        assertNull(detail.addressAmount)
        assertNull(detail.lotAddress)
    }

    private companion object {
        fun tx(id: Long, coin: String, type: PortfolioTxType = PortfolioTxType.BUY, amount: String = "1", price: String? = "100") =
            PortfolioTxEntity(id = id, coin = coin, type = type, amount = BigDecimal(amount), priceUsdt = price?.let(::BigDecimal), time = id * 1_000L)
    }
}
