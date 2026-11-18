package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.csvimport.CsvStoredTransfer
import kotlinx.coroutines.flow.Flow

/** Ergebnis von [PortfolioStore.addWatchedAddress]. */
sealed interface AddWatchedResult {
    data class Added(val id: Long) : AddWatchedResult

    /** Ungültig (Prüfsumme, Netz, privater Schlüssel …). */
    data class Invalid(val problem: AddressValidation.Problem) : AddWatchedResult
    data object Duplicate : AddWatchedResult

    /** Schon [WatchedAddressRules.MAX_ADDRESSES] Adressen. */
    data object LimitReached : AddWatchedResult
}

/** Ergebnis von [PortfolioStore.insertImportedLinked]: neu angelegte Transaktionen und verknüpfte gespeicherte Seiten. */
data class CsvImportWritten(val inserted: Int, val linked: Int)

/** Entfernte Adresse samt allem, was dazugehört — für «Rückgängig» ([PortfolioStore.restoreWatchedAddress]). */
interface RemovedWatchedAddress {
    /** Die Adresse als Modell; null bei unbekanntem Netz. */
    fun removedAddress(): WatchedAddress?
}

/**
 * Portfolio für die Oberfläche: Transaktionen und beobachtete Adressen lesen und ändern (Umsetzung: `PortfolioRepository`).
 * Schreibwege des Nutzers werfen gesperrt [PortfolioStorageLockedException].
 */
interface PortfolioStore : PortfolioReader {
    fun observeTransactions(): Flow<List<PortfolioTxEntity>>

    val watchedAddresses: Flow<List<WatchedAddress>>

    /** Neue oder geänderte Transaktion; liefert die Id. */
    suspend fun save(tx: PortfolioTxEntity): Long

    /** Schreibwege gerade gesperrt? Öffnet dazu die Datenbank. */
    suspend fun writesBlocked(): Boolean

    suspend fun delete(id: Long)

    /** Löscht alle Transaktionen von [coin] samt Gebühren in [coin] bei anderen; die Originale für «Rückgängig». */
    suspend fun deleteCoin(coin: String): List<PortfolioTxEntity>

    /** Legt gelöschte Transaktionen mit ihren alten ids wieder an («Rückgängig»). */
    suspend fun restore(transactions: List<PortfolioTxEntity>)

    suspend fun clearAll(): List<PortfolioTxEntity>

    suspend fun importKeys(): Set<String>

    /** Import [batch] rückgängig; die Originale der gelöschten bzw. geänderten Transaktionen. */
    suspend fun undoImport(batch: String): List<PortfolioTxEntity>

    /** Importierte Zeilen anlegen und verknüpfte gespeicherte Seiten ändern — in einer Datenbank-Transaktion. */
    suspend fun insertImportedLinked(
        transactions: List<PortfolioTxEntity>,
        batch: String,
        updates: List<CsvStoredTransfer>,
        fallbacks: Map<String, Long>,
        onRow: (Int) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): CsvImportWritten

    suspend fun addWatchedAddress(
        chain: AddressChain,
        address: String,
        label: String?,
        countInPortfolio: Boolean = true,
        /** Überschneidung beim Hinzufügen gefragt und entschieden ([WatchedAddress.overlapChecked]). */
        overlapChecked: Boolean = false,
    ): AddWatchedResult

    /** Entfernt die Adresse; die ganze Zeile für «Rückgängig», null = schon weg. */
    suspend fun removeWatchedAddress(id: Long): RemovedWatchedAddress?

    /** «Rückgängig» nach [removeWatchedAddress]; false, wenn das nicht mehr geht. */
    suspend fun restoreWatchedAddress(row: RemovedWatchedAddress): Boolean

    suspend fun setWatchedAddressCountTokens(id: Long, on: Boolean)

    suspend fun setWatchedAddressNetworks(id: Long, networks: List<String>)

    suspend fun setWatchedAddressCountInPortfolio(id: Long, on: Boolean)

    suspend fun setWatchedAddressOverlapShowOnly(id: Long)

    suspend fun setWatchedAddressCostLots(id: Long, lots: List<AddressCostLot>)
}
