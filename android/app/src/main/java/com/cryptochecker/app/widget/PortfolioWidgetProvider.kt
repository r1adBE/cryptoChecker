package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Portfolio-Widget: Gesamtwert in der Umrechnungswährung; je nach Grösse dazu «heute»,
 * Wertverlauf und die grössten Positionen (siehe [WidgetUpdater.updatePortfolio]).
 */
@AndroidEntryPoint
class PortfolioWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var snapshotUpdater: PortfolioSnapshotUpdater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                // Sofort mit der letzten Aufnahme zeichnen, dann frisch berechnen
                widgetUpdater.updatePortfolio(appWidgetIds)
                // goAsync erlaubt nur rund 10 s
                withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { snapshotUpdater.refresh() }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Grösse geändert: aus der letzten Aufnahme in der neuen Stufe zeichnen (ohne Netz). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                widgetUpdater.updatePortfolio(intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetPrefs.remove(it) }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MILLIS = 8_000L
    }
}
