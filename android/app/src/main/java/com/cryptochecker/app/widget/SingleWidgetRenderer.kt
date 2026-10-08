package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einzel-Widget: ein Paar mit Kurs, Veränderung und Chart (Kerzen oder Linie; 24 h / 7 / 30 Tage).
 * Die Stundenkerzen dienen auch dem Wertverlauf des Portfolio-Widgets ([cachedHourlyPrices]).
 */
@Singleton
class SingleWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: com.cryptochecker.app.data.WatchRepository,
    private val candleDataSource: CandleDataSource,
    private val toolkit: WidgetToolkit,
) {
    /** Mini-Chart-Kerzen je Symbol und Zeitraum, 10 Minuten zwischengespeichert (für Kerzen und Linie). */
    private val sparkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<WidgetCandle>>>()

    /**
     * Zuletzt gezeichneter Chart je Einzel-Widget: Gleiche Kerzen, Darstellung, Farben, Grösse
     * und gleicher angezeigter Kurs ergeben dasselbe Bild — dann nicht neu zeichnen.
     * Nur das jeweils letzte Bild je Widget, beim Löschen entfernt ([forget]).
     */
    private val chartBitmaps = LatestPerWidget<ChartBitmapKey, android.graphics.Bitmap>()

    /** Zeichnet Einzel-Widgets neu: Paar, Kurs, Änderung, Chart (Kerzen/Linie; 24 h / 7 / 30 Tage). */
    suspend fun update(appWidgetIds: IntArray): Boolean {
        if (appWidgetIds.isEmpty()) return false
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val watches = watchRepository.getWatches().associateBy { it.id }
        val now = System.currentTimeMillis()
        val outdatedAfter = WidgetOutdated.afterMillis(settings)
        val changeView = toolkit.changeView(settings.changeBasis, now)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val watch = widgetPrefs.getWatchId(appWidgetId)?.let { watches[it] }
            val views = RemoteViews(context.packageName, R.layout.widget_single)

            toolkit.background(views, R.id.single_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.single_logo, accent.logoRes(dark))
            // Noch ohne Paar (z. B. aus der App hinzugefügt, Einrichten nicht geöffnet):
            // Tippen öffnet das Einrichten statt der App
            views.setOnClickPendingIntent(R.id.single_root, if (watch == null) configureSingle(appWidgetId) else toolkit.openApp())

            if (watch == null) {
                views.setTextViewText(R.id.single_pair, context.getString(R.string.single_widget_choose))
                views.setTextColor(R.id.single_pair, colors.textColor)
                views.setTextViewText(R.id.single_price, "—")
                views.setTextColor(R.id.single_price, colors.textColor)
            } else {
                views.setTextColor(R.id.single_pair, colors.textColor)
                views.setTextViewText(R.id.single_market, watch.marketName)
                views.setTextColor(R.id.single_market, colors.secondaryTextColor)
                views.setTextViewText(R.id.single_price, PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                views.setTextColor(R.id.single_price, colors.textColor)

                // Veränderung gemäss %-Basis wie die Pille in der Merkliste, mit «24h» / «heute» — der
                // Chart daneben kann einen anderen Zeitraum zeigen. Ohne Bezug «—», ebenso bei
                // nicht mehr gehandelten Paaren (dann auch kein Chart) und veralteter Basis.
                val change = changeView.shown(watch.shownChange24h)
                val day = ChangeBasisText.shortLabel(context, changeView.basis)
                // Pfeil wie in der Merkliste (folgt dem Vorzeichen, nie dem Farbtausch)
                val changeText = when {
                    change == null -> "— $day"
                    else -> PriceFormat.changePercent(change)?.let { text ->
                        PriceFormat.changeArrow(change).let { if (it.isEmpty()) text else "$it $text" }
                    }?.let { "$it $day" } ?: "${PriceFormat.zeroPercent()} $day"
                }
                views.setTextViewText(R.id.single_change, changeText)
                // Paar ganz, wenn es neben der Veränderung Platz hat, sonst nur die Basis
                // («BTC» statt «BT…»); die Veränderung wird nie gekürzt
                val pairLabel = WidgetTextFit.singlePairLabel(
                    pair = watch.displayName,
                    base = watch.baseAsset,
                    widgetWidthDp = toolkit.sizeDp(manager, appWidgetId).first,
                    changeWidthDp = toolkit.textWidthDp(changeText, 13f, tabular = true),
                ) { toolkit.textWidthDp(it, 14f) }
                views.setTextViewText(R.id.single_pair, pairLabel)
                views.setTextColor(
                    R.id.single_change,
                    when {
                        change == null || PriceFormat.changePercent(change) == null -> colors.neutralColor
                        change >= 0 -> colors.upColor
                        else -> colors.downColor
                    }
                )
                // Zeitraum des Charts neben der Uhrzeit, z. B. «14:05 · 7T»; alter Stand
                // ausgeschrieben: «veraltet · 06:42 · 7T»
                val range = widgetPrefs.getChartRange(appWidgetId)
                val outdated = WidgetOutdated.isOutdated(watch.lastUpdate, watch.lastError, now, outdatedAfter)
                val timeText = if (outdated) WidgetOutdated.label(context, watch.lastUpdate) else PriceFormat.time(watch.lastUpdate)
                views.setTextViewText(
                    R.id.single_time,
                    timeText + " · " + context.getString(range.shortLabelRes)
                )
                views.setTextColor(R.id.single_time, colors.secondaryTextColor)

                val chartType = widgetPrefs.getChartType(appWidgetId)
                val candles = if (watch.isNotTraded) null else sparkline(watch.baseAsset, watch.quoteAsset, range)
                // Screenreader: Verlauf als Satz (Zeitraum, Start, Ende, Änderung, Hoch, Tief);
                // Kerzen: erste Eröffnung, letzter Schluss, höchstes Hoch, tiefstes Tief
                val chartDescription = candles?.takeIf { it.size >= 2 }?.let {
                    A11yText.chart(context, context.getString(range.labelRes), WidgetChartGeometry.summary(it, chartType))
                }
                // Zeichnen nicht auf dem Main-Thread (Aufrufe auch aus App und ViewModel)
                val chartBitmap = candles?.takeIf { it.size >= 2 }?.let {
                    withContext(Dispatchers.Default) {
                        runCatching { drawChart(manager, appWidgetId, it, chartType, range, colors, highContrast, watch.lastPrice) }
                            .onFailure { e -> Timber.w(e, "Chart für Widget %d nicht gezeichnet", appWidgetId) }
                            .getOrNull()
                    }
                }
                if (chartBitmap != null) {
                    views.setImageViewBitmap(R.id.single_chart, chartBitmap)
                    views.setViewVisibility(R.id.single_chart, android.view.View.VISIBLE)
                    views.setContentDescription(R.id.single_chart, chartDescription)
                } else {
                    views.setViewVisibility(R.id.single_chart, android.view.View.INVISIBLE)
                }
                // Das ganze Widget als ein Satz: Paar, Kurs, Änderung, Verlauf, Uhrzeit
                views.setContentDescription(
                    R.id.single_root,
                    A11yText.row(
                        context = context,
                        pair = watch.displayName,
                        market = watch.marketName,
                        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                        change24h = change,
                        basis = changeView.basis,
                        extras = listOf(
                            chartDescription,
                            if (outdated) WidgetOutdated.spoken(context, watch.lastUpdate)
                            else PriceFormat.time(watch.lastUpdate).takeIf { it != "—" },
                        ),
                    )
                )
            }

            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Einzel-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /** Einrichten eines Einzel-Widgets öffnen (Tippen auf ein Widget ohne Paar). */
    private fun configureSingle(appWidgetId: Int): PendingIntent {
        val intent = Intent(context, SingleWidgetConfigureActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            // Eigene data-Uri je Widget: sonst teilten sich alle Widgets einen PendingIntent
            data = Uri.parse("cryptochecker://widget/single/$appWidgetId")
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

    /**
     * Kerzen für den Chart — gleiches Paar, auch wenn das Widget eine andere
     * Börse zeigt. Quelle über die Ausweich-Kette (Binance, Binance.US, Coinbase),
     * siehe [CandleDataSource]. 24 h = 24 × 1 h, 7 Tage = 42 × 4 h, 30 Tage = 30 × 1 Tag.
     * Eröffnung, Hoch, Tief und Schluss bleiben erhalten (Kerzen- und Linien-Chart).
     */
    private suspend fun sparkline(base: String, quote: String, range: WidgetChartRange): List<WidgetCandle>? {
        val q = quote.uppercase().let { if (it == "USD") "USDT" else it }
        val symbol = base.uppercase() + q
        val cacheKey = "$symbol|${range.name}"
        sparkCache[cacheKey]?.let { (time, data) ->
            if (System.currentTimeMillis() - time < 10 * 60_000L) return data
        }
        // CandleDataSource arbeitet auf Dispatchers.IO, nie auf dem Main-Thread.
        // Gesamtgrenze: Die Ausweich-Kette kann sonst fast eine Minute dauern
        // (goAsync im Widget-Empfänger). Abbruch des Aufrufers wird weitergereicht.
        val candles = try {
            withTimeoutOrNull(SPARKLINE_TIMEOUT_MILLIS) {
                candleDataSource.candles(base, quote, range.candleInterval, range.limit)?.map {
                    WidgetCandle(openTime = it.openTime, open = it.open, high = it.high, low = it.low, close = it.close)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Kerzen für Widget nicht verfügbar: %s", symbol)
            null
        }
        if (candles != null && candles.size >= 2) {
            sparkCache[cacheKey] = System.currentTimeMillis() to candles
            return candles
        }
        return null
    }

    /**
     * Chart in der tatsächlichen Grösse der Bildfläche: Widget-Grösse aus den
     * Widget-Optionen (Hochformat: minWidth × maxHeight, Querformat: maxWidth × minHeight)
     * abzüglich Rand und Textzeilen von widget_single.xml. Ohne Angaben eine feste Grösse.
     */
    private fun drawChart(
        manager: AppWidgetManager,
        appWidgetId: Int,
        candles: List<WidgetCandle>,
        type: WidgetChartType,
        range: WidgetChartRange,
        colors: WidgetColors,
        highContrast: Boolean,
        currentPrice: Double?,
    ): android.graphics.Bitmap {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val density = metrics.density
        val fontScale = config.fontScale.takeIf { it > 0f } ?: 1f
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
        val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val widgetW = options?.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
        ) ?: 0
        val widgetH = options?.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
        ) ?: 0
        // Feste Teile des Layouts: Rand 2 × 12 dp; Kopfzeile 14 sp, Börse 11 sp, Kurs 24 sp
        // (+4 dp), Chart-Abstand 6 dp, Uhrzeit 10 sp (+2 dp). Zeilenhöhe ≈ 1.35 × Schriftgrösse.
        val chartWDp = if (widgetW > 0) (widgetW - 24f) else DEFAULT_CHART_WIDTH_DP
        val chartHDp = if (widgetH > 0) (widgetH - 24f - 12f - (14f + 11f + 24f + 10f) * 1.35f * fontScale) else DEFAULT_CHART_HEIGHT_DP
        val wDp = chartWDp.coerceAtLeast(60f)
        val hDp = chartHDp.coerceAtLeast(32f)
        // Bitmap-Speicher begrenzen (RemoteViews-Grenze): höchstens ~1 MP; fitXY skaliert gleichmässig zurück.
        var scale = density
        val pixels = wDp * scale * hDp * scale
        if (pixels > MAX_CHART_PIXELS) scale *= kotlin.math.sqrt(MAX_CHART_PIXELS / pixels)
        val widthPx = (wDp * scale).toInt()
        val heightPx = (hDp * scale).toInt()
        val compact = WidgetChartGeometry.isCompact(wDp, hDp)
        val zone = java.time.ZoneId.systemDefault()

        // Alles, wovon das Bild abhängt; der Kurs nur so genau, wie er im Etikett steht
        val key = ChartBitmapKey(
            candles = candles,
            type = type,
            range = range,
            accentColor = colors.accentColor,
            onAccentColor = colors.onAccentColor,
            secondaryTextColor = colors.secondaryTextColor,
            upColor = colors.upColor,
            downColor = colors.downColor,
            highContrast = highContrast,
            widthPx = widthPx,
            heightPx = heightPx,
            density = scale,
            fontScale = fontScale,
            compact = compact,
            priceTag = PriceFormat.price(currentPrice?.takeIf { it > 0.0 } ?: candles.last().close),
            zone = zone.id,
            locale = config.locales.toLanguageTags(),
        )
        chartBitmaps.get(appWidgetId, key)?.let { return it }

        return WidgetChartRenderer.draw(
            candles = candles,
            type = type,
            range = range,
            colors = colors,
            highContrast = highContrast,
            widthPx = widthPx,
            heightPx = heightPx,
            density = scale,
            fontScale = fontScale,
            compact = compact,
            formatPrice = PriceFormat::price,
            currentPrice = currentPrice,
            zone = zone,
        ).also { chartBitmaps.put(appWidgetId, key, it) }
    }

    /** Gelöschte Einzel-Widgets: zwischengespeicherten Chart freigeben. */
    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { chartBitmaps.remove(it) }
    }

    /** Wovon das Bild des Einzel-Widget-Charts abhängt (siehe [chartBitmaps]). */
    private data class ChartBitmapKey(
        // Die Kerzen selbst (nicht nur ihr Hash): keine Verwechslung bei gleichem Hash.
        // Meist dieselbe Liste aus sparkCache, der Vergleich ist dann sofort entschieden.
        val candles: List<WidgetCandle>,
        val type: WidgetChartType,
        val range: WidgetChartRange,
        val accentColor: Int,
        val onAccentColor: Int,
        val secondaryTextColor: Int,
        val upColor: Int,
        val downColor: Int,
        val highContrast: Boolean,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val fontScale: Float,
        val compact: Boolean,
        val priceTag: String,
        val zone: String,
        val locale: String,
    )

    private companion object {
        /** Höchstens so lange auf die Kerzen eines Widgets warten (ganze Ausweich-Kette). */
        const val SPARKLINE_TIMEOUT_MILLIS = 20_000L
    }
}
