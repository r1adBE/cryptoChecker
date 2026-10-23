package com.cryptochecker.app.data

import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.model.MarketPair
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
 */
@Singleton
class BulkAddRunner @Inject constructor(
    private val watchRepository: WatchRepository,
    private val addMoments: AddMoments,
) {
    /** Stand: [done] von [total] verarbeitet; [result] erst am Ende (gesperrt: null und [locked]). */
    data class Progress(
        val done: Int,
        val total: Int,
        val result: BulkAddResult? = null,
        val locked: Boolean = false,
    ) {
        val running: Boolean get() = result == null && !locked
    }

    private val scope = appScope(Dispatchers.Default, "Paare aufnehmen")
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
                if (!watchRepository.confirmUserWrite()) {
                    state.value = null
                    return@launch
                }
                val outcome = watchRepository.addWatches(market, pairs, group) { done ->
                    state.value = Progress(done, total)
                }
                state.value = if (outcome.locked) Progress(total, total, locked = true)
                else Progress(total, total, result = outcome)
                // Viele auf einmal: kein Erst-Moment, das erste Paar gilt aber als erledigt
                if (outcome.added > 0) addMoments.markFirstPairAdded()
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
            if (!watchRepository.confirmUserWrite()) return@launch
            watchRepository.deleteWatches(ids)
        }
    }

    /** Ergebnis gemeldet (Explorer oder Merkliste): Anzeige weg. Eine laufende Aufnahme bleibt. */
    fun clearResult() {
        state.update { if (it?.running == true) it else null }
    }
}
