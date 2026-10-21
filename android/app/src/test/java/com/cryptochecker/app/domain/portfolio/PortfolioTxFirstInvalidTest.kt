package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioTxFirstInvalid.Field
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Erstes ungültiges Feld beim Speichern ([PortfolioTxFirstInvalid]). Wie `PortfolioTxFirstInvalidTests` (iOS). */
class PortfolioTxFirstInvalidTest {

    private fun dec(s: String) = BigDecimal(s)

    private fun buy() = Entry(type = PortfolioTxType.BUY, coinQuery = "btc", amount = dec("1"))

    private val swap = Entry(
        type = PortfolioTxType.SWAP, coinQuery = "BTC", amount = dec("1"), toCoinQuery = "ETH", toAmount = dec("2"),
    )

    private fun first(e: Entry): Field? =
        PortfolioTxFirstInvalid.of(PortfolioTxForm.check(e), amountValid = e.amount != null, isSwap = e.type == PortfolioTxType.SWAP)

    @Test
    fun gueltigeEingabeHatKeinFehlerfeld() {
        assertNull(first(buy()))
        assertNull(first(swap))
    }

    @Test
    fun reihenfolgeWieImBlatt() {
        // Coin und Menge falsch: zuerst der Coin (steht oben)
        assertEquals(Field.COIN, first(buy().copy(coinQuery = "", amount = null)))
        assertEquals(Field.AMOUNT, first(buy().copy(amount = null, priceBlank = false)))
        // Preis unlesbar, Gebühr unlesbar: zuerst der Preis
        assertEquals(Field.PRICE, first(buy().copy(priceBlank = false, feeBlank = false)))
        assertEquals(Field.FEE, first(buy().copy(feeBlank = false)))
    }

    @Test
    fun tauschFelder() {
        assertEquals(Field.TO_COIN, first(swap.copy(toCoinQuery = "btc", toAmount = null)))
        assertEquals(Field.TO_AMOUNT, first(swap.copy(toAmount = null)))
        assertEquals(Field.SWAP_VALUE, first(swap.copy(swapValueBlank = false, swapValue = dec("-1"))))
    }

    @Test
    fun gebuehrImDrittenCoinUndVerbrauchteMenge() {
        // Dritter Coin gleich dem Coin der Transaktion: ungültig
        assertEquals(Field.FEE_COIN, first(buy().copy(feeCoin = "BTC", feeCoinAmountBlank = false, feeCoinAmount = dec("1"))))
        // Gebühr im Coin ≥ Menge: am Gebührenfeld
        assertEquals(Field.FEE, first(buy().copy(feeBlank = false, fee = dec("1"), feeUnit = PortfolioFeeUnit.COIN)))
        // Tausch: Gebühr im erhaltenen Coin ≥ erhaltene Menge — an der Gebühr im erhaltenen Coin
        assertEquals(Field.FEE_COIN, first(swap.copy(feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("2"))))
    }

    @Test
    fun nurGebuehrenfelderLiegenImAbschnitt() {
        assertTrue(Field.FEE.inLedger)
        assertTrue(Field.FEE_COIN.inLedger)
        assertFalse(Field.PRICE.inLedger)
        assertFalse(Field.COIN.inLedger)
    }

    @Test
    fun keinFehlerfeldGenauDannWennSpeicherbar() {
        val entries = listOf(
            buy(), buy().copy(coinQuery = ""), buy().copy(amount = null), buy().copy(priceBlank = false),
            buy().copy(feeBlank = false), swap, swap.copy(toAmount = null),
            swap.copy(feeCoin = "ETH", feeCoinAmountBlank = false, feeCoinAmount = dec("2")),
        )
        for (e in entries) {
            assertEquals(e.toString(), PortfolioTxForm.check(e).valid, first(e) == null)
        }
    }
}
