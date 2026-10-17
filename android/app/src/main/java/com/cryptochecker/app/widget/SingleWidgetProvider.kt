package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.cryptochecker.app.util.ReceiverAsync
import com.cryptochecker.app.util.launchAsync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Einzel-Widget: ein Paar gross mit Kurs, Änderung und Mini-Chart. */
@AndroidEntryPoint
class SingleWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    @Inject lateinit var widgetPrefs: WidgetPrefs

    @Inject lateinit var scheduler: com.cryptochecker.app.work.PriceUpdateScheduler

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        launchAsync("Einzel-Widget aktualisieren") {
            drawCachedThenFetch(widgetUpdater, appWidgetIds)
        }
    }

    /** Grösse geändert: Chart in der neuen Grösse zeichnen (Kerzen kommen aus dem Zwischenspeicher). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        launchAsync("Einzel-Widget neu zeichnen") {
            drawCachedThenFetch(widgetUpdater, intArrayOf(appWidgetId))
        }
    }

    /** Erstes Widget dieser Art platziert bzw. letztes entfernt: Bedarf der Hintergrund-Aktualisierung neu prüfen. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduler.onWidgetsChanged()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        scheduler.onWidgetsChanged()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetPrefs.remove(it) }
        widgetUpdater.forgetWidgets(appWidgetIds)
    }
}

/**
 * Einzel-Widgets aus einem Empfänger (goAsync): zuerst alle sofort aus dem Zwischenspeicher zeichnen
 * (nach dem Neustart ohne Chart), dann Kerzen frisch laden — zusammen höchstens
 * [ReceiverAsync.FETCH_MILLIS]. Mehrere Einzel-Widgets nach dem Neustart führen so nicht zum
 * Hintergrund-ANR; was nicht fertig wird, holt die nächste Aktualisierung nach.
 */
internal suspend fun drawCachedThenFetch(widgetUpdater: WidgetUpdater, appWidgetIds: IntArray) {
    widgetUpdater.updateSingle(appWidgetIds, fetch = false)
    withTimeoutOrNull(ReceiverAsync.FETCH_MILLIS) { widgetUpdater.updateSingle(appWidgetIds) }
}
