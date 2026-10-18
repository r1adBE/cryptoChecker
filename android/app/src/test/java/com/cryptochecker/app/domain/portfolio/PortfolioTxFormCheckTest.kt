package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxForm.Entry
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm.ThirdFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Prüfung des Erfassen-Blatts ([PortfolioTxForm.check]) ohne Oberfläche. Wie `PortfolioTxFormCheckTests` (iOS). */
class PortfolioTxFormCheckTest {

    private fun dec(s: String) = BigDecimal(s)

    private fun buy(amount: String? = "1") = Entry(type = PortfolioTxType.BUY, coinQuery = "btc", amount = amount?.let(::dec))

    @Test
    fun minimalBuyIsValidWithUnknownPrice() {
        val c = PortfolioTxForm.check(buy())
        assertEquals("BTC", c.coin)
        assertTrue(c.coinValid)
        assertTrue(c.priceValid)
        assertEquals(ThirdFee.NONE, c.thirdFee)
        assertTrue(c.valid)
    }

    @Test
    fun coinAndAmountRequired() {
        assertFalse(PortfolioTxForm.check(buy().copy(coinQuery = "")).valid)
        assertFalse(PortfolioTxForm.check(buy().copy(coinQuery = "BT-C")).coinValid)
        assertFalse(PortfolioTxForm.check(buy().copy(coinQuery = "A".repeat(16))).coinValid)
        assertFalse(PortfolioTxForm.check(buy(amount = null)).valid)
    }

    @Test
    fun unreadablePriceBlocksButTransferHasNoPrice() {
        val unreadable = buy().copy(priceBlank = false, priceEntered = null, price = null)
        assertFalse(PortfolioTxForm.check(unreadable).priceValid)
        assertFalse(PortfolioTxForm.check(unreadable).valid)
        // Umbuchung: kein Preisfeld — ein Rest im Text zählt nicht
        assertTrue(PortfolioTxForm.check(unreadable.copy(type = PortfolioTxType.TRANSFER)).priceValid)
        // Anzeigewährung ohne Kurs: gelesen, aber kein USDT-Preis → ungültig
        assertFalse(PortfolioTxForm.check(buy().copy(priceBlank = false, priceEntered = dec("100"), price = null)).priceValid)
    }

    @Test
    fun priceZeroOnlyBlockedForImportedTransactions() {
        val zero = buy().copy(priceBlank = false, priceEntered = BigDecimal.ZERO, price = BigDecimal.ZERO)
        assertFalse(PortfolioTxForm.check(zero).priceZero)
        assertTrue(PortfolioTxForm.check(zero).valid)
        val imported = PortfolioTxForm.check(zero.copy(imported = true))
        assertTrue(imported.priceZero)
        assertFalse(imported.priceValid)
        assertFalse(imported.valid)
    }

    @Test
    fun feeOnlyForTypesWithFee() {
        // Belohnung hat keine Gebühr: ein getippter Wert zählt nicht, unlesbar sperrt nicht
        val reward = Entry(type = PortfolioTxType.REWARD, coinQuery = "ETH", amount = dec("1"), feeBlank = false, fee = null)
        val r = PortfolioTxForm.check(reward)
        assertFalse(r.hasFee)
        assertNull(r.fee)
        assertTrue(r.feeValid)
        assertTrue(r.valid)
        // Verkauf: unlesbare Gebühr sperrt
        val sell = reward.copy(type = PortfolioTxType.SELL)
        assertFalse(PortfolioTxForm.check(sell).feeValid)
        assertFalse(PortfolioTxForm.check(sell).valid)
        assertEquals(dec("0.1"), PortfolioTxForm.check(sell.copy(fee = dec("0.1"))).fee)
        // Auszahlung: Gebühr immer im Coin
        val withdrawal = sell.copy(type = PortfolioTxType.WITHDRAWAL, fee = dec("0.1"), feeUnit = PortfolioFeeUnit.QUOTE)
        assertEquals(PortfolioFeeUnit.COIN, PortfolioTxForm.check(withdrawal).feeUnit)
    }

    @Test
    fun thirdCoinFeeStates() {
        val base = buy().copy(feeCoin = "bnb", feeCoinAmountBlank = false, feeCoinAmount = dec("0.01"))
        assertEquals(ThirdFee.INCOMPLETE, PortfolioTxForm.check(base).thirdFee)
        assertTrue(PortfolioTxForm.check(base).valid)
        val complete = base.copy(feeCoinPriceBlank = false, feeCoinPrice = dec("600"))
        assertEquals(ThirdFee.COMPLETE, PortfolioTxForm.check(complete).thirdFee)
        // Gleicher Coin wie die Transaktion: nicht speicherbar
        val same = base.copy(feeCoin = "BTC")
        assertEquals(ThirdFee.INVALID, PortfolioTxForm.check(same).thirdFee)
        assertFalse(PortfolioTxForm.check(same).valid)
        // Belohnung kennt keine Gebühr im dritten Coin
        assertEquals(ThirdFee.NONE, PortfolioTxForm.check(same.copy(type = PortfolioTxType.REWARD)).thirdFee)
    }

    @Test
    fun swapChecksReceivedSide() {
        val swap = Entry(
            type = PortfolioTxType.SWAP, coinQuery = "BTC", amount = dec("1"),
            toCoinQuery = "eth", toAmount = dec("20"),
        )
        val ok = PortfolioTxForm.check(swap)
        assertEquals("ETH", ok.toCoin)
        assertTrue(ok.valid)
        assertFalse(PortfolioTxForm.check(swap.copy(toCoinQuery = "btc")).toCoinValid)
        assertFalse(PortfolioTxForm.check(swap.copy(toAmount = null)).valid)
        // Wert leer = unbekannt (gültig), negativ bzw. unlesbar: nicht speicherbar
        assertTrue(PortfolioTxForm.check(swap.copy(swapValueBlank = false, swapValue = dec("0"))).swapValueValid)
        assertFalse(PortfolioTxForm.check(swap.copy(swapValueBlank = false, swapValue = dec("-1"))).swapValueValid)
        assertFalse(PortfolioTxForm.check(swap.copy(swapValueBlank = false, swapValue = null)).valid)
        // Gebühr im erhaltenen Coin: ohne Preis vollständig
        val inReceived = swap.copy(feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("0.5"))
        val r = PortfolioTxForm.check(inReceived)
        assertTrue(r.feeInReceived)
        assertEquals(ThirdFee.COMPLETE, r.thirdFee)
        assertTrue(r.valid)
    }

    @Test
    fun feeConsumingTheReceivedAmountBlocksSaving() {
        val buy = buy().copy(feeBlank = false, fee = dec("1"), feeUnit = PortfolioFeeUnit.COIN)
        assertTrue(PortfolioTxForm.check(buy).feeConsumes)
        assertFalse(PortfolioTxForm.check(buy).valid)
        assertFalse(PortfolioTxForm.check(buy.copy(fee = dec("0.5"))).feeConsumes)
        val swap = Entry(
            type = PortfolioTxType.SWAP, coinQuery = "BTC", amount = dec("1"), toCoinQuery = "ETH", toAmount = dec("2"),
            feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("2"),
        )
        assertTrue(PortfolioTxForm.check(swap).feeConsumes)
        assertFalse(PortfolioTxForm.check(swap).valid)
    }
}
