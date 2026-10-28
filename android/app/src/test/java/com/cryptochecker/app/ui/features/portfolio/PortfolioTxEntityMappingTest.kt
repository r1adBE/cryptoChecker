package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Gespeicherte Transaktion → Entwurf des Erfassen-Blatts ([toDraft]) und → mögliches Gegenstück einer Umbuchung beim
 * CSV-Import ([toStoredMove]): alle Felder kommen mit, die Gebühr-Einheit wird aus dem Namen gelesen.
 */
class PortfolioTxEntityMappingTest {

    private val full = PortfolioTxEntity(
        id = 17,
        coin = "BTC",
        type = PortfolioTxType.WITHDRAWAL,
        amount = BigDecimal("0.25"),
        priceUsdt = BigDecimal("60000"),
        time = 1_700_000_000_000L,
        note = "Cold Wallet",
        fee = BigDecimal("0.0001"),
        feeUnit = "COIN",
        exchange = "binance",
        transferFrom = "Binance",
        transferTo = "Ledger",
        feeCoin = "BNB",
        feeCoinAmount = BigDecimal("0.01"),
        feeCoinPriceUsdt = BigDecimal("600"),
        importKey = "binance:42",
        acquiredTime = 1_600_000_000_000L,
        toCoin = "ETH",
        toAmount = BigDecimal("4"),
        swapValueUsdt = BigDecimal("15000"),
        importBatch = "batch-1",
        giftToSpouse = true,
        linkedImportKey = "binance:43",
        feeRow = true,
        interest = true,
    )

    // ---- Entwurf ----

    @Test
    fun toDraftCopiesAllFields() {
        val draft = full.toDraft()
        assertEquals(17L, draft.id)
        assertEquals("BTC", draft.coin)
        assertEquals(PortfolioTxType.WITHDRAWAL, draft.type)
        assertEquals(BigDecimal("0.25"), draft.amount)
        assertEquals(BigDecimal("60000"), draft.priceUsdt)
        assertEquals(1_700_000_000_000L, draft.time)
        assertEquals("Cold Wallet", draft.note)
        assertEquals(BigDecimal("0.0001"), draft.fee)
        assertEquals(PortfolioFeeUnit.COIN, draft.feeUnit)
        assertEquals("binance", draft.exchange)
        assertEquals("Binance", draft.transferFrom)
        assertEquals("Ledger", draft.transferTo)
        assertEquals("BNB", draft.feeCoin)
        assertEquals(BigDecimal("0.01"), draft.feeCoinAmount)
        assertEquals(BigDecimal("600"), draft.feeCoinPriceUsdt)
        assertEquals("binance:42", draft.importKey)
        assertEquals(1_600_000_000_000L, draft.acquiredTime)
        assertEquals("ETH", draft.toCoin)
        assertEquals(BigDecimal("4"), draft.toAmount)
        assertEquals(BigDecimal("15000"), draft.swapValueUsdt)
        assertEquals("batch-1", draft.importBatch)
        assertTrue(draft.giftToSpouse)
        assertEquals("binance:43", draft.linkedImportKey)
        assertNull(draft.importRestoreState)
        assertTrue(draft.feeRow)
        assertTrue(draft.interest)
    }

    @Test
    fun toDraftReadsMissingOrUnknownFeeUnitAsQuote() {
        val minimal = PortfolioTxEntity(coin = "ETH", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1L)
        val draft = minimal.toDraft()
        assertEquals(PortfolioFeeUnit.QUOTE, draft.feeUnit)
        assertNull(draft.fee)
        assertNull(draft.priceUsdt)
        assertFalse(draft.giftToSpouse)
        assertEquals(PortfolioFeeUnit.QUOTE, minimal.copy(feeUnit = "UNBEKANNT").toDraft().feeUnit)
    }

    // ---- Gegenstück einer Umbuchung ----

    @Test
    fun toStoredMoveForImportedWithdrawal() {
        val stored = full.toStoredMove()
        assertNotNull(stored)
        val move = stored!!
        assertEquals(17L, move.id)
        assertEquals("BTC", move.coin)
        assertEquals(PortfolioTxType.WITHDRAWAL, move.type)
        assertEquals(BigDecimal("0.25"), move.amount)
        assertEquals(BigDecimal("0.0001"), move.fee)
        assertEquals(PortfolioFeeUnit.COIN, move.feeUnit)
        assertEquals("BNB", move.feeCoin)
        assertEquals(BigDecimal("0.01"), move.feeCoinAmount)
        assertEquals(1_700_000_000_000L, move.time)
        assertEquals("binance:42", move.importKey)
        assertEquals("Binance", move.transferFrom)
        assertEquals("Ledger", move.transferTo)
        assertEquals(BigDecimal("60000"), move.priceUsdt)
        assertEquals(BigDecimal("600"), move.feeCoinPrice)
        assertEquals(1_600_000_000_000L, move.acquiredTime)
        assertEquals("binance", move.exchange)
        assertEquals("batch-1", move.importBatch)
        assertEquals("binance:43", move.linkedImportKey)
    }

    @Test
    fun toStoredMoveForDepositAndTransfer() {
        assertEquals(PortfolioTxType.DEPOSIT, full.copy(type = PortfolioTxType.DEPOSIT).toStoredMove()?.type)
        assertEquals(PortfolioTxType.TRANSFER, full.copy(type = PortfolioTxType.TRANSFER).toStoredMove()?.type)
    }

    @Test
    fun toStoredMoveWithoutFeeHasNoFeeUnit() {
        val move = full.copy(fee = null, feeUnit = "COIN").toStoredMove()
        assertNotNull(move)
        assertNull(move!!.fee)
        assertNull(move.feeUnit)
        // Gebühr ohne Einheit: USDT
        assertEquals(PortfolioFeeUnit.QUOTE, full.copy(feeUnit = null).toStoredMove()!!.feeUnit)
    }

    @Test
    fun toStoredMoveNullWithoutImportKeyOrForTrades() {
        assertNull(full.copy(importKey = null).toStoredMove())
        assertNull(full.copy(type = PortfolioTxType.BUY).toStoredMove())
        assertNull(full.copy(type = PortfolioTxType.SELL).toStoredMove())
        assertNull(full.copy(type = PortfolioTxType.SWAP).toStoredMove())
        assertNull(full.copy(type = PortfolioTxType.REWARD).toStoredMove())
    }
}
