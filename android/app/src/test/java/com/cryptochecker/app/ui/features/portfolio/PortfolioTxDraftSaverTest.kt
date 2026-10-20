package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Offenes Erfassen-Blatt übersteht Drehen/Sprachwechsel: Entwurf wird verlustfrei gespeichert. */
class PortfolioTxDraftSaverTest {

    @Test
    fun `leerer Entwurf kommt gleich zurueck`() {
        val draft = TxDraft(time = 1_700_000_000_000L)
        assertEquals(draft, txDraftFromSaved(draft.toSaved()))
    }

    @Test
    fun `alle Felder samt exakter Betraege kommen gleich zurueck`() {
        val draft = TxDraft(
            id = 42, coin = "BTC", type = PortfolioTxType.SWAP, amount = BigDecimal("0.00000001"),
            priceUsdt = BigDecimal("61234.50"), time = 1_700_000_000_000L, note = "Notiz",
            fee = BigDecimal("1.5"), feeUnit = PortfolioFeeUnit.entries.last(), exchange = "Binance",
            transferFrom = "A", transferTo = "B", feeCoin = "BNB", feeCoinAmount = BigDecimal("0.01"),
            feeCoinPriceUsdt = BigDecimal("600"), importKey = "k", acquiredTime = 1_600_000_000_000L,
            toCoin = "ETH", toAmount = BigDecimal("2.000"), swapValueUsdt = BigDecimal("1E+3"),
            importBatch = "b", giftToSpouse = true, linkedImportKey = "l", feeRow = true, interest = true,
        )
        assertEquals(draft, txDraftFromSaved(draft.toSaved()))
    }

    @Test
    fun `fremde Fassung oder kaputte Liste ergibt kein Blatt`() {
        val saved = TxDraft().toSaved()
        assertNull(txDraftFromSaved(saved.drop(1)))
        assertNull(txDraftFromSaved(ArrayList(saved).also { it[0] = 99L }))
        assertNull(txDraftFromSaved(ArrayList(saved).also { it[3] = "UNBEKANNT" }))
    }
}
