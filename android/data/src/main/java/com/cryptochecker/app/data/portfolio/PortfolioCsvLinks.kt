package com.cryptochecker.app.data.portfolio

import androidx.room.withTransaction
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer

// CSV-Import: gespeicherte Seiten verknüpfter Umbuchungen in EINER Datenbank-Transaktion ändern — alle oder keine.
// Eigene Datei, weil `PortfolioRepository.kt` an der Längengrenze ist.

/**
 * Gespeicherte Seiten verknüpfter Umbuchungen ändern ([CsvStoredTransfer]) — in einer Datenbank-Transaktion: scheitert
 * eine (gesperrt, Fehler), bleibt keine halb verknüpft (sonst stünde die erste geändert da, gemeldet würde «nichts
 * gespeichert»). Innerhalb der Transaktion neu gelesen, damit nichts Veraltetes überschrieben wird ([linkedStoredEntities]).
 * @return Anzahl geänderter Transaktionen
 * @throws PortfolioStorageLockedException gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock])
 */
suspend fun PortfolioRepository.linkStoredTransfers(updates: List<CsvStoredTransfer>, batch: String): Int {
    if (updates.isEmpty()) return 0
    return database.withTransaction {
        val changed = linkedStoredEntities(getTransactions(), updates, batch)
        // `save` tritt dieser Transaktion bei (Room verschachtelt): eine Ausnahme rollt alle zurück
        changed.forEach { save(it) }
        changed.size
    }
}

/**
 * Geänderte Einträge der gespeicherten Seiten: inzwischen gelöscht oder schon verknüpft → ausgelassen. Die Transaktion
 * wechselt in den Importlauf [batch] (ihr Zustand davor samt früherem Lauf steht in `importRestoreState`): «Import
 * rückgängig» dieses Laufs setzt sie zurück.
 */
fun linkedStoredEntities(
    current: List<PortfolioTxEntity>,
    updates: List<CsvStoredTransfer>,
    batch: String,
): List<PortfolioTxEntity> {
    val byId = current.associateBy { it.id }
    return updates.mapNotNull { u ->
        val tx = byId[u.id]?.takeIf { it.linkedImportKey == null } ?: return@mapNotNull null
        tx.copy(
            type = PortfolioTxType.TRANSFER,
            amount = u.amount,
            priceUsdt = null,
            time = u.time,
            fee = u.fee,
            feeUnit = u.feeUnit?.name,
            transferFrom = u.transferFrom,
            transferTo = u.transferTo,
            feeCoin = u.feeCoin,
            feeCoinAmount = u.feeCoinAmount,
            feeCoinPriceUsdt = tx.feeCoinPriceUsdt.takeIf { u.feeCoin == tx.feeCoin },
            importKey = u.importKey,
            acquiredTime = null,
            importBatch = batch,
            linkedImportKey = u.linkedImportKey,
            importRestoreState = u.restoreState,
        )
    }
}
