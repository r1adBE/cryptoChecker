package com.cryptochecker.app.data

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Mehrere Paare auf einmal» app-weit: Die Aufnahme läuft im Prozess-Bereich weiter, auch wenn der Bildschirm «Paar
 * hinzufügen» verlassen wird (vorher brach das Verlassen sie ab). [progress] zeigt den Stand für den Balken im
 * Explorer und über der Merkliste; das Ergebnis bleibt stehen, bis ein Bildschirm es gemeldet hat ([clearResult]).
 * Schreibwege ([BulkAddWrites]) und Bereich sind über den internen Konstruktor austauschbar (`BulkAddRunnerTest`).
 */
@Singleton
class BulkAddRunner internal constructor(
    private val writes: BulkAddWrites,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(watchRepository: WatchRepository, addMoments: AddMoments) :
        this(RepositoryBulkAddWrites(watchRepository, addMoments), appScope(Dispatchers.Default, "Paare aufnehmen"))

    /** Stand: [done] von [total] verarbeitet; [result] erst am Ende (gesperrt: null und [locked]). */
    data class Progress(
        val done: Int,
        val total: Int,
        val result: BulkAddResult? = null,
        val locked: Boolean = false,
    ) {
        val running: Boolean get() = result == null && !locked
    }

    private val state = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = state.asStateFlow()

    /** Startet die Aufnahme; false, wenn schon eine läuft. Die Rückfrage (Sperre) stellt der Aufrufer vorher. */
    fun start(market: MarketInfo, pairs: List<MarketPair>, group: String?): Boolean {
        val total = pairs.size
        var started = false
        state.update { current ->
            // update() kann den Block wiederholen: Merker jedes Mal neu setzen
            started = current?.running != true
            if (started) Progress(0, total) else current
        }
        if (!started) return false
        scope.launch {
            try {
                // Eigene Coroutine: Zustimmung F-08 hier noch einmal (nach der Antwort im Explorer ohne Dialog; ohne
                // Oberfläche durchgelassen gilt sie nur für die Coroutine der Aktion, also für diese)
                if (!writes.confirm()) {
                    state.value = null
                    return@launch
                }
                val outcome = writes.add(market, pairs, group) { done ->
                    state.value = Progress(done, total)
                }
                state.value = if (outcome.locked) Progress(total, total, locked = true)
                else Progress(total, total, result = outcome)
                // Viele auf einmal: kein Erst-Moment, das erste Paar gilt aber als erledigt
                if (outcome.added > 0) writes.markFirstPairAdded()
            } catch (e: Throwable) {
                // Fehler (z. B. Datenbank-Schlüssel gerade nicht lesbar): kein Balken, der ewig steht
                state.value = null
                throw e
            }
        }
        return true
    }

    /**
     * «Rückgängig» nach dem Hinzufügen: genau die neu angelegten Paare [ids] wieder löschen (bestehende bleiben).
     * Läuft wie die Aufnahme app-weit, mit eigener Zustimmung F-08.
     */
    fun undo(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            if (!writes.confirm()) return@launch
            writes.delete(ids)
        }
    }

    /** Ergebnis gemeldet (Explorer oder Merkliste): Anzeige weg. Eine laufende Aufnahme bleibt. */
    fun clearResult() {
        state.update { if (it?.running == true) it else null }
    }
}

/**
 * Schreibwege der Sammelaufnahme: im Betrieb das Repository ([RepositoryBulkAddWrites]); Tests setzen eine Fälschung
 * ein (`BulkAddRunnerTest`). Jeder Weg läuft in der Coroutine des Aufrufers — die Zustimmung F-08 aus [confirm] gilt
 * dort samt Kindern (`WatchlistNotSavedGate`).
 */
internal interface BulkAddWrites {
    /** Rückfrage F-08 ([confirmUserWrite]); false = «Abbrechen». */
    suspend fun confirm(): Boolean

    /** Paare anlegen ([addWatches]). */
    suspend fun add(
        market: MarketInfo,
        pairs: List<MarketPair>,
        group: String?,
        onProgress: (Int) -> Unit,
    ): BulkAddResult

    /** Genau diese Paare löschen ([WatchRepository.deleteWatches]). */
    suspend fun delete(ids: List<Long>)

    /** Erstes Paar gilt als erledigt ([AddMoments.markFirstPairAdded]). */
    suspend fun markFirstPairAdded()
}

private class RepositoryBulkAddWrites(
    private val watchRepository: WatchRepository,
    private val addMoments: AddMoments,
) : BulkAddWrites {
    override suspend fun confirm(): Boolean = watchRepository.confirmUserWrite()

    override suspend fun add(market: MarketInfo, pairs: List<MarketPair>, group: String?, onProgress: (Int) -> Unit) =
        watchRepository.addWatches(market, pairs, group, onProgress)

    override suspend fun delete(ids: List<Long>) {
        watchRepository.deleteWatches(ids)
    }

    override suspend fun markFirstPairAdded() = addMoments.markFirstPairAdded()
}
