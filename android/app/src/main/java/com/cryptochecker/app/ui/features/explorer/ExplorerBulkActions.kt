// «Mehrere Paare auf einmal» (Teil von `ExplorerViewModel`): Gegenwährung, Liste sammeln, Kennzahlen für
// die Schnellfilter, Paare an-/abwählen und gemeinsam hinzufügen.
// Eigene Datei, weil die Sammelaufnahme ein eigener Modus ist; Erweiterungsfunktionen auf dem ViewModel
// (wie `ExplorerBulkActions.swift`).
package com.cryptochecker.app.ui.features.explorer

import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.BulkAddRunner
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun ExplorerViewModel.setBulkQuote(quote: String) {
    // Die Sammelliste gehört zur Gegenwährung, für die sie erstellt wurde
    if (bulk.quote.value != quote) {
        clearBulkList()
        bulk.add.value = null
    }
    bulk.quote.value = quote
}

internal fun ExplorerViewModel.clearBulkList() {
    bulk.pairs.value = emptyList()
    bulk.empty.value = false
}

/**
 * Kennzahlen für die Schnellfilter von «Alle …-Paare»: eine Sammelabfrage der Börse (wie beim
 * Aktualisieren der Merkliste), nur wenn sie eine hat — nie Einzelabfragen. Dieselbe Liste
 * höchstens einmal pro Minute.
 */
fun ExplorerViewModel.loadBulkStats() {
    val selected = exchange.value ?: return
    if (selected.key == DEX_KEY || !exchangeRepository.isExchangeSupportsBulkTickers(selected)) return
    val ids = bulk.pairs.value.mapNotNull { it.pairId }.distinct()
    if (ids.isEmpty()) return

    val key = selected.key + "|" + ids.sorted().joinToString(",")
    val now = System.currentTimeMillis()
    val fresh = bulk.statsJob?.isActive == true || now - bulk.statsLoadedAt < BULK_STATS_MAX_AGE_MS
    if (key == bulk.statsFor && fresh) return

    bulk.statsFor = key
    bulk.statsLoadedAt = now
    bulk.statsJob?.cancel()
    bulk.statsJob = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { exchangeRepository.getBulkTickers(selected, ids) }
        if (exchange.value != selected) return@launch
        bulk.stats.value = result.tickers.mapValues { (_, t) -> QuickFilters.statsOf(t) }
    }
}

/** Alle Paare der gewählten Gegenwährung (Standard USDT) zum aktuellen Kontrakttyp, Spot wie Futures. */
fun ExplorerViewModel.collectBulkPairs() {
    val info = pairList.value
    val counter = bulk.quote.value
    val found = if (info != null && counter != null) info.pairsWithQuote(counter, contract.value) else emptyList()
    bulk.pairs.value = found
    bulk.empty.value = found.isEmpty()

    val first = found.firstOrNull() ?: return
    quote.value = first.quote
    coin.value = first.base
}

fun ExplorerViewModel.selectBulkPair(pair: ExchangePair) {
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
    val pairs = bulk.pairs.value
    if (selected == null || pairs.isEmpty()) {
        addResult.value = AddResult.NO_PAIR_SELECTED
        return
    }
    if (bulk.add.value?.running == true || bulkAddRunner.progress.value?.running == true) return

    viewModelScope.launch {
        bulk.add.value = BulkAddResult(added = 0, skipped = 0, running = true)
        // Rückfrage F-08 einmal für alle Paare; «Abbrechen» = nichts angelegt, keine Zählung
        if (!watchRepository.confirmUserWrite()) {
            bulk.add.value = null
            return@launch
        }
        // Läuft app-weit weiter (Verlassen des Bildschirms bricht nicht ab); Stand und Ergebnis über
        // [bulkAddFromRunner]. Gesperrt: keine Zählung, den kurzen Hinweis zeigt WatchlistLockedHint; voll: die
        // Rückmeldung nennt die übrigen
        if (!bulkAddRunner.start(selected, pairs, sessionTargetGroup.value)) bulk.add.value = null
    }
}

