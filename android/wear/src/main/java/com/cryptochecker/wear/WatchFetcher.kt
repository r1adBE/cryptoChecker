package com.cryptochecker.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * dann gilt der Abruf nicht als fehlgeschlagen. [scope] läuft auf dem Hauptthread. Nach «Aktualisieren» ([refresh] mit
 * `force`) bleibt [fetching] mindestens [tapAckMillis] an (Kreisel im Knopf). Wie iOS `WatchModel.refresh`.
 */
class WatchFetcher(
    private val scope: CoroutineScope,
    private val shown: () -> List<String>,
    private val fetch: suspend (List<String>) -> Map<String, WatchQuote>,
    private val save: (quotes: Map<String, WatchQuote>, startedAt: Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val plan: suspend (coins: List<String>, force: Boolean) -> List<String> = { coins, _ -> coins },
    private val tapAckMillis: Long = 0L,
) {
    private val gate = WatchFetchGate()
    private var job: Job? = null
    private val _loading = MutableStateFlow(false)
    private val _failed = MutableStateFlow(false)
    private val _fetching = MutableStateFlow(false)

    /** Ein Abruf läuft. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Der letzte gültige Abruf brachte keinen einzigen Kurs (bei nicht leerer Liste). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Ein Abruf an der Quelle läuft (Kreisel im Knopf); nichts zu holen ([plan] leer): aus. */
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    /**
     * Neu laden; [force] = «Aktualisieren»: alles, auch Coins vom Handy (sonst nur, was nach [plan] fällig ist — beim
     * Öffnen und im Takt der offenen App).
     */
    fun refresh(force: Boolean) {
        val coins = shown()
        val run = gate.start()
        val startedAt = now()
        job?.cancel()
        _loading.value = true
        // Erst zuweisen, dann starten: am Ende setzt nur der jeweils letzte Abruf «lädt» zurück, auch wenn sein
        // Ergebnis verworfen wird (andere Liste) – sonst bliebe der Ladekreis stehen, falls kein neuer Abruf folgt
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                load(coins, run, startedAt, force)
            } finally {
                if (job === coroutineContext[Job]) {
                    _loading.value = false
                    _fetching.value = false
                }
            }
        }
        job = launched
        launched.start()
    }

    private suspend fun load(coins: List<String>, generation: Long, startedAt: Long, force: Boolean) {
        val need = try {
            plan(coins, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coins
        }
        _fetching.value = need.isNotEmpty()
        val quotes = if (need.isEmpty()) {
            emptyMap()
        } else {
            try {
                fetch(need)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }
        if (!gate.accepts(generation, coins, shown())) return
        if (quotes.isNotEmpty()) save(quotes, startedAt)
        _failed.value = quotes.isEmpty() && need.isNotEmpty()
        // Knopf: Kreisel mindestens kurz, damit der Tipp sichtbar ankommt
        if (force && need.isNotEmpty()) {
            val left = tapAckMillis - (now() - startedAt)
            if (left > 0L) delay(left)
        }
    }
}
