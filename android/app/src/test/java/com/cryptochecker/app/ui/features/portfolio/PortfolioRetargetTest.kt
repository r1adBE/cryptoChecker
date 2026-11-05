package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * «Ziel ändern» am Umbuchungs-Hinweis ([retargeted], [coinDetail]): nur die genannte Umbuchung bekommt die Adress-Wallet
 * als Ziel, alles andere bleibt; danach hat die Coin-Ansicht keinen Hinweis mehr. Gegenstück: `PortfolioRetargetTests.swift`.
 */
class PortfolioRetargetTest {

    private val cold = WatchedAddress(
        id = 1, chain = AddressChain.BTC, address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", label = "Cold",
        lastBalance = "1.0", lastUpdated = 1_727_740_800_000L,
    )

    private val all = listOf(
        tx(1, PortfolioTxType.BUY, "2", exchange = "Binance"),
        tx(2, PortfolioTxType.TRANSFER, "1.0", from = "Binance", to = "Ledger"),
        tx(3, PortfolioTxType.TRANSFER, "0.3", from = "Binance", to = "Trezor"),
    )

    @Test
    fun onlyTheNamedTransferGetsTheAddressWallet() {
        val changed = retargeted(all, 2, " Cold ")
        assertNotNull(changed)
        assertEquals(all[1].copy(transferTo = "Cold"), changed)
        // Keine Umbuchung, unbekannte id oder leeres Ziel: nichts
        assertNull(retargeted(all, 1, "Cold"))
        assertNull(retargeted(all, 99, "Cold"))
        assertNull(retargeted(all, 2, "  "))
        // Gekürzt wie im Feld
        assertEquals(PortfolioTxForm.MAX_ROUTE, retargeted(all, 2, "x".repeat(60))?.transferTo?.length)
    }

    @Test
    fun hintGoneAfterTheChange() {
        val before = coinDetail("BTC", all, all, listOf(cold), null, CostMethod.FIFO, null)
        val r = before.retarget
        assertNotNull(r)
        assertEquals(2L, r!!.trade.id)
        assertEquals("Ledger", r.from)
        assertEquals("Cold", r.to)
        val saved = retargeted(all, r.trade.id, r.to)!!
        val after = all.map { if (it.id == saved.id) saved else it }
        assertNull(coinDetail("BTC", after, after, listOf(cold), null, CostMethod.FIFO, null).retarget)
        assertEquals("Trezor", after[2].transferTo)
    }

    private fun tx(
        id: Long, type: PortfolioTxType, amount: String, exchange: String? = null, from: String? = null, to: String? = null,
    ) = PortfolioTxEntity(
        id = id, coin = "BTC", type = type, amount = BigDecimal(amount), priceUsdt = if (type == PortfolioTxType.BUY) BigDecimal("40000") else null,
        time = 1_725_000_000_000L + id * 86_400_000L, exchange = exchange, transferFrom = from, transferTo = to,
    )
}