/**
 * Stand von [BulkAddRunner] als Anzeige des Explorers (Balken, Ergebnis, Fehler). Gesperrt
 * oder abgebrochen: nichts (Knopf wieder frei, kein Balken, keine Zählung).
 */
internal fun bulkAddFromRunner(p: BulkAddRunner.Progress?): BulkAddResult? = when {
    p == null || p.locked || p.cancelled -> null
    p.failed -> BulkAddResult(added = 0, skipped = 0, failed = true)
    p.running -> BulkAddResult(added = 0, skipped = 0, running = true, done = p.done, total = p.total)
    else -> p.result?.let {
        BulkAddResult(
            added = it.added, skipped = it.skipped, refused = it.refused, addedIds = it.addedIds,
            finishedAt = p.finishedAt,
        )
    }
}

/** Stand der app-weiten Aufnahme übernehmen — auch nach Rückkehr auf den Bildschirm (neues ViewModel). */
internal suspend fun ExplorerViewModel.collectBulkRunner() = bulkAddDisplay.collect()

/** Der Explorer beginnt, das Ergebnis zu zeigen ([BulkAddDisplay.claim]). */
fun ExplorerViewModel.markBulkAddClaimed() = bulkAddDisplay.claim()

/** «Rückgängig» in der Rückmeldung: die eben neu angelegten Paare wieder entfernen. */
fun ExplorerViewModel.undoBulkAdd(ids: List<Long>) {
    bulkAddRunner.undo(ids)
}

/** Meldung zu: Anzeige weg. */
fun ExplorerViewModel.clearBulkAddResult() = bulkAddDisplay.clear()

/**
 * Anzeige der app-weiten Aufnahme im Explorer ([shown] = `ExplorerViewModel.bulkAdd`): übernimmt den Stand des Runners
 * und sorgt dafür, dass jedes Ende genau einmal gemeldet wird — vom Explorer oder von der Merkliste. Ohne ViewModel
 * testbar (`BulkAddRunnerTest`).
 */
internal class BulkAddDisplay(
    private val runner: BulkAddRunner,
    private val shown: MutableStateFlow<BulkAddResult?>,
) {
    /** Ende in [shown], das der Explorer meldet ([claim]); bleibt stehen, auch wenn der Runner schon leer ist. */
    private var claimed: BulkAddResult? = null

    suspend fun collect() {
        runner.progress.collect { apply(it) }
    }

    /**
     * Gesperrt, abgebrochen oder Fehler: Anzeige danach ([bulkAddFromRunner]: Knopf frei, kein Balken; Fehler als
     * Meldung des Explorers) und das Ende im Runner abräumen. Noch keine Aufnahme (null) ändert nichts (Rückfrage
     * läuft evtl. noch); ein Ergebnis, das der Explorer noch nicht übernommen hat, hat dann die Merkliste gemeldet:
     * nicht noch einmal zeigen.
     */
    fun apply(p: BulkAddRunner.Progress?) {
        when {
            p == null -> {
                val current = shown.value
                if (current != null && !current.running && current !== claimed) shown.value = null
            }
            p.running || p.result != null -> shown.value = bulkAddFromRunner(p)
            else -> {
                shown.value = bulkAddFromRunner(p)
                claimed = shown.value
                runner.clearResult()
            }
        }
    }

    /**
     * Der Explorer beginnt, das Ergebnis zu zeigen: im Runner gilt es damit als gemeldet (die Merkliste zeigt es nicht
     * noch einmal). [shown] bleibt unverändert bis [clear] — sonst bräche die laufende Snackbar ab.
     */
    fun claim() {
        claimed = shown.value
        runner.clearResult()
    }

    fun clear() {
        shown.value = null
        claimed = null
        runner.clearResult()
    }
}

/** Kennzahlen der Schnellfilter höchstens einmal pro Minute neu abfragen. */
private const val BULK_STATS_MAX_AGE_MS = 60_000L
