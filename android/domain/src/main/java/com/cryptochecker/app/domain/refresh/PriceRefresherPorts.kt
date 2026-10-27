// Schmale Schnittstellen von [PriceRefresher] (wie `RefreshPorts.kt` für die Auswertung): genau die Aufrufe, die die
// Aktualisierung an Merkliste, Statistik, Kerzen, Widgets, Netz und die Prüfungen danach richtet. Die Hüllen um die
// App-Klassen stehen in der App (`PriceRefresherAdapters.kt`), Hilt baut [PriceRefresher] in `RefreshModule`.
package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** Merkliste lesen und 24-h-Werte nachtragen bzw. löschen (`WatchRepository`). */
interface RefresherStore {
    suspend fun getWatches(): List<WatchEntity>
    suspend fun getWatch(id: Long): WatchEntity?
    suspend fun getAllEnabledAlarms(): List<AlarmEntity>
    suspend fun clearChanges(ids: List<Long>)
    suspend fun fillChange(id: Long, time: Long, change: Double): Boolean
}

/** Zeiten, Bericht, Bremsen und Stempel der Aktualisierung (`RefreshStats`). */
interface RefresherStats {
    val changeStamp: StateFlow<ChangeStamp?>
    val lastDurationMillis: StateFlow<Long>
    val lastReport: StateFlow<RefreshReport?>
    fun backoffStates(): Map<String, ExchangeBackoff.State>
    fun setBackoffStates(states: Map<String, ExchangeBackoff.State>)
    fun setChangeStamp(stamp: ChangeStamp)
    fun setLastFullCheck(at: Long = System.currentTimeMillis())
    fun setLastFullRefresh(durationMillis: Long, at: Long = System.currentTimeMillis())
    fun setLastReport(report: RefreshReport)
}

/** Gespeicherte Kerzen-Reihen sind geladen (`SparklineRepository.awaitRestored`). */
fun interface RefresherSparklines {
    suspend fun awaitRestored()
}

/** Netz vorhanden (`ConnectivityMonitor.isOnline`). */
fun interface RefresherOnline {
    fun isOnline(): Boolean
}

/** Widgets eines Paars neu zeichnen (`WidgetUpdater.updateForWatch`). */
fun interface RefresherWatchWidgets {
    suspend fun updateForWatch(watchId: Long, fetch: Boolean)
}

/** 24-h- und Tages-Bezüge aus Kerzen (`DayReferences`). */
interface RefresherDayReferences {
    val candleWatchIds: Set<Long>
    fun watchesNeedingCandles(
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        basis: ChangeBasis,
        remember: Boolean,
    ): List<WatchEntity>
    fun startDayReferenceLoads(watches: List<WatchEntity>, started: MutableSet<Pair<String, String>>, dayStart: Long?): List<Job>
    suspend fun awaitDayReferenceLoads(jobs: List<Job>, limitMillis: Long = Long.MAX_VALUE)
    fun candleChange(watch: WatchEntity, price: Double, dayStart: Long?): Double?
}

/** Widget-Charts ausserhalb der Sperre (`ChartRefresher`). */
interface RefresherCharts {
    fun markIfStale()
    fun takePending(): Boolean
    suspend fun refreshUnlocked()
    fun launch(timeoutMillis: Long)
    fun launchIfPending(timeoutMillis: Long = CheckBudget.CHART_TIMEOUT_MILLIS)
}

/** Aktivität, Gas, Depeg und Portfolio-Momentaufnahme nach dem Durchlauf (`RefreshSideChecks`). */
interface RefresherSideChecks {
    suspend fun awaitAll(timeoutMillis: Long, extra: SideCheckExtra? = null)
    fun launchDetached()
}

/** Eine zusätzliche Prüfung für [RefresherSideChecks.awaitAll] (z. B. Widget-Charts) mit eigener Zeitgrenze. */
class SideCheckExtra(val name: String, val timeoutMillis: Long, val check: suspend () -> Unit)
