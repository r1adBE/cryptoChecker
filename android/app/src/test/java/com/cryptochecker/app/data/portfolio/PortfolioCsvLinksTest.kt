package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxWealthRow
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer
import com.cryptochecker.app.domain.portfolio.wealthCoinNoteBelowValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * CSV-Import: geänderte gespeicherte Seiten verknüpfter Umbuchungen ([linkedStoredEntities], geschrieben in EINER
 * Datenbank-Transaktion von [linkStoredTransfers]) und Hinweis «unvollständig» unter einem Teilwert der CH-Steuerkarte
 * (iOS `PortfolioWriteFailureTests.swift`).
 */
class PortfolioCsvLinksTest {

    private fun stored(id: Long, linked: String? = null) = PortfolioTxEntity(
        id = id, coin = "BTC", type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1"), time = 100L,
        importKey = "k$id", importBatch = "old", linkedImportKey = linked,
    )

    private fun update(id: Long) = CsvStoredTransfer(
        id = id, amount = BigDecimal("0.9"), fee = BigDecimal("0.1"), feeUnit = PortfolioFeeUnit.COIN, feeCoin = null,
        feeCoinAmount = null, time = 200L, transferFrom = "Binance", transferTo = "Kraken", importKey = "k$id",
        linkedImportKey = "d$id",
        restoreState = CsvRestoreState(
            type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1"), priceUsdt = null, time = 100L, fee = null,
            feeUnit = null, feeCoin = null, feeCoinAmount = null, feeCoinPrice = null, transferFrom = null,
            transferTo = null, acquiredTime = null, exchange = null, importKey = "k$id", importBatch = "old", absorbed = null,
        ),
    )

    @Test
    fun linksOnlyStoredUnlinkedSides() {
        val current = listOf(stored(1), stored(2, linked = "x"))
        // id 3 inzwischen gelöscht, id 2 schon verknüpft: nur id 1 wird geändert (alle zusammen in einer Transaktion)
        val changed = linkedStoredEntities(current, listOf(update(1), update(2), update(3)), batch = "run")
        assertEquals(listOf(1L), changed.map { it.id })
        val tx = changed.single()
        assertEquals(PortfolioTxType.TRANSFER, tx.type)
        assertEquals(BigDecimal("0.9"), tx.amount)
        assertEquals("run", tx.importBatch)
        assertEquals("d1", tx.linkedImportKey)
        assertEquals("old", tx.importRestoreState?.importBatch)
        assertEquals(PortfolioFeeUnit.COIN.name, tx.feeUnit)
        assertNull(tx.priceUsdt)
    }

    /** Teilwert: Wert und darunter «unvollständig – Grund»; ohne Wert steht der Hinweis an Stelle des Werts. */
    @Test
    fun wealthNoteBelowPartialValue() {
        val texts = TaxSummaryTexts(
            title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "CHF", note = "Note", orientation = "O",
            labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
            incomplete = "incomplete", noFx = "nofx", wealthMissing = "nc", wealthAddressUnobserved = "unobserved",
        )
        val partial = TaxWealthRow(
            "BTC", BigDecimal("0.1"), BigDecimal("60000"), BigDecimal("6000"), BigDecimal("4800"),
            addressUnobserved = true, incomplete = true,
        )
        assertEquals("incomplete – unobserved", texts.wealthCoinNoteBelowValue(partial, hasValue = true))
        assertNull(texts.wealthCoinNoteBelowValue(partial, hasValue = false))
        val full = TaxWealthRow("BTC", BigDecimal("0.1"), BigDecimal("60000"), BigDecimal("6000"), BigDecimal("4800"))
        assertNull(texts.wealthCoinNoteBelowValue(full, hasValue = true))
    }
}
