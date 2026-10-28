// Auswahl «Erweitert / nach Börse» (Teil von `ExplorerViewModel`): Börse, Coin, Gegenwährung, Laufzeit,
// Paarliste neu laden, Kurs erneut holen und das gewählte Paar hinzufügen.
// Eigene Datei, weil der Schritt-für-Schritt-Ablauf eine eigene Aufgabe ist; Erweiterungsfunktionen auf dem
// ViewModel (wie `ExplorerSelectionActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.AddWatchOutcome
import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.app.data.tryAddWatch
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.ExchangeError
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.PairQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/** Kurs des gewählten Paars neu laden («Erneut»). */
fun ExplorerViewModel.retryPrice() {
    val selected = exchange.value ?: return
    val pair = selectedPair.value ?: return
    viewModelScope.launch {
        price.value = null
        // Fehler zeigen statt endlos «Kurs wird geladen…»
        price.value = priceOrError(selected, pair)
    }
}

fun ExplorerViewModel.selectExchange(picked: ExchangeInfo?) {
    if (exchange.value != picked) resetForNewExchange()
    exchange.value = picked
    if (picked != null) reloadIfNoPairs(picked)
}

/**
 * Noch keine Paare gespeichert? Dann gleich abgleichen — für den Nutzer kein eigener Schritt,
 * sondern einfach «Paare werden geladen…».
 */
internal fun ExplorerViewModel.reloadIfNoPairs(target: ExchangeInfo) {
    viewModelScope.launch {
        val noPairs = runCatchingCancellable { exchangeRepository.getExchangeCurrencyPairsInfo(target).pairs.isEmpty() }
            .getOrDefault(false)
        val stillWanted = exchange.value == target && !pairListSync.value.inProgress
        if (noPairs && exchangeRepository.isExchangeSupportsUpdatePairs(target) && stillWanted) {
            reloadPairList()
        }
    }
}

fun ExplorerViewModel.selectCoin(base: String?) {
    coin.value = base
}

fun ExplorerViewModel.selectQuote(counter: String?) {
    quote.value = counter
}

fun ExplorerViewModel.selectContract(type: FuturesContractType?) {
    // Die Sammelliste gehört zum Kontrakttyp, für den sie erstellt wurde
    if (contract.value != type) clearBulkList()
    contract.value = type
}

/**
 * Sammelliste, Kurs und Meldungen gehören nach einem Börsenwechsel zur alten Börse. Blieben sie
 * stehen, schriebe «Alle … Paare hinzufügen» fremde Paare unter der neuen Börse in die Merkliste.
 */
internal fun ExplorerViewModel.resetForNewExchange() {
    clearBulkList()
    bulk.add.value = null
    addResult.value = null
    price.value = null
    dex.pools.value = emptyList()
    dex.notice.value = null
    bulk.statsJob?.cancel()
    bulk.statsFor = null
    bulk.stats.value = emptyMap()
}

/** Paarliste der gewählten Börse neu holen (vollständige öffentliche Liste). */
@Synchronized
fun ExplorerViewModel.reloadPairList() {
    pairListSyncJob?.cancel()
    pairListSyncJob = null

    val target = exchange.value ?: return
    pairListSync.value = PairListSync(inProgress = true)
    pairListSyncJob = viewModelScope.launch {
        Timber.d("Paarliste abgleichen: ${target.key}")
        try {
            exchangeRepository.updateExchangeCurrencyPairs(target)
            runCatchingCancellable { pairsByExchange[target.key] = exchangeRepository.getExchangeCurrencyPairsInfo(target, withAlpha = true) }
            pairListChanged.emit(Unit)
            pairListSync.value = PairListSync()
            // Die Suche kennt die Liste jetzt auch
            if (searchableExchanges.isNotEmpty()) updateUnloaded()
        } catch (ex: ExchangeError) {
            pairListSync.value = PairListSync(error = ex.message)
        }
    }.apply {
        invokeOnCompletion { cause: Throwable? -> Timber.d(cause, "Abgleich beendet: %s", target.key) }
    }
}

/** Das gewählte Paar in die Merkliste. */
fun ExplorerViewModel.addSelectedPair() {
    val selected = exchange.value
    val pair = selectedPair.value
    if (selected == null || pair == null) {
        addResult.value = AddResult.NO_PAIR_SELECTED
        return
    }
    launchAdd(selected, pair) { added ->
        addResult.value = if (added) AddResult.ADDED else AddResult.ALREADY_IN_LIST
    }
}

// =============================================================================================
// Hilfen
// =============================================================================================

/**
 * Paar in die Merkliste (Ziel-Gruppe der Sitzung); erst melden, dann der Erst-Moment. Merkliste voll
 * ([com.cryptochecker.app.domain.watch.WatchlistLimits]): nichts angelegt, Hinweis statt [report] — für Suche, Auswahl
 * und DEX gleich.
 */
internal fun ExplorerViewModel.launchAdd(target: ExchangeInfo, pair: ExchangePair, report: (added: Boolean) -> Unit) {
    viewModelScope.launch {
        // Rückfrage F-08 vor dem Anlegen; «Abbrechen» = nichts, keine Meldung
        if (!watchRepository.confirmUserWrite()) return@launch
        when (val outcome = watchRepository.tryAddWatch(target, pair, sessionTargetGroup.value)) {
            is AddWatchOutcome.Added -> {
                report(true)
                addMoments.afterExplorerAdd(outcome.id)
            }
            AddWatchOutcome.Duplicate -> report(false)
            AddWatchOutcome.Full -> addResult.value = AddResult.WATCHLIST_FULL
            // Merkliste gesperrt: nichts angelegt, den kurzen Hinweis zeigt WatchlistLockedHint
            AddWatchOutcome.Locked -> Unit
        }
    }
}

/** Kurs holen; ein Fehler wird zum Ergebnis mit Fehlertext (Abbruch bleibt Abbruch). */
internal suspend fun ExplorerViewModel.priceOrError(target: ExchangeInfo, pair: ExchangePair): ExchangeTickerResult =
    try {
        exchangeRepository.getExchangeTicker(target, pair)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ExchangeTickerResult(
            MutableTicker(),
            PairQuery(pair.base, pair.quote, pair.pairId, pair.contractType),
            e.message ?: e.javaClass.simpleName,
        )
    }
