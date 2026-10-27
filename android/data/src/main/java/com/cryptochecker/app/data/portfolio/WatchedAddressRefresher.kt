package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guthaben der beobachteten Adressen in der Portfolio-Aktualisierung (Portfolio-Tab und
 * Hintergrund/Widget, [com.cryptochecker.app.widget.PortfolioSnapshotUpdater.refresh]):
 * höchstens alle [AddressBalance.CACHE_MILLIS] je Adresse, manuell frühestens nach
 * [AddressBalance.MIN_FORCED_MILLIS]. Ohne Adressen keine Anfrage. Fehler bleiben still
 * (stehen je Adresse als `lastError`, das letzte Guthaben bleibt). Eigener Bitcoin-Server aus den Einstellungen; ist er
 * nicht lesbar, wird gar nicht gefragt (nie still bei öffentlichen Knoten).
 */
@Singleton
class WatchedAddressRefresher @Inject constructor(
    private val repository: PortfolioRepository,
    private val source: WatchedAddressSource,
    private val settings: SettingsRepository,
) {
    /** Eine Aktualisierung zur Zeit ([singleFlightRefresh]): Lesen, Laden und Speichern zusammen. */
    private val gate = Mutex()

    /**
     * @param background Hintergrund/Widget: alle Adressen zusammen höchstens
     *   [WatchedAddressSource.BACKGROUND_TOTAL_MILLIS] (je Anfrage kürzer), damit Kurse und Alarme nie warten;
     *   läuft schon eine Aktualisierung, sofort zurück. Vordergrund wartet und liest danach frisch.
     * @return true, wenn mindestens ein Abruf gespeichert wurde.
     */
    suspend fun refresh(force: Boolean, background: Boolean = false): Boolean {
        val budget = if (background) WatchedAddressSource.BACKGROUND_TOTAL_MILLIS else null
        return singleFlightRefresh(
            gate, background,
            read = { repository.getWatchedAddresses() },
            load = { addresses ->
                val server = settings.addressServerFlow.first()
                source.refresh(addresses, force, budgetMillis = budget, btcServer = server)
            },
            save = { updates -> updates.forEach { save(it) } },
        )
    }

    private suspend fun save(u: WatchedAddressSource.Update) = repository.updateWatchedAddressBalance(u)
}

/**
 * Single-Flight der Adress-Aktualisierung: Lesen, Laden und Speichern unter [gate]. Vordergrund wartet auf eine
 * laufende Runde und liest danach frisch (eben geladene Adressen sind nicht mehr fällig, nichts doppelt);
 * Hintergrund kehrt sofort zurück, wenn schon eine läuft. Fehler (auch beim Lesen) bleiben still und geben das Gate
 * frei; Abbruch wird weitergereicht.
 * @return true, wenn mindestens ein Ergebnis gespeichert wurde; false bei Fehler.
 */
suspend fun <U> singleFlightRefresh(
    gate: Mutex,
    background: Boolean,
    read: suspend () -> List<WatchedAddress>,
    load: suspend (List<WatchedAddress>) -> List<U>,
    save: suspend (List<U>) -> Unit,
): Boolean {
    if (background) {
        if (!gate.tryLock()) return false
    } else {
        gate.lock()
    }
    return try {
        val addresses = read()
        if (addresses.isEmpty()) return false
        val updates = load(addresses)
        save(updates)
        updates.isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Adressen: Aktualisierung fehlgeschlagen")
        false
    } finally {
        gate.unlock()
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
