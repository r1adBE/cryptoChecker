package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.RetryWait
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.util.appScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Neuer Versuch in 0:30» im Kopf der Listen-Widgets, solange die Aktualisierung aus dem Widget nach «erneut
 * versuchen» wartet (Datenbank-Schlüssel noch nicht lesbar, [RetryWait]). Statt «Uhrzeit · Dauer» zählt ein
 * Chronometer herunter — das macht der Launcher selbst. Gezeichnet wird nur der Kopf (partiallyUpdateAppWidget,
 * ohne Datenbank): beim Warten einmal, am Ende bzw. beim Start des nächsten Versuchs einmal zurück.
 */
@Singleton
class ListWidgetRetry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val toolkit: WidgetToolkit,
) {
    private val scope = appScope(Dispatchers.Default, "ListWidgetRetry")

    @Volatile private var endJob: Job? = null

    /** Wartet bis [retryAt] (Wanduhr, ms): Countdown zeigen, am Ende wieder «Uhrzeit · Dauer». */
    fun show(retryAt: Long) {
        widgetPrefs.refreshRetryAt = retryAt
        drawHeader(retryAt)
        endJob?.cancel()
        endJob = scope.launch {
            // Startet der Versuch später (z. B. ohne Netz), verschwindet der Countdown trotzdem bei 0
            delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + WidgetRefreshState.UNLOCK_SLACK_MILLIS)
            clear()
        }
    }

    /** Versuch startet bzw. Warten vorbei: wieder «Uhrzeit · Dauer» (nur, wenn ein Countdown stand). */
    fun clear() {
        if (widgetPrefs.refreshRetryAt == 0L) return
        widgetPrefs.refreshRetryAt = 0L
        drawHeader(0L)
    }

    private fun drawHeader(retryAt: Long) {
        val ids = toolkit.ids(PriceWidgetProvider::class.java)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        applyTo(context, views, retryAt, System.currentTimeMillis())
        runCatching { manager.partiallyUpdateAppWidget(ids, views) }
            .onFailure { Timber.w(it, "Countdown bis zum neuen Versuch nicht gezeichnet") }
    }

    companion object {
        /**
         * Kopf für [retryAt] (0 = keiner): wartend der Chronometer statt «Uhrzeit · Dauer», sonst umgekehrt.
         * Auch beim vollen Zeichnen ([ListWidgetRenderer]); die Textfarbe setzt dort der Aufrufer.
         */
        fun applyTo(context: Context, views: RemoteViews, retryAt: Long, now: Long) {
            val rest = if (retryAt > now) retryAt - now else 0L
            val waiting = rest > 0
            views.setViewVisibility(R.id.widget_duration, if (waiting) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_retry_countdown, if (waiting) View.VISIBLE else View.GONE)
            if (!waiting) {
                // Angehalten: zählt verborgen nicht weiter
                views.setChronometer(R.id.widget_retry_countdown, SystemClock.elapsedRealtime(), null, false)
                return
            }
            views.setChronometer(
                R.id.widget_retry_countdown,
                WidgetRefreshState.chronometerBase(SystemClock.elapsedRealtime(), rest),
                context.getString(R.string.widget_retry_countdown),
                true,
            )
            views.setChronometerCountDown(R.id.widget_retry_countdown, true)
        }
    }
}
