package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guthaben der beobachteten Adressen in der Portfolio-Aktualisierung (Portfolio-Tab und
 * Hintergrund/Widget, [com.cryptochecker.app.widget.PortfolioSnapshotUpdater.refresh]):
 * höchstens alle [AddressBalance.CACHE_MILLIS] je Adresse, manuell frühestens nach
 * [AddressBalance.MIN_FORCED_MILLIS]. Ohne Adressen keine Anfrage. Fehler bleiben still
 * (stehen je Adresse als `lastError`, das letzte Guthaben bleibt).
 */
@Singleton
class WatchedAddressRefresher @Inject constructor(
    private val repository: PortfolioRepository,
    private val source: WatchedAddressSource,
) {
    /**
     * @param background Hintergrund/Widget: alle Adressen zusammen höchstens
     *   [WatchedAddressSource.BACKGROUND_TOTAL_MILLIS] (je Anfrage kürzer), damit Kurse und Alarme nie warten
     * @return true, wenn mindestens ein Abruf gespeichert wurde.
     */
    suspend fun refresh(force: Boolean, background: Boolean = false): Boolean {
        return try {
            val addresses = repository.getWatchedAddresses()
            if (addresses.isEmpty()) return false
            val budget = if (background) WatchedAddressSource.BACKGROUND_TOTAL_MILLIS else null
            val updates = source.refresh(addresses, force, budgetMillis = budget)
            for (u in updates) {
                repository.updateWatchedAddressBalance(
                    u.id, u.fetched?.balance, u.time, u.error, u.fetched?.provider, u.fetched?.tokens, u.fetched?.xpub,
                    u.fetched?.networks, u.fetched?.unknownTokens, u.fetched?.keptTokens,
                )
            }
            updates.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Adressen: Aktualisierung fehlgeschlagen")
            false
        }
    }
}

/**
 * Echte Transaktionen + Adress-Einzahlungen ohne Kaufpreis und Eröffnungsposten ([AddressBalance.withAddresses]) als Einträge
 * für Rechner, Verlauf, Widget und Alarme. Nie speichern: negative Ids.
 */
fun List<PortfolioTxEntity>.withAddresses(
    addresses: List<WatchedAddress>,
    now: Long = System.currentTimeMillis(),
): List<PortfolioTxEntity> {
    // Ohne Adressen oder schon zusammengeführt (negative Ids): unverändert
    if (addresses.isEmpty() || any { AddressBalance.isAddressTrade(it.id) }) return this
    val synthetic = AddressBalance.trades(addresses, map { it.toTrade() }, PortfolioHistory.epochDayOfUtcMillis(now))
    if (synthetic.isEmpty()) return this
    // Eröffnungsposten ([com.cryptochecker.app.domain.portfolio.AddressCostLots]) mit Preis und Wallet der Adresse
    return this + synthetic.map { t ->
        PortfolioTxEntity(id = t.id, coin = t.coin, type = t.type, amount = t.amount, priceUsdt = t.priceUsdt, time = t.time, exchange = t.exchange)
    }
}
