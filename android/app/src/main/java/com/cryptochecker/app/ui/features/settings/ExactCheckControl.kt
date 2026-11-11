package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.work.ExactCheckDemand
import com.cryptochecker.app.work.ExactCheckScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * «Aktualisierung» › «Pünktlich prüfen» und «Letzte Prüfungen»: stellt den exakten Wecker passend zum Schalter und zum
 * Bedarf (aktive Paare, Portfolio-, Gas- oder Depeg-Alarm) und liefert die Liste der letzten Prüfungen. Eigene Klasse, damit [SettingsViewModel] nur einen
 * Baustein dafür braucht.
 */
class ExactCheckControl @Inject constructor(
    private val exactScheduler: ExactCheckScheduler,
    private val checkRunStore: CheckRunStore,
    private val demand: ExactCheckDemand,
) {
    /** «Letzte Prüfungen», neueste zuerst. */
    val runs: StateFlow<List<CheckRun>> get() = checkRunStore.runs

    /** Gibt es etwas pünktlich zu prüfen (aktive Paare, Portfolio-, Gas- oder Depeg-Alarm; [ExactCheckDemand])? */
    val hasWork: Flow<Boolean> get() = demand.observe()

    /** Wecker stellen bzw. absagen; ohne aktive Paare und ohne solche Alarme prüft der Hintergrund nichts. */
    suspend fun sync(enabled: Boolean) {
        exactScheduler.sync(enabled, hasWork = demand.hasWork())
    }
}
