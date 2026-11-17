package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.domain.BoundedCache
import com.cryptochecker.app.domain.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.util.DefaultDispatcher
import com.cryptochecker.app.domain.watch.RetiredWatch
import com.cryptochecker.app.domain.widget.LatestPerWidget
import com.cryptochecker.app.domain.widget.WidgetCandle
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.util.AppIntents
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import timber.log.Timber

/**
 * Einzel-Widget: ein Paar mit Kurs, Veränderung und Chart (Kerzen oder Linie; 24 h / 7 / 30 Tage).
 * Die Stundenkerzen dienen auch dem Wertverlauf des Portfolio-Widgets ([cachedHourlyPrices]).
 */
@Singleton
class SingleWidgetRenderer @Inject constructor(
    @param:ApplicationContext val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: com.cryptochecker.app.data.WatchRepository,
    val candleDataSource: CandleDataSource,
    val ownCandles: com.cryptochecker.app.data.remote.candles.OwnCandleDataSource,
    val candlePairs: com.cryptochecker.app.data.remote.candles.CandlePairs,
    private val toolkit: WidgetToolkit,
    private val coinLogos: WidgetCoinLogos,
    val tokenIdentityStore: com.cryptochecker.app.data.TokenIdentityStore,
    private val connectivity: WidgetConnectivity,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * Mini-Chart-Kerzen je Symbol und Zeitraum, 10 Minuten zwischengespeichert (für Kerzen und Linie).
     * Begrenzt (sonst blieb jedes je geprüfte Paar bis zum Prozessende).
     */
    val sparkCache = BoundedCache<String, Pair<Long, List<WidgetCandle>>>(MEMORY_CACHE_ENTRIES)

    /**
     * Gesetzt, wenn ein Zeichnen ohne Netz (`fetch = false`) Kerzen brauchte, die im Zwischenspeicher
     * fehlten oder älter als 10 Minuten waren: Dann lohnt das Nachladen ([takeStaleCharts]).
     */
    val staleCharts = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fehlten seit dem letzten Aufruf Kerzen beim Zeichnen ohne Netz? Setzt den Merker zurück. */
    fun takeStaleCharts(): Boolean = staleCharts.getAndSet(false)

    /** Börse der Kerzen aus der Ausweich-Kette je Schlüssel von [sparkCache] (für «Chart: Binance»). */
    val sparkProvider = BoundedCache<String, String>(MEMORY_CACHE_ENTRIES)

    /**
     * Zuletzt gezeichneter Chart je Einzel-Widget: Gleiche Kerzen, Darstellung, Farben, Grösse
     * und gleicher angezeigter Kurs ergeben dasselbe Bild — dann nicht neu zeichnen.
     * Nur das jeweils letzte Bild je Widget, beim Löschen entfernt ([forget]).
     */
    val chartBitmaps = LatestPerWidget<ChartBitmapKey, android.graphics.Bitmap>()

    /**
     * Zeichnet Einzel-Widgets neu: Paar, Kurs, Änderung, Chart (Kerzen/Linie; 24 h / 7 / 30 Tage).
     * [fetch] false: Chart nur aus dem Zwischenspeicher (auch älter), kein Netz — für Widget-Empfänger
     * (goAsync, mehrere Einzel-Widgets nach dem Neustart), siehe [SingleWidgetProvider].
     */
    suspend fun update(appWidgetIds: IntArray, fetch: Boolean = true): Boolean {
        if (appWidgetIds.isEmpty()) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val now = System.currentTimeMillis()
        // Börse stillgelegt (RetiredWatch: Marker oder Ende erreicht): ohne Kurs und Veränderung, mit dem Marker als Grund
        val watches = watchRepository.getWatches().associate { it.id to RetiredWatch.shown(it) }
        val outdatedAfter = WidgetOutdated.afterMillis(settings)
        val changeView = toolkit.changeView(settings.changeBasis, now)
        // Ohne Internet: «Keine Verbindung · Stand 14:05» (letzter Abruf) vor «veraltet» ([WidgetStatusRule])
        val online = connectivity.online()

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId, settings)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            // Gemerkte Id zeigt inzwischen ein anderes Paar (eingespielte Sicherung): «Paar wählen» statt ein fremdes Paar
            val watch = widgetPrefs.chosenWatch(appWidgetId, watches)
            val views = RemoteViews(context.packageName, R.layout.widget_single)

            toolkit.background(views, R.id.single_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.single_logo, accent.logoRes(dark))
            // Coin-Logo statt App-Logo, sofern eingeschaltet und ein Paar gewählt ist
            if (settings.widgetCoinLogos && watch != null) {
                views.setImageViewBitmap(
                    R.id.single_coin_logo,
                    coinLogos.bitmap(coinLogos.logoKey(watch), colors, logo = CoinLogos.allowedFor(watch.exchangeKey))
                )
                views.setViewVisibility(R.id.single_coin_logo, View.VISIBLE)
                views.setViewVisibility(R.id.single_logo, View.GONE)
            } else {
                views.setViewVisibility(R.id.single_coin_logo, View.GONE)
                views.setViewVisibility(R.id.single_logo, View.VISIBLE)
            }
            // Noch ohne Paar (z. B. aus der App hinzugefügt, Einrichten nicht geöffnet): Tippen öffnet das Einrichten;
            // sonst das Paar in der Merkliste mit seinem Aktionsblatt (wie eine Zeile des Listen-Widgets, iOS `widgetURL`)
            views.setOnClickPendingIntent(
                R.id.single_root,
                if (watch ==
                    null
                ) {
                    configureSingle(appWidgetId)
                } else {
                    openPair(appWidgetId, watch.id)
                }
            )

            if (watch == null) {
                views.setTextViewText(R.id.single_pair, context.getString(R.string.single_widget_choose))
                views.setTextColor(R.id.single_pair, colors.textColor)
                views.setAmountText(R.id.single_price, "—")
                views.setTextColor(R.id.single_price, colors.textColor)
            } else {
                drawSingleWatch(
                    views = views,
                    watch = watch,
                    colors = colors,
                    manager = manager,
                    appWidgetId = appWidgetId,
                    now = now,
                    outdatedAfter = outdatedAfter,
                    changeView = changeView,
                    online = online,
                    fetch = fetch,
                    highContrast = highContrast,
                    toolkit = toolkit,
                    widgetPrefs = widgetPrefs,
                    defaultDispatcher = defaultDispatcher,
                )
            }

            runCatchingCancellable { WidgetPublish.update(manager, appWidgetId, views) }
                .onFailure { Timber.w(it, "Einzel-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /** Öffnet die App beim Paar [watchId] ([AppIntents.EXTRA_WATCH_ID]); eigene data-Uri je Widget. */
    private fun openPair(appWidgetId: Int, watchId: Long): PendingIntent {
        val intent = AppIntents.main(context).apply {
            action = ACTION_OPEN_SINGLE_WIDGET
            data = "cryptochecker://widget/single/open/$appWidgetId".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppIntents.EXTRA_WATCH_ID, watchId)
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Einrichten eines Einzel-Widgets öffnen (Tippen auf ein Widget ohne Paar). */
    private fun configureSingle(appWidgetId: Int): PendingIntent {
        val intent = Intent(context, SingleWidgetConfigureActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            // Eigene data-Uri je Widget: sonst teilten sich alle Widgets einen PendingIntent
            data = "cryptochecker://widget/single/$appWidgetId".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Schon geladene Stundenkerzen (24 h, gegen USDT) eines Einzel-Widgets als Kurse mit Zeit —
     * ohne Netz, für den Wertverlauf des Portfolio-Widgets ([PortfolioSnapshotUpdater]).
     * Höchstens [PortfolioWidgetSeries.KEEP_MILLIS] alt; sonst null.
     */
    fun cachedHourlyPrices(base: String): List<TimedPrice>? {
        val (time, candles) = sparkCache[base.trim().uppercase() + USDT + "|" + WidgetChartRange.DAY.name] ?: return null
        if (System.currentTimeMillis() - time !in 0..PortfolioWidgetSeries.KEEP_MILLIS) return null
        return PortfolioWidgetSeries.fromCandles(candles.map { it.openTime to it.close }, time)
    }

    /**
     * Stundenkerzen (24 h, gegen USDT) für [base] laden — für Portfolio-Coins ohne Mini-Chart
     * in der Merkliste ([PortfolioSnapshotUpdater]); gleicher Zwischenspeicher wie die
     * Einzel-Widgets (10 Minuten). Danach wie [cachedHourlyPrices]; null ohne Quelle.
     */
    suspend fun loadHourlyPrices(base: String): List<TimedPrice>? {
        sparkline(base, USDT, WidgetChartRange.DAY)
        return cachedHourlyPrices(base)
    }

    /** Gelöschte Einzel-Widgets: zwischengespeicherten Chart freigeben. */
    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { chartBitmaps.remove(it) }
    }

    internal companion object {
        /** Zeitraum hinter der Veränderung: 80 % der Schrift (wie iOS `WidgetChangeLabel.suffix`). */
        const val PERIOD_SCALE = 0.8f

        /** Eigene Action: sonst gleiche Identität wie die allgemeine «App öffnen» des Widgets. */
        const val ACTION_OPEN_SINGLE_WIDGET = "com.cryptochecker.app.action.OPEN_FROM_SINGLE_WIDGET"
    }
}
