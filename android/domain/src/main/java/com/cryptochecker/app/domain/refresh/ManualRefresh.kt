package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.cryptochecker.app.util.DomainLog

/**
 * Aktualisierung per Knopf bzw. nach unten ziehen in der App: direkt in einer
 * App-weiten Coroutine, nicht mehr über WorkManager. Dort stand der Auftrag als
 * «bevorzugte Arbeit» an — mit aufgebrauchtem Kontingent lief er als gewöhnliche
 * Arbeit und der Kreisel drehte, bevor überhaupt eine Anfrage ins Netz ging.
 * App-weit (nicht im viewModelScope), damit der Durchlauf auch nach dem Schliessen
 * der App zu Ende läuft — samt Widgets. WorkManager bleibt für die Hintergrund-
 * Aktualisierung und den Knopf im Widget.
 *
 * Mit [RefreshDebounce]: kein zweiter Durchlauf, solange einer läuft oder der letzte
 * vollständige keine 15 s her ist.
 */
/** Zeit des letzten vollständigen Durchlaufs (`RefreshStats.lastFullRefresh`) — Sperre für «Aktualisieren». */
fun interface ManualRefreshStats {
    fun lastFullRefresh(): Long
}

/** Netzstatus (`ConnectivityMonitor`): Fluss für «wieder online» und die Abfrage jetzt. */
interface ManualRefreshNet {
    val online: StateFlow<Boolean>
    fun isOnline(): Boolean
}

class ManualRefresh(
    private val priceRefresher: PriceRefresher,
    private val refreshStats: ManualRefreshStats,
    private val connectivity: ManualRefreshNet,
    private val checkRuns: CheckRunStore,
) {
    private val scope = appScope(Dispatchers.Default, "ManualRefresh")

    private val _running = MutableStateFlow(false)

    /** true, solange eine per Knopf angestossene Aktualisierung läuft. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Erst nach _running (Reihenfolge der Initialisierung)
    init {
        // Netz wieder da (#22/#23): genau eine Aktualisierung, solange die App sichtbar ist.
        // Im Hintergrund übernimmt das WorkManager (Bedingung «Netz verbunden»).
        scope.launch {
            var previous: Boolean? = null
            connectivity.online.collect { online ->
                if (OfflineGate.resumeOnChange(previous, online) && AppVisibility.visible) request()
                previous = online
            }
        }
    }

    /**
     * Erzwungene Gesamt-Aktualisierung anfragen.
     * @param otherRunning läuft anderswo schon eine angestossene Aktualisierung (Widget-Knopf)?
     * @return was geschehen ist; bei [RefreshDebounce.Decision.RECENT] zeigt die App kurz
     *   «Gerade aktualisiert»
     */
    fun request(otherRunning: Boolean = false): RefreshDebounce.Decision {
        val decision = synchronized(this) {
            val decision = RefreshDebounce.decide(
                running = _running.value || otherRunning || priceRefresher.fullRefreshRunning.value,
                lastFinishedAt = refreshStats.lastFullRefresh(),
                now = System.currentTimeMillis(),
            )
            if (decision == RefreshDebounce.Decision.START) _running.value = true
            decision
        }
        if (decision == RefreshDebounce.Decision.START) start()
        return decision
    }

    /** Ohne Sperre (z. B. gleich nach dem Hinzufügen neuer Paare) — läuft schon eine, nichts tun. */
    fun requestUnguarded() {
        synchronized(this) {
            if (_running.value) return
            _running.value = true
        }
        start()
    }

    private fun start() {
        scope.launch {
            val startedAt = System.currentTimeMillis()
            // «Letzte Prüfungen»: auch der Knopf in der App zählt (Auslöser «App»)
            fun log(result: CheckResult) = checkRuns.add(
                CheckRun(System.currentTimeMillis(), CheckTrigger.FOREGROUND, result, System.currentTimeMillis() - startedAt)
            )
            try {
                // Offline überspringt refreshAll (gleicher Netzstatus) — als «übersprungen» vermerken
                val offline = !connectivity.isOnline()
                val summary = priceRefresher.refreshAll()
                log(if (offline) CheckResult.SKIPPED else CheckRunLog.result(summary.checked, summary.failed))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Der Bericht hält den Abbruch schon fest (PriceRefresher.refreshAllGuarded)
                DomainLog.w(ex, "Aktualisierung per Knopf fehlgeschlagen")
                log(CheckResult.FAILED)
            } finally {
                _running.value = false
            }
        }
    }
}
