package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cryptochecker.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
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

    private var data: ListWidgetRowData? = null

    override fun onCreate() = Unit

    /**
     * Wird vom System auf einem Hintergrund-Thread aufgerufen und muss
     * synchron fertig werden — deshalb runBlocking.
     */
    override fun onDataSetChanged() {
        data = runBlocking { rows.load(appWidgetId) }
    }

    override fun onDestroy() {
        data = null
    }

    override fun getCount(): Int = data?.watches?.size ?: 0

    override fun getViewAt(position: Int): RemoteViews =
        data?.let { rows.row(it, position) } ?: RemoteViews(context.packageName, R.layout.widget_list_item)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = data?.watches?.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
