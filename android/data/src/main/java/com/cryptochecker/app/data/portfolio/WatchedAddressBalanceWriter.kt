package com.cryptochecker.app.data.portfolio

import androidx.room.withTransaction
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules

/**
 * Ergebnis eines Adress-Abrufs speichern (eine Transaktion, ohne Schreibsperre: Abrufe laufen auch im Hintergrund).
 * Mit Guthaben: Guthaben, Zeit, Anbieter, Beobachtungen, Token, xpub zusammen ([WatchedAddressRules.applyFetched]),
 * Fehler gelöscht; sonst nur Versuch und Fehler, das letzte Guthaben bleibt. Hat der Nutzer während des Abrufs etwas
 * dazugenommen ([AddressBalance.discardFetched]): verwerfen, die Adresse bleibt fällig. Wie iOS `WatchedAddressStore.apply`.
 */
object WatchedAddressBalanceWriter {

    suspend fun save(database: PortfolioDatabase, dao: WatchedAddressDao, u: WatchedAddressSource.Update) {
        database.withTransaction {
            val current = dao.getById(u.id)?.toDomain() ?: return@withTransaction
            if (AddressBalance.discardFetched(current.countTokens, current.networks, u.snapshotCountTokens, u.snapshotNetworks)) {
                return@withTransaction
            }
            val f = u.fetched
            if (f == null) {
                dao.saveError(u.id, u.time, u.error ?: "unknown")
                return@withTransaction
            }
            val updated = WatchedAddressRules.applyFetched(
                current, u.time, f.balance, f.provider, f.tokens, f.xpub, f.networks, f.unknownTokens, f.keptTokens,
            )
            dao.saveFetched(u.id, f.balance, u.time, f.provider, WatchedAddressExtras.encode(updated))
        }
    }
}
