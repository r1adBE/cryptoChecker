package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Rückmeldung des Startbildschirms nach [WidgetPinner.request]: Das Widget liegt jetzt dort.
 * Einzel-Widget mit Paar (aus dem Aktionen-Blatt der Merkliste): Paar speichern und zeichnen.
 * Einzel-Widget ohne Paar (aus den Einstellungen): Einrichten öffnen — das geht nur, solange
 * die App sichtbar ist; sonst öffnet Tippen auf das Widget das Einrichten.
 */
@AndroidEntryPoint
class WidgetPinReceiver : BroadcastReceiver() {

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var widgetUpdater: WidgetUpdater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PINNED) return
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (intent.getStringExtra(EXTRA_KIND) != WidgetKind.SINGLE.name) return

        val watchId = intent.getLongExtra(EXTRA_WATCH_ID, NO_WATCH).takeIf { it != NO_WATCH }
        if (watchId == null) {
            // Einrichten öffnen (Paar, Zeitraum, Hintergrund) wie beim Hinzufügen im Startbildschirm
            val configure = Intent(context, SingleWidgetConfigureActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            runCatching { context.startActivity(configure) }
                .onFailure { Timber.w(it, "Einrichten für Widget %d nicht geöffnet", appWidgetId) }
            return
        }

        widgetPrefs.setWatchId(appWidgetId, watchId)
        val pendingResult = goAsync()
        scope.launch {
            try {
                widgetUpdater.updateSingle(intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PINNED = "com.cryptochecker.app.action.WIDGET_PINNED"
        const val EXTRA_KIND = "com.cryptochecker.app.extra.WIDGET_KIND"
        const val EXTRA_WATCH_ID = "com.cryptochecker.app.extra.WIDGET_WATCH_ID"
        private const val NO_WATCH = -1L
    }
}
