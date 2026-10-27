// Hüllen um die App-Klassen für die Schnittstellen in `PriceRefresherPorts.kt` (:domain): nur weiterreichen, keine
// eigene Logik. `RefreshModule` gibt sie an [PriceRefresher].
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.fillChange
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.util.ConnectivityMonitor
import com.cryptochecker.app.widget.WidgetUpdater
import kotlinx.coroutines.Job

internal fun WatchRepository.asRefresherStore(): RefresherStore = object : RefresherStore {
    override suspend fun getWatches() = this@asRefresherStore.getWatches()
    override suspend fun getWatch(id: Long) = this@asRefresherStore.getWatch(id)
    override suspend fun getAllEnabledAlarms() = this@asRefresherStore.getAllEnabledAlarms()
    override suspend fun clearChanges(ids: List<Long>) = this@asRefresherStore.clearChanges(ids)
    override suspend fun fillChange(id: Long, time: Long, change: Double) = this@asRefresherStore.fillChange(id, time, change)
}

internal fun RefreshStats.asRefresherStats(): RefresherStats = object : RefresherStats {
    private val stats = this@asRefresherStats
    override val changeStamp get() = stats.changeStamp
    override val lastDurationMillis get() = stats.lastDurationMillis
    override val lastReport get() = stats.lastReport
    override fun backoffStates() = stats.backoffStates()
    override fun setBackoffStates(states: Map<String, ExchangeBackoff.State>) = stats.setBackoffStates(states)
    override fun setChangeStamp(stamp: ChangeStamp) = stats.setChangeStamp(stamp)
    override fun setLastFullCheck(at: Long) = stats.setLastFullCheck(at)
    override fun setLastFullRefresh(durationMillis: Long, at: Long) = stats.setLastFullRefresh(durationMillis, at)
    override fun setLastReport(report: RefreshReport) = stats.setLastReport(report)
}

internal fun SparklineRepository.asRefresherSparklines() = RefresherSparklines { awaitRestored() }

internal fun ConnectivityMonitor.asRefresherOnline() = RefresherOnline { isOnline() }

internal fun WidgetUpdater.asRefresherWatchWidgets() = RefresherWatchWidgets { id, fetch -> updateForWatch(id, fetch) }

internal fun DayReferences.asRefresherDayReferences(): RefresherDayReferences = object : RefresherDayReferences {
    private val references = this@asRefresherDayReferences
    override val candleWatchIds get() = references.candleWatchIds
    override fun watchesNeedingCandles(watches: List<WatchEntity>, fetched: Map<Long, Fetched>, basis: ChangeBasis, remember: Boolean) =
        references.watchesNeedingCandles(watches, fetched, basis, remember)
    override fun startDayReferenceLoads(watches: List<WatchEntity>, started: MutableSet<Pair<String, String>>, dayStart: Long?) =
        references.startDayReferenceLoads(watches, started, dayStart)
    override suspend fun awaitDayReferenceLoads(jobs: List<Job>, limitMillis: Long) = references.awaitDayReferenceLoads(jobs, limitMillis)
    override fun candleChange(watch: WatchEntity, price: Double, dayStart: Long?) = references.candleChange(watch, price, dayStart)
}

internal fun ChartRefresher.asRefresherCharts(): RefresherCharts = object : RefresherCharts {
    private val charts = this@asRefresherCharts
    override fun markIfStale() = charts.markIfStale()
    override fun takePending() = charts.takePending()
    override suspend fun refreshUnlocked() = charts.refreshUnlocked()
    override fun launch(timeoutMillis: Long) = charts.launch(timeoutMillis)
    override fun launchIfPending(timeoutMillis: Long) = charts.launchIfPending(timeoutMillis)
}

internal fun RefreshSideChecks.asRefresherSideChecks(): RefresherSideChecks = object : RefresherSideChecks {
    private val checks = this@asRefresherSideChecks
    override suspend fun awaitAll(timeoutMillis: Long, extra: SideCheckExtra?) = checks.awaitAll(timeoutMillis, extra)
    override fun launchDetached() = checks.launchDetached()
}
