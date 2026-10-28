package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.work.ExactCheckScheduler
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * «Aktualisierung» › «Pünktlich prüfen» und «Letzte Prüfungen»: stellt den exakten Wecker passend zum Schalter und zu
 * den aktiven Paaren und liefert die Liste der letzten Prüfungen. Eigene Klasse, damit [SettingsViewModel] nur einen
 * Baustein dafür braucht.
 */
class ExactCheckControl @Inject constructor(
    private val exactScheduler: ExactCheckScheduler,
    private val checkRunStore: CheckRunStore,
    private val watchRepository: WatchRepository,
) {
    /** «Letzte Prüfungen», neueste zuerst. */
    val runs: StateFlow<List<CheckRun>> get() = checkRunStore.runs

    /** Wecker stellen bzw. absagen; ohne aktive Paare prüft der Hintergrund nichts. */
    suspend fun sync(enabled: Boolean) {
        exactScheduler.sync(enabled, hasWork = watchRepository.getWatches().any { !it.disabled })
    }
}
