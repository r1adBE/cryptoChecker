package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.PortfolioJson
import com.cryptochecker.app.data.portfolio.cleanForSave
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportSkipReason
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.ui.features.portfolio.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Zeilen, die das Speichern ablehnen würde, stehen schon in der Vorschau unter «übersprungen»
 * (`csv_import.json` › «unsavable», [CsvImportMapping.unsavableReason]). Dazu: die Regel deckt sich mit der
 * Bereinigung und Prüfung beim Speichern ([cleanForSave] samt `require` in `PortfolioRepository.insertImported`).
 * Gegenstück: `Tests/CsvImportUnsavableTests.swift`.
 */
class CsvUnsavableParityTest {

    private fun tx(c: Map<String, Any?>) = CsvImportTx(
        line = 2,
        coin = c["coin"] as String,
        type = PortfolioTxType.valueOf(c["type"] as String),
        amount = BigDecimal(c["amount"] as String),
        priceUsdt = null,
        time = 1_700_000_000_000L,
        fee = null,
        feeUnit = null,
        feeCoin = null,
        feeCoinAmount = null,
        note = null,
        importKey = "TEST|1",
        toCoin = c["toCoin"] as String?,
        toAmount = (c["toAmount"] as String?)?.let { BigDecimal(it) },
    )

    /** Wie `insertImported`: bereinigen, dann Coin nicht leer und Menge > 0; true = würde gespeichert. */
    private fun saves(tx: CsvImportTx): Boolean = runCatching {
        cleanForSave(tx.toEntity()).also { require(it.coin.isNotEmpty() && it.amount.signum() > 0) }
    }.isSuccess

    private val cases = ParityJson.load("csv_import.json")["unsavable"].list().map { it.obj() }

    @Test
    fun grundWieGemeinsameTestfaelle() {
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val expected = (c["expected"] as String?)?.let { CsvImportSkipReason.valueOf(it) }
            assertEquals(c["name"] as String, expected, CsvImportMapping.unsavableReason(tx(c)))
        }
    }

    @Test
    fun regelDecktSichMitDemSpeichern() {
        for (c in cases) {
            val t = tx(c)
            assertEquals(c["name"] as String, CsvImportMapping.unsavableReason(t) == null, saves(t))
        }
    }

    @Test
    fun laengeDesErhaltenenCoinsWieBeimSpeichern() {
        assertEquals(PortfolioJson.MAX_FEE_COIN, CsvImportMapping.MAX_TO_COIN)
    }
}
