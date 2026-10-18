// Umwandlung einer gelesenen CSV-Zeile des Imports in eine gespeicherte Transaktion bzw. Umbuchung.
// Eigene Datei, weil `PortfolioCsvImportViewModel` (Ablauf und Zustand) sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredMove
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch

/** Importierte Aus-/Einzahlung bzw. Umbuchung als mögliches Gegenstück ([CsvTransferMatch]); sonst null. */
internal fun PortfolioTxEntity.toStoredMove(): CsvStoredMove? {
    val key = importKey ?: return null
    if (type != PortfolioTxType.WITHDRAWAL && type != PortfolioTxType.DEPOSIT && type != PortfolioTxType.TRANSFER) return null
    return CsvStoredMove(
        id = id,
        coin = coin,
        type = type,
        amount = amount,
        fee = fee,
        feeUnit = fee?.let { PortfolioFeeUnit.fromName(feeUnit) },
        feeCoin = feeCoin,
        feeCoinAmount = feeCoinAmount,
        time = time,
        importKey = key,
        transferFrom = transferFrom,
        transferTo = transferTo,
        priceUsdt = priceUsdt,
        feeCoinPrice = feeCoinPriceUsdt,
        acquiredTime = acquiredTime,
        exchange = exchange,
        importBatch = importBatch,
        linkedImportKey = linkedImportKey,
    )
}

internal fun CsvImportTx.toEntity() = PortfolioTxEntity(
    coin = coin,
    type = type,
    amount = amount,
    priceUsdt = priceUsdt,
    time = time,
    note = note,
    fee = fee,
    feeUnit = feeUnit?.name,
    feeCoin = feeCoin,
    feeCoinAmount = feeCoinAmount,
    transferFrom = transferFrom,
    transferTo = transferTo,
    importKey = importKey,
    // Verknüpfte Umbuchung zweier neuer Zeilen: Herkunft der Einzahlung
    linkedImportKey = linkedImportKey,
    // Tausch aus der Datei (erhaltener Coin, Menge, Wert)
    toCoin = toCoin,
    toAmount = toAmount,
    swapValueUsdt = swapValueUsdt,
    // Gebühr-Zeile und Zins (eigene Merker)
    feeRow = feeRow,
    interest = interest,
)
