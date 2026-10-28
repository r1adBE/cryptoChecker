package com.cryptochecker.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Abrufe der Uhr-App: [refresh] bricht einen laufenden Abruf ab und startet einen neuen für die gerade gezeigte Liste
 * ([shown]). Ein Ergebnis geht nur an [save], wenn es noch zur gezeigten Liste gehört ([WatchFetchGate]) — eine
 * langsame Antwort zu einer älteren Liste überschreibt die neuere nicht. [save] bekommt den Start des Abrufs ([now]
 * beim Aufruf von [refresh]) für [WatchCache.merge]. [scope] läuft auf dem Hauptthread. Wie iOS `WatchModel.refresh`.
 */
class WatchFetcher(
    private val scope: CoroutineScope,
    private val shown: () -> List<String>,
    private val fetch: suspend (List<String>) -> Map<String, WatchQuote>,
    private val save: (quotes: Map<String, WatchQuote>, startedAt: Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = WatchFetchGate()
    private var job: Job? = null
    private val _loading = MutableStateFlow(false)
    private val _failed = MutableStateFlow(false)

    /** Ein Abruf läuft. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Der letzte gültige Abruf brachte keinen einzigen Kurs (bei nicht leerer Liste). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun refresh() {
        val coins = shown()
        val run = gate.start()
        val startedAt = now()
        job?.cancel()
        _loading.value = true
        job = scope.launch {
            val quotes = runCatching { fetch(coins) }.getOrElse { if (it is CancellationException) throw it else emptyMap() }
            if (!gate.accepts(run, coins, shown())) return@launch
            if (quotes.isNotEmpty()) save(quotes, startedAt)
            _failed.value = quotes.isEmpty() && coins.isNotEmpty()
            _loading.value = false
        }
    }
}
