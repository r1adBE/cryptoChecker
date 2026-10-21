package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Seit Build 102: «Coin löschen» entfernt auch die Gebühr in diesem Coin bei Transaktionen anderer Coins (sonst erschiene
 * der Coin als Position «mehr abgegeben als erfasst»). Wie iOS (`PortfolioDeleteCoinFeeTests`).
 */
class PortfolioCoinDeletionTest {

    private val buyBnb = PortfolioTxEntity(id = 1, coin = "BNB", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1L)
    private val transfer = PortfolioTxEntity(
        id = 2, coin = "ETH", type = PortfolioTxType.TRANSFER, amount = BigDecimal.ONE, time = 2L,
        feeCoin = "bnb", feeCoinAmount = BigDecimal("0.01"), feeCoinPriceUsdt = BigDecimal(600),
    )
    private val buyEth = PortfolioTxEntity(id = 3, coin = "ETH", type = PortfolioTxType.BUY, amount = BigDecimal(2), time = 3L)
    private val swapToBnb = PortfolioTxEntity(
        id = 4, coin = "ETH", type = PortfolioTxType.SWAP, amount = BigDecimal.ONE, time = 4L, toCoin = "BNB", toAmount = BigDecimal(3),
    )

    @Test
    fun zaehltGeloeschteUndGebuehrBezuegeGetrennt() {
        val all = listOf(buyBnb, transfer, buyEth, swapToBnb)
        // Gelöscht: Kauf BNB und Tausch in BNB; nur die Gebühr verliert die ETH-Umbuchung
        assertEquals(2 to 1, PortfolioCoinDeletion.counts(all, "bnb"))
        assertEquals(3 to 0, PortfolioCoinDeletion.counts(all, "ETH"))
    }

    @Test
    fun gebuehrBezugNurBeiAnderemCoin() {
        assertTrue(PortfolioCoinDeletion.paysFeeIn(transfer, "BNB"))
        assertFalse(PortfolioCoinDeletion.paysFeeIn(transfer, "ETH"))
        assertFalse(PortfolioCoinDeletion.paysFeeIn(buyEth, "BNB"))
        // Ohne Menge keine Gebühr
        assertFalse(PortfolioCoinDeletion.paysFeeIn(transfer.copy(feeCoinAmount = null), "BNB"))
    }

    @Test
    fun ohneGebuehrBleibtDerRestUnveraendert() {
        val clean = PortfolioCoinDeletion.withoutThirdFee(transfer)
        assertNull(clean.feeCoin)
        assertNull(clean.feeCoinAmount)
        assertNull(clean.feeCoinPriceUsdt)
        assertEquals(transfer.copy(feeCoin = null, feeCoinAmount = null, feeCoinPriceUsdt = null), clean)
        assertEquals(transfer.id, clean.id)
    }
}
