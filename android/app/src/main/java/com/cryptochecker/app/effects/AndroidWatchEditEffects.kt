package com.cryptochecker.app.effects

import com.cryptochecker.app.data.ActivityRepository
import com.cryptochecker.app.data.live.LivePriceStream
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.RefreshLockScope
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.ui.features.watchlist.WatchEditEffects
import com.cryptochecker.app.widget.WidgetUpdater
import javax.inject.Inject

/** [WatchEditEffects] über Kurs-Aktualisierung, Live-Kurse, Signale, Mitteilungen und Widgets der App. */
class AndroidWatchEditEffects @Inject constructor(
    private val priceRefresher: PriceRefresher,
    private val notifier: AppNotifier,
    private val widgetUpdater: WidgetUpdater,
    private val activityRepository: ActivityRepository,
    private val livePriceStream: LivePriceStream,
) : WatchEditEffects {
    /** Nur weiterreichen: die Rückfrage «nicht gespeichert» kommt im ViewModel vor dem Aufruf. */
    override suspend fun <T> withRefreshLock(block: suspend () -> T): T {
        val underLock: suspend RefreshLockScope.() -> T = { block() }
        return priceRefresher.withRefreshLock(underLock)
    }

    override fun forgetLivePrices(watchIds: Collection<Long>) {
        livePriceStream.forget(watchIds)
    }

    override fun forgetActivity(watchId: Long) {
        activityRepository.forget(watchId)
    }

    override fun cancelActivityNotification(watchId: Long) {
        notifier.cancelActivity(watchId)
    }

    override suspend fun cancelPriceNotification(watchId: Long) {
        notifier.cancelPrice(watchId)
    }

    override suspend fun updateWidgets() {
        widgetUpdater.updateAll()
    }

    override suspend fun refreshOne(watchId: Long) {
        priceRefresher.refreshOne(watchId)
    }
}
