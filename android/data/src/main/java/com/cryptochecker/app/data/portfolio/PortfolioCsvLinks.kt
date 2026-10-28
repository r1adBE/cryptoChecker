package com.cryptochecker.app.data.portfolio

import androidx.room.withTransaction
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer

// CSV-Import: neue Zeilen anlegen und gespeicherte Seiten verknüpfter Umbuchungen ändern in EINER Datenbank-Transaktion —
// alles oder nichts. Eigene Datei, weil `PortfolioRepository.kt` an der Längengrenze ist.

/** Ergebnis von [insertImportedLinked]: neu angelegte Transaktionen und verknüpfte gespeicherte Seiten. */
data class CsvImportWritten(val inserted: Int, val linked: Int)

/**
 * Importierte Zeilen anlegen ([PortfolioRepository.insertImported]) und gespeicherte Seiten verknüpfter Umbuchungen ändern
 * ([CsvStoredTransfer]) — in EINER Datenbank-Transaktion: scheitert etwas (gesperrt, Fehler, «Abbrechen» vor einer Zeile),
 * bleibt nichts geschrieben, nie eine halbe Verknüpfung. Die gespeicherten Seiten werden darin neu gelesen
 * ([linkedStoredEntities]); inzwischen gelöschte oder schon verknüpfte fallen weg, und ihre aufgehende neue Zeile
 * ([fallbacks]: Herkunftsschlüssel → Id der gespeicherten Seite, `CsvTransferMatch.fallbacks`) wird dann als eigene Zeile
 * angelegt statt verloren zu gehen. Ohne fallende Seite bleibt sie weg ([transactions] enthält sie,
 * `CsvTransferMatch.withFallbacks`). [onRow] zählt weggelassene Ersatzzeilen mit, damit der Fortschritt das Ende erreicht.
 * @throws PortfolioStorageLockedException gespeicherter Stand gerade nicht lesbar ([PortfolioStorageLock])
 * @throws com.cryptochecker.app.domain.portfolio.csvimport.CsvImportCancelledException abgebrochen ([cancelled])
 */
suspend fun PortfolioRepository.insertImportedLinked(
    transactions: List<PortfolioTxEntity>,
    batch: String,
    updates: List<CsvStoredTransfer>,
    fallbacks: Map<String, Long>,
    onRow: (Int) -> Unit = {},
    cancelled: () -> Boolean = { false },
): CsvImportWritten = database.withTransaction {
    // Nur Seiten, die sich auch speichern lassen (sonst gälte die Zeile als verknüpft und fehlte)
    val changed = linkedStoredEntities(getTransactions(), updates, batch)
        .filter { runCatching { cleanForSave(it).storable() }.getOrDefault(false) }
    val linked = changed.mapTo(HashSet()) { it.id }
    val rows = transactions.filter { tx -> fallbacks[tx.importKey]?.let { it !in linked } ?: true }
    val left = transactions.size - rows.size
    val inserted = insertImported(rows, batch, { onRow(it + left) }, cancelled)
    // `save` tritt dieser Transaktion bei (Room verschachtelt): eine Ausnahme rollt alles zurück
    changed.forEach { save(it) }
    CsvImportWritten(inserted, changed.size)
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
