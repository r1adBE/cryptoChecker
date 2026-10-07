package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Liefert die Zeilen der Widget-Liste. */
@AndroidEntryPoint
class PriceWidgetService : RemoteViewsService() {

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var widgetPrefs: WidgetPrefs

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return WatchlistViewsFactory(applicationContext, appWidgetId, watchRepository, settingsRepository, widgetPrefs)
    }
}

private class WatchlistViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val widgetPrefs: WidgetPrefs,
) : RemoteViewsService.RemoteViewsFactory {

    private var watches: List<WatchEntity> = emptyList()
    private var background: WidgetColors = WidgetColors.of(AccentColor.DEFAULT, dark = true)
    /** Ab diesem Alter «veraltet» ([WidgetOutdated]), aus den Einstellungen beim Laden. */
    private var outdatedAfter: Long = Long.MAX_VALUE

    override fun onCreate() = Unit

    /**
     * Wird vom System auf einem Hintergrund-Thread aufgerufen und muss
     * synchron fertig werden — deshalb runBlocking.
     */
    override fun onDataSetChanged() {
        runBlocking {
            val settings = settingsRepository.current()
            background = WidgetColors.of(
                settings.accentColor,
                widgetPrefs.isDark(appWidgetId),
                settings.priceColorScheme,
                HighContrast.isEffective(context, settings.highContrast),
                settings.priceColorsInverted,
            )
            outdatedAfter = WidgetOutdated.afterMillis(settings)
            // Gruppe des Widgets; gibt es sie nicht mehr, wieder alle Paare
            val all = watchRepository.getWatches()
            val group = widgetPrefs.getGroup(appWidgetId)
            watches = if (group == null) all
            else all.filter { it.groupName == group }.ifEmpty { all }
        }
    }

    override fun onDestroy() {
        watches = emptyList()
    }

    override fun getCount(): Int = watches.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        val watch = watches.getOrNull(position) ?: return views

        views.setTextViewText(R.id.item_pair, watch.displayName)
        views.setTextColor(R.id.item_pair, background.textColor)

        // Eigener Stand dieses Paares veraltet (z. B. Börse antwortet nicht): «Binance · veraltet»
        val outdated = WidgetOutdated.isOutdated(watch.lastUpdate, watch.lastError, System.currentTimeMillis(), outdatedAfter)
        views.setTextViewText(
            R.id.item_market,
            if (outdated) context.getString(R.string.watchlist_row_outdated, watch.marketName) else watch.marketName
        )
        views.setTextColor(R.id.item_market, background.secondaryTextColor)

        views.setTextViewText(
            R.id.item_price,
            PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
        )
        views.setTextColor(R.id.item_price, background.textColor)

        // Veränderung über 24 Stunden — derselbe Wert wie die Pille in der Merkliste
        // (nicht mehr gehandelt: «—»)
        val change = watch.shownChange24h?.takeIf { it.isFinite() }
        val changeText = PriceFormat.changePercent(change)

        // Immer ein Wert, damit alle Zeilen gleich aussehen: Pfeil und Vorzeichen
        // zeigen die Richtung (Pfeil folgt dem Vorzeichen, nie dem Farbtausch), die Farbe
        // zusätzlich; ohne Bewegung grau «0.00%» ohne Pfeil, ohne 24-h-Bezug grau «—».
        if (change == null) {
            views.setTextViewText(R.id.item_change, NO_CHANGE)
            views.setTextColor(R.id.item_change, background.neutralColor)
        } else if (changeText == null) {
            views.setTextViewText(R.id.item_change, ZERO_CHANGE)
            views.setTextColor(R.id.item_change, background.neutralColor)
        } else {
            views.setTextViewText(R.id.item_change, "${PriceFormat.changeArrow(change)} $changeText")
            views.setTextColor(R.id.item_change, if (change >= 0) background.upColor else background.downColor)
        }

        // Screenreader: die Zeile als ein Satz, Änderung mit Richtungswort statt Vorzeichen
        views.setContentDescription(
            R.id.item_root,
            A11yText.row(
                context = context,
                pair = watch.displayName,
                market = watch.marketName,
                price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                change24h = change,
                extras = listOf(if (outdated) WidgetOutdated.spoken(context, watch.lastUpdate) else null),
            )
        )

        // Trennlinie unter jeder Zeile ausser der letzten (Akzentfarbe, geringe Deckkraft)
        views.setInt(R.id.item_divider, "setBackgroundColor", background.dividerColor)
        views.setViewVisibility(
            R.id.item_divider,
            if (position == watches.size - 1) View.GONE else View.VISIBLE
        )

        // Tippen auf eine Zeile öffnet die App bei diesem Paar.
        views.setOnClickFillInIntent(
            R.id.item_root,
            Intent().putExtra(MainActivity.EXTRA_WATCH_ID, watch.id)
        )

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = watches.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}

/** Keine Bewegung über 24 Stunden: grau, im selben Format wie die übrigen Werte. */
private val ZERO_CHANGE: String get() = "%.2f%%".format(0.0)

/** Ohne 24-h-Bezug: Strich statt Zahl (nie die Veränderung seit der letzten Abfrage). */
private const val NO_CHANGE = "—"
