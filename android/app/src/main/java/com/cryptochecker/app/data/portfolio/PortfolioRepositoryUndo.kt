// «Rückgängig» für Löschungen im Portfolio (Teil von `PortfolioRepository`): eine entfernte Adresse samt
// Eröffnungsposten, Guthaben-Verlauf und Token-Reihen wieder anlegen. Eigene Datei, weil das Repository an der
// Zeilengrenze steht; [PortfolioRepository.removeWatchedAddress] gibt die ganze Zeile dafür zurück.
package com.cryptochecker.app.data.portfolio

import androidx.room.withTransaction
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo

/**
 * «Rückgängig» nach «Adresse entfernen»: dieselbe Zeile [row] mit der alten Id — alles, was zur Adresse gehört, steht
 * in dieser einen Zeile (`observations`, `costLots`). Gesperrt ([PortfolioStorageLock]) abgelehnt wie alle Schreibwege
 * des Nutzers. false, wenn das nicht mehr geht ([WatchedAddressUndo.canRestore]: inzwischen dieselbe Adresse neu
 * hinzugefügt, Id belegt oder Liste voll).
 * @throws PortfolioStorageLockedException gespeicherter Stand gerade nicht lesbar
 */
suspend fun PortfolioRepository.restoreWatchedAddress(row: WatchedAddressEntity): Boolean = database.withTransaction {
    ensurePortfolioWritable()
    val removed = row.toDomain() ?: return@withTransaction false
    WatchedAddressUndo.canRestore(getWatchedAddresses(), removed) && database.getWatchedAddressDao().insert(row) == row.id
}
