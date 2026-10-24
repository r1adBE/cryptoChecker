// «Mehrere Paare auf einmal» (Teil von `ExplorerViewModel`): Gegenwährung, Liste sammeln, Kennzahlen für
// die Schnellfilter, Paare an-/abwählen und gemeinsam hinzufügen.
// Eigene Datei, weil die Sammelaufnahme ein eigener Modus ist; Erweiterungsfunktionen auf dem ViewModel
// (wie `ExplorerBulkActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun ExplorerViewModel.setBulkQuote(quote: String) {
    // Die Sammelliste gehört zur Gegenwährung, für die sie erstellt wurde
    if (bulkQuote.value != quote) {
        clearBulkList()
        bulkAdd.value = null
    }
    bulkQuote.value = quote
}

internal fun ExplorerViewModel.clearBulkList() {
    bulkPairs.value = emptyList()
    bulkEmpty.value = false
}

/**
 * Kennzahlen für die Schnellfilter von «Alle …-Paare»: eine Sammelabfrage der Börse (wie beim
 * Aktualisieren der Merkliste), nur wenn sie eine hat — nie Einzelabfragen. Dieselbe Liste
 * höchstens einmal pro Minute.
 */
fun ExplorerViewModel.loadBulkStats() {
    val selected = exchange.value ?: return
    if (selected.key == DEX_KEY || !marketRepository.isMarketSupportsBulkTickers(selected)) return
    val ids = bulkPairs.value.mapNotNull { it.pairId }.distinct()
    if (ids.isEmpty()) return

    val key = selected.key + "|" + ids.sorted().joinToString(",")
    val now = System.currentTimeMillis()
    val fresh = bulkStatsJob?.isActive == true || now - bulkStatsLoadedAt < BULK_STATS_MAX_AGE_MS
    if (key == bulkStatsFor && fresh) return

    bulkStatsFor = key
    bulkStatsLoadedAt = now
    bulkStatsJob?.cancel()
    bulkStatsJob = viewModelScope.launch {
        val bulk = withContext(Dispatchers.IO) { marketRepository.getBulkTickers(selected, ids) }
        if (exchange.value != selected) return@launch
        bulkStats.value = bulk.tickers.mapValues { (_, t) -> QuickFilters.statsOf(t) }
    }
}

/** Alle Paare der gewählten Gegenwährung (Standard USDT) zum aktuellen Kontrakttyp, Spot wie Futures. */
fun ExplorerViewModel.collectBulkPairs() {
    val info = pairList.value
    val counter = bulkQuote.value
    val found = if (info != null && counter != null) info.pairsWithQuote(counter, contract.value) else emptyList()
    bulkPairs.value = found
    bulkEmpty.value = found.isEmpty()

    val first = found.firstOrNull() ?: return
    quote.value = first.quote
    coin.value = first.base
}

fun ExplorerViewModel.selectBulkPair(pair: MarketPair) {
    coin.value = pair.base
    quote.value = pair.quote
    contract.value = pair.contractType
}

/**
 * Alle gefundenen Paare in die Merkliste. Schon vorhandene bleiben unberührt — samt ihren Alarmen,
 * die am bestehenden Eintrag hängen. Höchstens bis zur vollen Merkliste
 * ([com.cryptochecker.app.domain.watch.WatchlistLimits.MAX_PAIRS]).
 */
fun ExplorerViewModel.addBulkPairs() {
    val selected = exchange.value
    val pairs = bulkPairs.value
    if (selected == null || pairs.isEmpty()) {
        addResult.value = AddResult.NO_PAIR_SELECTED
        return
    }
    if (bulkAdd.value?.running == true || bulkAddRunner.progress.value?.running == true) return

    viewModelScope.launch {
        bulkAdd.value = BulkAddResult(added = 0, skipped = 0, running = true)
        // Rückfrage F-08 einmal für alle Paare; «Abbrechen» = nichts angelegt, keine Zählung
        if (!watchRepository.confirmUserWrite()) {
            bulkAdd.value = null
            return@launch
        }
        // Läuft app-weit weiter (Verlassen des Bildschirms bricht nicht ab); Stand und Ergebnis über
        // [bulkAddFromRunner]. Gesperrt: keine Zählung, den kurzen Hinweis zeigt WatchlistLockedHint; voll: die
        // Rückmeldung nennt die übrigen
        if (!bulkAddRunner.start(selected, pairs, sessionTargetGroup.value)) bulkAdd.value = null
    }
}

/** Stand von [com.cryptochecker.app.data.BulkAddRunner] als Anzeige des Explorers (Balken, Ergebnis). */
internal fun bulkAddFromRunner(p: com.cryptochecker.app.data.BulkAddRunner.Progress?): BulkAddResult? = when {
    p == null || p.locked -> null
    p.running -> BulkAddResult(added = 0, skipped = 0, running = true, done = p.done, total = p.total)
    else -> p.result?.let { BulkAddResult(added = it.added, skipped = it.skipped, refused = it.refused, addedIds = it.addedIds) }
}

/**
 * Stand der app-weiten Aufnahme übernehmen — auch nach Rückkehr auf den Bildschirm (neues ViewModel). Noch keine
 * Aufnahme (null) ändert nichts (Rückfrage läuft evtl. noch); gesperrt: keine Zählung.
 */
internal suspend fun ExplorerViewModel.collectBulkRunner() {
    bulkAddRunner.progress.collect { p ->
        when {
            p == null -> Unit
            p.locked -> {
                bulkAdd.value = null
                bulkAddRunner.clearResult()
            }
            else -> bulkAdd.value = bulkAddFromRunner(p)
        }
    }
}

/** «Rückgängig» in der Rückmeldung: die eben neu angelegten Paare wieder entfernen. */
fun ExplorerViewModel.undoBulkAdd(ids: List<Long>) {
    bulkAddRunner.undo(ids)
}

fun ExplorerViewModel.clearBulkAddResult() {
    bulkAdd.value = null
    bulkAddRunner.clearResult()
}

/** Kennzahlen der Schnellfilter höchstens einmal pro Minute neu abfragen. */
private const val BULK_STATS_MAX_AGE_MS = 60_000L
