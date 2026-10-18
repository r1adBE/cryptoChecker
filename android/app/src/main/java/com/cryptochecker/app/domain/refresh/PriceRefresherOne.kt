// Ein einzelnes Paar aktualisieren (Teil von `PriceRefresher`, [PriceRefresher.refreshOne]): unter der Sperre abfragen,
// auswerten und nur die Widgets dieses Paars zeichnen. Erweiterungsfunktion; Abhängigkeiten bleiben in der Klasse.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun PriceRefresher.refreshOneLocked(watchId: Long): RefreshSummary = refreshLock.withLock { lock ->
    withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        val watch = watchRepository.getWatch(watchId) ?: return@withContext RefreshSummary()
        // Deaktiviert, kein Netz oder Börse pausiert: nichts anfragen, Kurs und Zustand bleiben
        if (watch.disabled || !connectivity.isOnline() ||
            ExchangeBackoff.isPaused(refreshStats.backoffStates()[watch.marketKey], startedAt) ||
            BlockedSources.groupPausedUntil(watch.marketKey) != null
        ) {
            return@withContext RefreshSummary()
        }
        val settings = settingsRepository.current()
        sparklineRepository.awaitRestored()
        val single = priceFetcher.fetchSingle(watch)
        // Kerzen nur, wenn der Ticker keinen 24-h-Wert liefert (Tages-Basen: immer)
        val dayStart = ChangeBasisMath.dayStart(settings.changeBasis, startedAt)
        dayReferences.awaitDayReferenceLoads(
            dayReferences.startDayReferenceLoads(
                dayReferences.watchesNeedingCandles(listOf(watch), mapOf(watch.id to single), settings.changeBasis, remember = false),
                HashSet(),
                dayStart,
            )
        )
        val processed = resultProcessor.process(lock, listOf(watch), mapOf(watch.id to single), settings, dayStart)

        // Ein einzelnes Paar sagt nichts über die Dauer eines vollen Durchlaufs,
        // deshalb bleibt die angezeigte Zeit im Widget hier unverändert.
        // Nur die Widgets, die dieses Paar zeigen (Liste mit dem Paar, Einzel-Widget).
        // Unter der Sperre ohne Kerzen-Abruf, siehe refreshOne
        widgetUpdater.updateForWatch(watch.id, fetch = false)
        chartRefresher.markIfStale()

        RefreshSummary(
            checked = processed.checked,
            failed = processed.failed,
            alarmsTriggered = processed.alarms,
            durationMillis = System.currentTimeMillis() - startedAt
        )
    }
}
