package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.domain.activity.ActivityReport
import com.cryptochecker.app.domain.model.WatchEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Was der Info-Tab von der Merkliste liest: ihre Paare (Tippen auf eine Zeile) und die Signale «Ungewöhnliche
 * Aktivität» je Paar. Nur lesen, ohne eigenen Zustand.
 */
class InfoWatchlist @Inject constructor(
    private val watchRepository: WatchRepository,
    private val activityRepository: ActivityRepository,
) {
    fun observeWatches(): Flow<List<WatchEntity>> = watchRepository.observeWatches()

    val reports: StateFlow<Map<Long, ActivityReport>> get() = activityRepository.reports
}
