package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Liefert die Zeilen der Widget-Liste unter Android 12 und bei sehr langen Listen
 * (ab Android 12 legt [ListWidgetRenderer] die Zeilen sonst direkt ins Widget).
 */
@AndroidEntryPoint
class PriceWidgetService : RemoteViewsService() {

    @Inject lateinit var rows: ListWidgetRows

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return WatchlistViewsFactory(applicationContext, appWidgetId, rows)
    }
}

private class WatchlistViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val rows: ListWidgetRows,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile private var data: ListWidgetRowData? = null

    /** Stand ([ListWidgetDataVersion]) der geladenen Zeilen; null = noch nie geladen. */
    @Volatile private var loadedVersion: Long? = null

    override fun onCreate() = Unit

    /**
     * Wird vom System auf einem Binder-Thread (nicht dem Hauptthread) aufgerufen und muss synchron
     * fertig werden — deshalb runBlocking. Gelesen wird nur der lokale Stand (Einstellungen, Room,
     * Logo-Dateien; nie das Netz), höchstens [RELOAD_BUDGET_MILLIS].
     */
    override fun onDataSetChanged() {
        reload()
    }

    override fun onDestroy() {
        data = null
        loadedVersion = null
    }

    /**
     * Anzahl — der Launcher fragt sie bei jedem (Neu-)Binden zuerst ab. Gehören die Zeilen nicht zum
     * letzten Zeichnen (Fabrik schon erstellt, Meldung verpasst), hier neu laden: Zeilen und Uhrzeit
     * im Kopf stammen so immer aus demselben Stand.
     */
    override fun getCount(): Int {
        if (rows.versions.isStale(appWidgetId, loadedVersion)) reload()
        return data?.watches?.size ?: 0
    }

    @Synchronized
    private fun reload() {
        // Stand vor dem Lesen merken: zeichnet es währenddessen neu, gilt die Fabrik weiter als veraltet
        val version = rows.versions.current(appWidgetId)
        // Fehler hier beendeten den Prozess (RemoteViewsService reicht sie an den Absturz-Behandler weiter), z. B.
        // Datenbank-Schlüssel gerade nicht lesbar: bisherige Zeilen behalten, das nächste Zeichnen lädt erneut
        val loaded = try {
            runBlocking { withTimeoutOrNull(RELOAD_BUDGET_MILLIS) { rows.load(appWidgetId) } }
        } catch (e: Exception) {
            if (e.isDatabaseKeyUnavailable()) Timber.d(e, "Widget-Zeilen: Datenbank-Schlüssel nicht verfügbar")
            else Timber.w(e, "Widget-Zeilen nicht geladen (%d)", appWidgetId)
            loadedVersion = version
            return
        }
        // Zeitgrenze (Speicher hängt): bisherige Zeilen behalten. Stand trotzdem als geladen merken,
        // sonst wartet getCount() gleich noch einmal; das nächste Zeichnen lädt ohnehin neu.
        if (loaded != null) data = loaded else Timber.w("Widget-Zeilen nicht rechtzeitig geladen (%d)", appWidgetId)
        loadedVersion = version
    }

    override fun getViewAt(position: Int): RemoteViews =
        data?.let { rows.row(it, position) } ?: RemoteViews(context.packageName, R.layout.widget_list_item)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = data?.watches?.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private companion object {
        /**
         * Höchstens so lange blockiert das Laden den Binder-Thread. Über der Logo-Grenze
         * (WidgetCoinLogos, 6 s), damit langsame Logo-Dateien nur Initialen statt alter Zeilen ergeben.
         */
        const val RELOAD_BUDGET_MILLIS = 8_000L
    }
}
