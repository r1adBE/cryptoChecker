package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.RetryWait
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.util.PriceFormat
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
 * «Neuer Versuch um 14:32» im Kopf der Listen-Widgets, solange die Aktualisierung aus dem Widget nach «erneut
 * versuchen» wartet (Datenbank-Schlüssel noch nicht lesbar, [RetryWait]). Statt «Uhrzeit · Dauer» steht die feste
 * Uhrzeit des nächsten Versuchs (12/24 h wie das Gerät) — kein Herunterzählen, das ohne Neuzeichnen falsch würde.
 * Gezeichnet wird nur der Kopf (partiallyUpdateAppWidget, ohne Datenbank): beim Warten einmal, am Ende bzw. beim
 * Start des nächsten Versuchs einmal zurück; bleibt das aus, steht die Uhrzeit bis zum nächsten Zeichnen.
 */
@Singleton
class ListWidgetRetry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val toolkit: WidgetToolkit,
) {
    private val scope = appScope(Dispatchers.Default, "ListWidgetRetry")

    @Volatile private var endJob: Job? = null

    /** Wartet bis [retryAt] (Wanduhr, ms): Uhrzeit des Versuchs zeigen, am Ende wieder «Uhrzeit · Dauer». */
    fun show(retryAt: Long) {
        widgetPrefs.refreshRetryAt = retryAt
        drawHeader(retryAt)
        endJob?.cancel()
        endJob = scope.launch {
            // Startet der Versuch später (z. B. ohne Netz), verschwindet die Uhrzeit trotzdem nach ihrem Ablauf
            delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + WidgetRefreshState.UNLOCK_SLACK_MILLIS)
            clear()
        }
    }

    /** Versuch startet bzw. Warten vorbei: wieder «Uhrzeit · Dauer» (nur, wenn eine Uhrzeit des Versuchs stand). */
    fun clear() {
        if (widgetPrefs.refreshRetryAt == 0L) return
        widgetPrefs.refreshRetryAt = 0L
        drawHeader(0L)
    }

    /**
     * App-Start: eine abgelaufene Uhrzeit des Versuchs löschen und den Kopf neu zeichnen
     * ([RetryWait.widgetRetryExpired]); eine noch kommende bleibt (der Auftrag wartet noch).
     */
    fun clearIfExpired(now: Long) {
        if (RetryWait.widgetRetryExpired(widgetPrefs.refreshRetryAt, now)) clear()
    }

    private fun drawHeader(retryAt: Long) {
        val ids = toolkit.ids(PriceWidgetProvider::class.java)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        applyTo(context, views, retryAt, System.currentTimeMillis())
        runCatching { manager.partiallyUpdateAppWidget(ids, views) }
            .onFailure { Timber.w(it, "Uhrzeit des neuen Versuchs nicht gezeichnet") }
    }

    companion object {
        /**
         * Kopf für [retryAt] (0 = keiner): liegt der Versuch nach [now], «Neuer Versuch um …» statt «Uhrzeit · Dauer»,
         * sonst umgekehrt ([RetryWait.widgetRetryTime]). Auch beim vollen Zeichnen ([ListWidgetRenderer]); die
         * Textfarbe setzt dort der Aufrufer.
         */
        fun applyTo(context: Context, views: RemoteViews, retryAt: Long, now: Long) {
            val at = RetryWait.widgetRetryTime(retryAt, now)
            views.setViewVisibility(R.id.widget_duration, if (at != null) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_retry_at, if (at != null) View.VISIBLE else View.GONE)
            if (at != null) {
                val text = context.getString(R.string.widget_retry_at, PriceFormat.shortTime(at))
                views.setTextViewText(R.id.widget_retry_at, text)
            }
        }
    }
}
