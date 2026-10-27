package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Charts der Einzel-Widgets nachladen. Unter der Refresh-Sperre ([RefreshLock]) zeichnen Aktualisierung und
 * Live-Speichern die Widgets nur aus dem Zwischenspeicher ([com.cryptochecker.app.widget.LiveWidgetGate.drawAll]
 * mit `fetch = false`); fehlten Kerzen, merkt [markIfStale] das vor, und [takePending] / [launch] laden sie danach
 * ausserhalb der Sperre. Eigene Sperre ([chartMutex]): höchstens ein Nachladen gleichzeitig — sie wird nie unter
 * der Refresh-Sperre genommen, also keine Reihenfolge zwischen beiden. Teil von [PriceRefresher].
 */
/** Die Widget-Seite von [ChartRefresher] (`WidgetUpdater`): veraltete Charts merken und Einzel-Widget-Charts nachladen. */
interface ChartWidgets {
    fun takeStaleCharts(): Boolean
    suspend fun refreshSingleCharts()
}

class ChartRefresher(
    private val widgetUpdater: ChartWidgets,
) {
    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val scope = appScope(Dispatchers.IO, "ChartRefresher")

    /** Unter der Sperre gezeichnete Widgets vermissten Kerzen: Charts ausserhalb der Sperre nachladen. */
    private val pending = AtomicBoolean(false)

    /** Höchstens ein Nachladen der Charts gleichzeitig. */
    private val chartMutex = Mutex()

    /** Nach einem Zeichnen ohne Netz: fehlten Kerzen ([WidgetUpdater.takeStaleCharts]), Nachladen vormerken. */
    fun markIfStale() {
        if (widgetUpdater.takeStaleCharts()) pending.set(true)
    }

    /** Vorgemerktes Nachladen abholen (und zurücksetzen): true = es fehlten Kerzen. */
    fun takePending(): Boolean = pending.getAndSet(false)

    /**
     * Charts der Einzel-Widgets mit Netz nachladen — nie unter der Sperre der Aktualisierung (Kerzen
     * können je Widget bis zu 20 s dauern; Frist des Weckers, Vordergrund und Live-Speichern warteten
     * sonst). Läuft schon ein Nachladen, wird nur vorgemerkt: der nächste Durchlauf holt es nach.
     */
    suspend fun refreshUnlocked() {
        if (!chartMutex.tryLock()) {
            pending.set(true)
            return
        }
        try {
            widgetUpdater.refreshSingleCharts()
        } finally {
            chartMutex.unlock()
        }
    }

    /** [refreshUnlocked] im eigenen Hintergrund-Job, höchstens [timeoutMillis]; Fehler bleiben still. */
    fun launch(timeoutMillis: Long) {
        scope.launch {
            try {
                withTimeoutOrNull(timeoutMillis) { refreshUnlocked() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Widget-Charts nicht nachgeladen")
            }
        }
    }

    /** Vorgemerktes Nachladen (auch eines früheren Laufs) jetzt im Hintergrund starten. */
    fun launchIfPending(timeoutMillis: Long = CheckBudget.CHART_TIMEOUT_MILLIS) {
        if (takePending()) launch(timeoutMillis)
    }
}
