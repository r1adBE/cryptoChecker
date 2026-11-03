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
 * beim Aufruf von [refresh]) für [WatchCache.merge]. Welche Coins die Uhr selbst abfragt, bestimmt [plan] (Liste,
 * auf Wunsch?) — z. B. keine, solange das Handy frische Kurse liefert ([com.cryptochecker.marketdata.watch.WatchPhoneQuotes.selfFetch]);
 * dann gilt der Abruf nicht als fehlgeschlagen. [scope] läuft auf dem Hauptthread. Wie iOS `WatchModel.refresh`.
 */
class WatchFetcher(
    private val scope: CoroutineScope,
    private val shown: () -> List<String>,
    private val fetch: suspend (List<String>) -> Map<String, WatchQuote>,
    private val save: (quotes: Map<String, WatchQuote>, startedAt: Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val plan: suspend (coins: List<String>, force: Boolean) -> List<String> = { coins, _ -> coins },
) {
    private val gate = WatchFetchGate()
    private var job: Job? = null
    private val _loading = MutableStateFlow(false)
    private val _failed = MutableStateFlow(false)

    /** Ein Abruf läuft. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Der letzte gültige Abruf brachte keinen einzigen Kurs (bei nicht leerer Liste). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Neu laden; [force] = «Aktualisieren» (sonst nur, was nach [plan] fällig ist, z. B. im Takt der offenen App). */
    fun refresh(force: Boolean = true) {
        val coins = shown()
        val run = gate.start()
        val startedAt = now()
        job?.cancel()
        _loading.value = true
        job = scope.launch {
            val need = runCatching { plan(coins, force) }.getOrElse { if (it is CancellationException) throw it else coins }
            val quotes = if (need.isEmpty()) {
                emptyMap()
            } else {
                runCatching { fetch(need) }.getOrElse { if (it is CancellationException) throw it else emptyMap() }
            }
            if (!gate.accepts(run, coins, shown())) return@launch
            if (quotes.isNotEmpty()) save(quotes, startedAt)
            _failed.value = quotes.isEmpty() && need.isNotEmpty()
            _loading.value = false
        }
    }
}
