// Gelöschtes Paar wiederherstellen (Rückgängig) — Teil von WatchlistEditController.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.AddMoments
import com.cryptochecker.app.data.AddWatchOutcome
import com.cryptochecker.app.data.RestoreOutcome
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.confirmUserWrite
import com.cryptochecker.app.data.restore
import com.cryptochecker.app.data.tryAddWatch
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.WatchSnapshot
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.notification.PriceNotificationRule
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.UndoSlot
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.widget.WidgetPrefs
import com.cryptochecker.app.widget.WidgetUpdater
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * «Rückgängig» für [watchId]; tut nichts, wenn inzwischen etwas anderes gelöscht wurde. Nur bis zur
 * Obergrenze der Merkliste: [onFull] bekommt die Zahl der deshalb nicht zurückgeholten Paare (nur wenn > 0).
 */
internal fun WatchlistEditController.undoDelete(watchId: Long, onFull: (Int) -> Unit = {}) {
    launchWrite {
        undoMutex.withLock {
            val snapshots = undo.take(watchId) ?: return@withLock
            // Nach «Merkliste leeren» bzw. vielen Paaren keine Kurs-Meldung je Paar: Android drosselt rund 5 Meldungen
            // pro Sekunde und verwirft den Rest still; wie unter iOS meldet dann erst die nächste Aktualisierung
            val showPrices = restoresPriceNotifications(snapshots.size)
            var any = false
            var full = 0
            for (snapshot in snapshots) {
                when (watchRepository.restore(snapshot)) {
                    RestoreOutcome.RESTORED -> Unit
                    RestoreOutcome.FULL -> {
                        full++
                        continue
                    }
                    RestoreOutcome.BLOCKED -> continue
                }
                any = true
                // Kurs-Meldung wie vorher wieder zeigen (letzter bekannter Kurs) — nur nach den Einstellungen
                if (showPrices) notifier.showPriceNow(snapshot.watch)
            }
            if (any) widgetUpdater.updateAll()
            if (full > 0) onFull(full)
        }
    }
}
