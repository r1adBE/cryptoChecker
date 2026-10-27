package com.cryptochecker.app.data

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.model.MarketPair
import kotlinx.coroutines.CancellationException
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
 * Explorer und über der Merkliste; jedes Ende ist eindeutig (Ergebnis, gesperrt, abgebrochen, Fehler) und bleibt
 * stehen, bis ein Bildschirm es gemeldet hat ([clearResult]). Schreibwege ([BulkAddWrites]), Bereich und Uhr sind über
 * den internen Konstruktor austauschbar (`BulkAddRunnerTest`).
 */
@Singleton
class BulkAddRunner constructor(
    private val writes: BulkAddWrites,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(watchRepository: WatchRepository, addMoments: AddMoments) :
        this(RepositoryBulkAddWrites(watchRepository, addMoments), appScope(Dispatchers.Default, "Paare aufnehmen"))

    /**
     * Stand: [done] von [total] verarbeitet; am Ende genau eines von [result], [locked], [cancelled] oder [failed].
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val result: BulkAddResult? = null,
        val locked: Boolean = false,
        /** «Abbrechen» der Rückfrage F-08 im Runner: nichts angelegt. */
        val cancelled: Boolean = false,
        /** Mit Fehler beendet (z. B. Datenbank-Schlüssel nicht lesbar); bis dahin angelegte Paare bleiben. */
        val failed: Boolean = false,
        /** Zeitpunkt des Ergebnisses (Uhr des Runners, ms) für das Fenster von «Rückgängig» ([undoOffered]). */
        val finishedAt: Long = 0,
    ) {
        val running: Boolean get() = result == null && !locked && !cancelled && !failed
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
                    state.value = Progress(0, total, cancelled = true)
                    return@launch
                }
                val outcome = writes.add(market, pairs, group) { done ->
                    state.value = Progress(done, total)
                }
                state.value = if (outcome.locked) Progress(total, total, locked = true)
                else Progress(total, total, result = outcome, finishedAt = now())
                // Viele auf einmal: kein Erst-Moment, das erste Paar gilt aber als erledigt
                if (outcome.added > 0) writes.markFirstPairAdded()
            } catch (e: Throwable) {
                // Fehler (z. B. Datenbank-Schlüssel gerade nicht lesbar) oder Bereich beendet: eindeutiges Ende statt
                // eines Balkens, der ewig steht. Ein schon gemeldetes Ende bleibt.
                val cancelled = e is CancellationException
                state.update { p ->
                    if (p == null || !p.running) p
                    else Progress(p.done, total, cancelled = cancelled, failed = !cancelled)
                }
                throw e
            }
        }
        return true
    }

    /**
     * «Rückgängig» nach dem Hinzufügen: die neu angelegten Paare [ids] wieder löschen (bestehende bleiben), ausser
     * denen, die inzwischen einen Alarm bekommen haben. Läuft wie die Aufnahme app-weit, mit eigener Zustimmung F-08.
     */
    fun undo(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            if (!writes.confirm()) return@launch
            writes.deleteWithoutAlarms(ids)
        }
    }

    /** «Rückgängig» für dieses Ergebnis noch anbieten? Nur kurz nach dem Ende ([UNDO_WINDOW_MS]). */
    fun undoOffered(p: Progress): Boolean = undoOffered(p.finishedAt, now())

    /** Ergebnis gemeldet (Explorer oder Merkliste): Anzeige weg. Eine laufende Aufnahme bleibt. */
    fun clearResult() {
        state.update { if (it?.running == true) it else null }
    }

    companion object {
        /** So lange nach dem Ende wird «Rückgängig» angeboten; danach nur noch die Meldung. */
        const val UNDO_WINDOW_MS = 60_000L

        /** Ende ([finishedAt], 0 = keins) höchstens [UNDO_WINDOW_MS] vor [now]; Uhr rückwärts: nein. */
        fun undoOffered(finishedAt: Long, now: Long): Boolean = finishedAt > 0 && now - finishedAt in 0..UNDO_WINDOW_MS
    }
}

/**
 * Schreibwege der Sammelaufnahme: im Betrieb das Repository ([RepositoryBulkAddWrites]); Tests setzen eine Fälschung
 * ein (`BulkAddRunnerTest`). Jeder Weg läuft in der Coroutine des Aufrufers — die Zustimmung F-08 aus [confirm] gilt
 * dort samt Kindern (`WatchlistNotSavedGate`).
 */
interface BulkAddWrites {
    /** Rückfrage F-08 ([confirmUserWrite]); false = «Abbrechen». */
    suspend fun confirm(): Boolean

    /** Paare anlegen ([addWatches]). */
    suspend fun add(
        market: MarketInfo,
        pairs: List<MarketPair>,
        group: String?,
        onProgress: (Int) -> Unit,
    ): BulkAddResult

    /** Diese Paare löschen, soweit sie keinen Alarm haben ([deleteWatchesWithoutAlarms]). */
    suspend fun deleteWithoutAlarms(ids: List<Long>)

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

    override suspend fun deleteWithoutAlarms(ids: List<Long>) {
        watchRepository.deleteWatchesWithoutAlarms(ids)
    }

    override suspend fun markFirstPairAdded() = addMoments.markFirstPairAdded()
}
