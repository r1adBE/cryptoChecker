// Beobachtete Adressen (Teil von `PortfolioViewModel`): hinzufügen, entfernen, Netze, «im Portfolio zählen»,
// Tokens mitzählen und der Hinweis bei Überschneidung mit erfassten Transaktionen samt «Rückgängig».
// Eigene Datei, weil die Adressen ein eigener Bereich neben den Transaktionen sind.
package com.cryptochecker.app.ui.features.portfolio

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.data.portfolio.WatchedAddressEntity
import com.cryptochecker.app.data.portfolio.restoreWatchedAddress
import com.cryptochecker.app.data.portfolio.toDomain
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.WatchedAddressUndo
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

fun PortfolioViewModel.consumeAddressOverlapNotice() {
    _addressOverlapNotice.value = null
}

/** «Rückgängig» zum Hinweis: Adressen zählen wieder (gilt als entschieden, kein zweites automatisches Umschalten). */
fun PortfolioViewModel.undoAddressOverlap(notice: AddressOverlapNotice) {
    viewModelScope.launch { notice.ids.forEach { id -> safe { repository.setWatchedAddressCountInPortfolio(id, true) } } }
}

fun PortfolioViewModel.consumeAddressOverlapRestored() {
    _addressOverlapRestored.value = null
}

/** «Rückgängig» zum Hinweis [addressOverlapRestored]: wieder «Nur anzeigen» (gilt als entschieden). */
fun PortfolioViewModel.undoAddressOverlapRestored(notice: AddressOverlapNotice) {
    viewModelScope.launch { notice.ids.forEach { id -> safe { repository.setWatchedAddressCountInPortfolio(id, false) } } }
}

/**
 * Adresse beobachten (Netz aus [AddressValidation][com.cryptochecker.app.domain.portfolio.AddressValidation]
 * erkannt). [onResult] im Hauptthread; hinzugefügt mit ausgeschaltetem Portfolio schaltet es ein wie die
 * erste Transaktion ([PortfolioOff.enableOnSave]) — dann [onPortfolioEnabled]. Das Guthaben wird gleich geladen.
 */
fun PortfolioViewModel.addWatchedAddress(
    chain: AddressChain,
    address: String,
    label: String?,
    countInPortfolio: Boolean = true,
    onPortfolioEnabled: () -> Unit = {},
    /** Überschneidung beim Hinzufügen gefragt und entschieden (kein späteres automatisches «Nur anzeigen»). */
    overlapChecked: Boolean = false,
    onResult: (PortfolioRepository.AddWatchedResult?) -> Unit,
) {
    viewModelScope.launch {
        val result = safe { repository.addWatchedAddress(chain, address, label, countInPortfolio, overlapChecked) }
        onResult(result)
        if (result !is PortfolioRepository.AddWatchedResult.Added) return@launch
        if (PortfolioOff.enableOnSave(true, settingsRepository.current().portfolioEnabled)) {
            if (safe { settingsRepository.setPortfolioEnabled(true) } != null) {
                onPortfolioEnabled()
                safe { snapshotUpdater.redrawWidgets() }
            }
        }
        // Neue Adresse ist sofort fällig (noch nie versucht); danach Kurse für BTC/ETH
        addressRefresher.refresh(force = false)
        calcTransactions.value?.let { list -> loadPrices(list.flatMap { it.coins() }.toSet(), force = false) }
    }
}

/**
 * Adresse entfernen (nach Bestätigung). [onRemoved] bekommt die ganze Zeile (Eröffnungsposten, Verlauf, Token-Reihen)
 * für «Rückgängig» ([restoreWatchedAddress]); gesperrt ([com.cryptochecker.app.data.portfolio.PortfolioStorageLock]):
 * nichts entfernt, [onLocked] — nie still. Nicht geschrieben (Datenbank gerade nicht beschreibbar): [onFailed]
 * (vorher still). Schon weg (keine Zeile): nichts zu melden.
 */
fun PortfolioViewModel.removeWatchedAddress(
    id: Long,
    onLocked: () -> Unit = {},
    onFailed: () -> Unit = {},
    onRemoved: (WatchedAddressEntity) -> Unit = {},
) {
    viewModelScope.launch {
        val removed = try {
            repository.removeWatchedAddress(id)
        } catch (e: PortfolioStorageLockedException) {
            onLocked()
            return@launch
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Adresse nicht entfernt")
            onFailed()
            return@launch
        }
        removed?.let(onRemoved)
    }
}

/**
 * «Rückgängig» nach [removeWatchedAddress]: dieselbe Adresse mit alter Id samt allem, was dazugehört; gesperrt: [onLocked].
 * Nicht zurückgeholt (Adresse inzwischen neu hinzugefügt, Liste voll, nicht gespeichert): [onFailed] mit dem Grund
 * ([WatchedAddressUndo.failure]) — nie still.
 */
fun PortfolioViewModel.restoreWatchedAddress(
    row: WatchedAddressEntity,
    onLocked: () -> Unit = {},
    onFailed: (WatchedAddressUndo.RestoreFailure) -> Unit = {},
) {
    viewModelScope.launch {
        val restored = try {
            repository.restoreWatchedAddress(row)
        } catch (e: PortfolioStorageLockedException) {
            onLocked()
            return@launch
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Adresse nicht zurückgeholt")
            false
        }
        if (restored) return@launch
        val existing = safe { repository.getWatchedAddresses() }.orEmpty()
        val removed = row.toDomain()
        onFailed(removed?.let { WatchedAddressUndo.failure(existing, it) } ?: WatchedAddressUndo.RestoreFailure.NOT_SAVED)
    }
}

/** Gewählte EVM-Netze einer 0x-Adresse; ein neu gewähltes wird gleich gelesen. */
fun PortfolioViewModel.setWatchedAddressNetworks(id: Long, networks: List<String>) {
    viewModelScope.launch {
        safe { repository.setWatchedAddressNetworks(id, networks) } ?: return@launch
        addressRefresher.refresh(force = false)
        calcTransactions.value?.let { list -> loadPrices(list.map { it.coin }.toSet(), force = false) }
    }
}

/** «Nur anzeigen, nicht zum Portfolio zählen» (false) bzw. zählen (true). */
fun PortfolioViewModel.setWatchedAddressCountInPortfolio(id: Long, on: Boolean) {
    viewModelScope.launch { safe { repository.setWatchedAddressCountInPortfolio(id, on) } }
}

/**
 * «Tokens mitzählen» einer Adresse. An: die Adresse ist sofort fällig — Token gleich lesen, dann Kurse der
 * neuen Coins. Aus: Token-Bestand und -Reihen weg.
 */
fun PortfolioViewModel.setWatchedAddressCountTokens(id: Long, on: Boolean) {
    viewModelScope.launch {
        safe { repository.setWatchedAddressCountTokens(id, on) } ?: return@launch
        if (!on) return@launch
        addressRefresher.refresh(force = false)
        calcTransactions.value?.let { list -> loadPrices(list.map { it.coin }.toSet(), force = false) }
    }
}
