package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.shownChange24h
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.data.CachedValue
import com.cryptochecker.app.data.CycleCacheCodecs
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.PulseDataSource
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSize
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Zeitraum des Mini-Charts im Einzel-Widget: Kerzenintervall und Anzahl,
 * Länge einer Kerze und Abstand der senkrechten Gitterlinien.
 * Gespeichert wird der Name (siehe [WidgetPrefs.getChartRange]). [inWidget] = im
 * Widget wählbar; 1 Jahr (365 Tageskerzen) gibt es nur im Chart des Aktionsblatts.
 */
enum class WidgetChartRange(
    val candleInterval: CandleInterval,
    val limit: Int,
    val labelRes: Int,
    val shortLabelRes: Int,
    val intervalMillis: Long,
    val gridUnit: ChartGridUnit,
    val inWidget: Boolean = true,
) {
    DAY(CandleInterval.H1, 24, R.string.widget_range_24h, R.string.widget_range_short_24h, 3_600_000L, ChartGridUnit.HOUR),
    WEEK(CandleInterval.H4, 42, R.string.widget_range_7d, R.string.widget_range_short_7d, 4 * 3_600_000L, ChartGridUnit.DAY),
    MONTH(CandleInterval.D1, 30, R.string.widget_range_30d, R.string.widget_range_short_30d, 24 * 3_600_000L, ChartGridUnit.WEEK),
    YEAR(CandleInterval.D1, 365, R.string.widget_range_1y, R.string.widget_range_short_1y, 24 * 3_600_000L, ChartGridUnit.MONTH, inWidget = false),
}

/** Ids einer Zeile der Positionsliste im Portfolio-Widget (widget_portfolio_large.xml). */
private class PortfolioRowIds(
    val row: Int,
    val symbol: Int,
    val track: Int,
    val bar: Int,
    val share: Int,
    val change: Int,
)

/** Die drei Coin-Chips des Widgets «Was gerade auffällt»: Rahmen, Hintergrund, Text (widget_pulse.xml). */
private val PULSE_CHIPS = listOf(
    Triple(R.id.pulse_chip_1, R.id.pulse_chip_1_bg, R.id.pulse_chip_1_text),
    Triple(R.id.pulse_chip_2, R.id.pulse_chip_2_bg, R.id.pulse_chip_2_text),
    Triple(R.id.pulse_chip_3, R.id.pulse_chip_3_bg, R.id.pulse_chip_3_text),
)

/** Die drei festen Zeilen der Positionsliste (statisch statt Liste mit RemoteViewsService). */
private val PORTFOLIO_ROWS = listOf(
    PortfolioRowIds(R.id.portfolio_pos_1, R.id.portfolio_pos_1_symbol, R.id.portfolio_pos_1_track, R.id.portfolio_pos_1_bar,
        R.id.portfolio_pos_1_share, R.id.portfolio_pos_1_change),
    PortfolioRowIds(R.id.portfolio_pos_2, R.id.portfolio_pos_2_symbol, R.id.portfolio_pos_2_track, R.id.portfolio_pos_2_bar,
        R.id.portfolio_pos_2_share, R.id.portfolio_pos_2_change),
    PortfolioRowIds(R.id.portfolio_pos_3, R.id.portfolio_pos_3_symbol, R.id.portfolio_pos_3_track, R.id.portfolio_pos_3_bar,
        R.id.portfolio_pos_3_share, R.id.portfolio_pos_3_change),
)

/** Zeichnet die Startbildschirm-Widgets neu. */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val refreshStats: RefreshStats,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: com.cryptochecker.app.data.WatchRepository,
    private val candleDataSource: CandleDataSource,
    private val portfolioSnapshotStore: PortfolioSnapshotStore,
    private val pulseDataSource: PulseDataSource,
    private val cycleCacheStore: CycleCacheStore,
) {
    /** Mini-Chart-Kerzen je Symbol und Zeitraum, 10 Minuten zwischengespeichert (für Kerzen und Linie). */
    private val sparkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<WidgetCandle>>>()

    /**
     * Zuletzt gezeichneter Chart je Einzel-Widget: Gleiche Kerzen, Darstellung, Farben, Grösse
     * und gleicher angezeigter Kurs ergeben dasselbe Bild — dann nicht neu zeichnen.
     * Nur das jeweils letzte Bild je Widget, beim Löschen entfernt ([forgetWidgets]).
     */
    private val chartBitmaps = LatestPerWidget<ChartBitmapKey, android.graphics.Bitmap>()

    /**
     * Zuletzt gezeichneter Wertverlauf je Portfolio-Widget und Grösse (Schlüssel
     * Widget-Id × [MAX_PORTFOLIO_SIZES] + Grösse), beim Löschen entfernt ([forgetPortfolioWidgets]).
     */
    private val portfolioCharts = LatestPerWidget<PortfolioChartKey, android.graphics.Bitmap>()

    /** Stand der Momentaufnahme beim letzten Zeichnen der Portfolio-Widgets ([updatePortfolio]). */
    @Volatile
    private var portfolioDrawn: PortfolioDrawState? = null

    fun singleWidgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, SingleWidgetProvider::class.java))
        }.getOrNull() ?: IntArray(0)
    }

    /**
     * %-Basis der Widgets und ob die gespeicherten Veränderungen noch dazu passen (Stempel des
     * letzten Durchlaufs); sonst «—» bis zur nächsten Aktualisierung.
     */
    fun changeView(basis: ChangeBasis, now: Long = System.currentTimeMillis()): ChangeView =
        ChangeView.of(refreshStats.changeStamp.value, basis, now)

    /** Zeichnet Einzel-Widgets neu: Paar, Kurs, Änderung, Chart (Kerzen/Linie; 24 h / 7 / 30 Tage). */
    suspend fun updateSingle(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val watches = watchRepository.getWatches().associateBy { it.id }
        val now = System.currentTimeMillis()
        val outdatedAfter = WidgetOutdated.afterMillis(settings)
        val changeView = changeView(settings.changeBasis, now)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val watch = widgetPrefs.getWatchId(appWidgetId)?.let { watches[it] }
            val views = RemoteViews(context.packageName, R.layout.widget_single)

            background(views, R.id.single_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.single_logo, accent.logoRes(dark))
            // Noch ohne Paar (z. B. aus der App hinzugefügt, Einrichten nicht geöffnet):
            // Tippen öffnet das Einrichten statt der App
            views.setOnClickPendingIntent(R.id.single_root, if (watch == null) configureSingle(appWidgetId) else openApp())

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
                    widgetWidthDp = widgetSizeDp(manager, appWidgetId).first,
                    changeWidthDp = textWidthDp(changeText, 13f, tabular = true),
                ) { textWidthDp(it, 14f) }
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
        scheduleOutdatedCheck()
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
     * Wecker auf den Moment, an dem der nächste angezeigte Stand veraltet ([WidgetOutdated]):
     * Uhrzeit der Liste, Kurse der Paare (Listen- und Einzel-Widgets), Momentaufnahme des
     * Portfolios. Dann zeichnet [redrawOutdated] die Widgets ohne Netz neu.
     */
    private suspend fun scheduleOutdatedCheck() {
        val hasList = widgetIds().isNotEmpty()
        val hasSingle = singleWidgetIds().isNotEmpty()
        val hasPortfolio = portfolioWidgetIds().isNotEmpty()
        val settings = settingsRepository.current()
        val times = buildList {
            if (hasList) add(refreshStats.lastRefresh())
            if (hasList || hasSingle) {
                watchRepository.getWatches()
                    .filterNot { it.isNotTraded }
                    .forEach { add(it.lastUpdate) }
            }
            if (hasPortfolio && !PortfolioLockPolicy.widgetLocked(settings.appLock)) portfolioSnapshotStore.snapshot()?.let { add(it.time) }
        }
        WidgetOutdated.schedule(context, times, WidgetOutdated.afterMillis(settings))
    }

    /** Vom Wecker ([WidgetOutdated.schedule]): Listen-, Einzel- und Portfolio-Widgets neu zeichnen. */
    suspend fun redrawOutdated() {
        update(widgetIds())
        updateSingle(singleWidgetIds())
        updatePortfolio(portfolioWidgetIds())
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
    fun forgetWidgets(appWidgetIds: IntArray) {
        appWidgetIds.forEach { chartBitmaps.remove(it) }
    }

    fun widgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
        }.getOrNull() ?: IntArray(0)
    }

    /**
     * Beim Start der App: Hat sich der wirksame hohe Kontrast (Einstellung oder
     * System-Kontrast) seit dem letzten Zeichnen geändert, alle Widgets neu zeichnen.
     */
    suspend fun updateIfContrastChanged() {
        val effective = HighContrast.isEffective(context, settingsRepository.current().highContrast)
        if (widgetPrefs.lastHighContrast == effective) return
        val hasWidgets = widgetIds().isNotEmpty() || singleWidgetIds().isNotEmpty() ||
            portfolioWidgetIds().isNotEmpty() || pulseWidgetIds().isNotEmpty()
        if (hasWidgets) updateAll() else widgetPrefs.lastHighContrast = effective
    }

    /** Bildschirm an (bzw. Gerät bedienbar)? Ohne PowerManager: ja. */
    fun isScreenInteractive(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    suspend fun updateAll() {
        update(widgetIds())
        updateSingle(singleWidgetIds())
        updatePortfolio(portfolioWidgetIds())
        updatePulseWithOthers()
    }

    /**
     * Nach der Aktualisierung EINES Paares: nur, was dieses Paar zeigt — Listen-Widgets,
     * deren Liste es enthält, und Einzel-Widgets dieses Paares. Portfolio-Widgets nur, wenn
     * sich die Momentaufnahme seit dem letzten Zeichnen geändert hat; «Was gerade auffällt»
     * hängt nicht an einem einzelnen Paar und bleibt.
     */
    suspend fun updateForWatch(watchId: Long) {
        val listIds = widgetIds()
        if (listIds.isNotEmpty()) {
            val watches = watchRepository.getWatches()
            val watch = watches.firstOrNull { it.id == watchId }
            val groups = watches.mapTo(HashSet()) { it.groupName }
            // Paar inzwischen gelöscht: alle Listen, damit es überall verschwindet
            update(
                listIds.filter { id ->
                    watch == null || WidgetRefreshScope.listShowsWatch(widgetPrefs.getGroup(id), watch.groupName, groups)
                }.toIntArray()
            )
        }
        updateSingle(singleWidgetIds().filter { widgetPrefs.getWatchId(it) == watchId }.toIntArray())

        val portfolioIds = portfolioWidgetIds()
        if (portfolioIds.isNotEmpty()) {
            val snapshot = portfolioSnapshotStore.snapshot()
            val state = PortfolioDrawState(snapshot?.total, snapshot?.time)
            if (WidgetRefreshScope.portfolioChanged(portfolioDrawn, state)) updatePortfolio(portfolioIds)
        }
    }

    fun portfolioWidgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        }.getOrNull() ?: IntArray(0)
    }

    /**
     * Portfolio-Widgets aus der letzten Momentaufnahme ([PortfolioSnapshotStore]) zeichnen —
     * ohne Netz. Layout je Stufe ([PortfolioWidgetMath.size]): klein = Kopfzeile mit Pille,
     * Gesamtwert, Betrag über 24 h; schmal-hoch = dazu der Wertverlauf darunter; mittel = Werte
     * links, Wertverlauf rechts; gross = dazu die drei grössten Positionen. Ab Android 12 je
     * Grösse des Launchers ein eigenes Layout (RemoteViews mit Grössen-Zuordnung), sonst nach
     * der Grösse aus den Widget-Optionen. Mit Portfolio-Sperre nur Titel, Schloss und
     * «Gesperrt – in der App entsperren», ohne Werte.
     */
    suspend fun updatePortfolio(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val stored = portfolioSnapshotStore.snapshot()
        portfolioDrawn = PortfolioDrawState(stored?.total, stored?.time)
        // %-Basis: Aufnahme mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        // (bis zur nächsten Aufnahme); der Verlauf bleibt, beschriftet nach seiner eigenen Basis
        val snapshot = stored?.let {
            if (ChangeBasisMath.isCurrent(it.stamp, settings.changeBasis, System.currentTimeMillis())) it
            else it.copy(changeAmount = null, changePercent = null, positions = it.positions.map { p -> p.copy(change24hPercent = null) })
        }
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val outdated = snapshot != null &&
            OutdatedRule.isOutdated(snapshot.time, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))
        val locked = PortfolioLockPolicy.widgetLocked(settings.appLock)

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val style = PortfolioStyle(
                colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted),
                dark = dark,
                highContrast = highContrast,
                logoRes = accent.logoRes(dark),
                opacity = widgetPrefs.getOpacity(appWidgetId),
                basis = settings.changeBasis,
                hideAmounts = settings.hidePortfolioAmounts,
            )
            val message: String? = when {
                locked -> context.getString(R.string.widget_portfolio_locked_hint)
                snapshot == null || snapshot.empty -> context.getString(R.string.widget_portfolio_empty)
                else -> null
            }
            val views = if (message != null || snapshot == null) {
                portfolioMessageViews(style, message.orEmpty(), locked)
            } else {
                val wantUsdt = snapshot.totalUsdt != null &&
                    PortfolioWidgetMath.showsUsdt(widgetPrefs.getPortfolioShowUsdt(appWidgetId), snapshot.currency)
                val sizes = portfolioSizesDp(manager, appWidgetId)
                if (sizes.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Je Grösse (Hoch-/Querformat) ein Layout; der Launcher wählt ohne neuen Aufruf
                    RemoteViews(
                        sizes.mapIndexed { slot, (w, h) ->
                            SizeF(w.toFloat(), h.toFloat()) to
                                portfolioViews(appWidgetId, slot, w, h, snapshot, style, outdated, fontScale, wantUsdt)
                        }.toMap()
                    )
                } else {
                    val (w, h) = sizes.firstOrNull() ?: (0 to 0)
                    portfolioViews(appWidgetId, 0, w, h, snapshot, style, outdated, fontScale, wantUsdt)
                }
            }
            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Portfolio-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        scheduleOutdatedCheck()
    }

    /** Farben und Einstellungen eines Portfolio-Widgets. */
    private class PortfolioStyle(
        val colors: WidgetColors,
        val dark: Boolean,
        val highContrast: Boolean,
        val logoRes: Int,
        val opacity: Int,
        /** %-Basis (Zeitraum neben Betrag und Pille, Screenreader). */
        val basis: ChangeBasis = ChangeBasis.DEFAULT,
        /** «Beträge verbergen»: Gesamtwert und Betrag als «•••», ohne ≈ USDT; Prozente bleiben. */
        val hideAmounts: Boolean = false,
    )

    /** Hintergrund, Logo, Titel und Tipp (öffnet den Portfolio-Tab) — in jedem Layout gleich. */
    private fun portfolioFrame(views: RemoteViews, style: PortfolioStyle) {
        background(views, R.id.portfolio_bg, style.colors, style.opacity)
        views.setImageViewResource(R.id.portfolio_logo, style.logoRes)
        views.setTextViewText(R.id.portfolio_title, context.getString(R.string.widget_portfolio_name))
        views.setTextColor(R.id.portfolio_title, style.colors.textColor)
        views.setOnClickPendingIntent(R.id.portfolio_root, openPortfolio())
    }

    /** Sperre oder «leer»: Titel, (Schloss,) Hinweis — keine Werte, kein Chart, keine Positionen. */
    private fun portfolioMessageViews(style: PortfolioStyle, message: String, locked: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_portfolio)
        portfolioFrame(views, style)
        views.setViewVisibility(R.id.portfolio_pill, View.GONE)
        views.setViewVisibility(R.id.portfolio_values, View.GONE)
        views.setViewVisibility(R.id.portfolio_lock_icon, if (locked) View.VISIBLE else View.GONE)
        if (locked) views.setInt(R.id.portfolio_lock_icon, "setColorFilter", style.colors.secondaryTextColor)
        views.setViewVisibility(R.id.portfolio_message, View.VISIBLE)
        views.setTextViewText(R.id.portfolio_message, message)
        views.setTextColor(R.id.portfolio_message, style.colors.secondaryTextColor)
        views.setContentDescription(
            R.id.portfolio_root,
            listOf(context.getString(R.string.widget_portfolio_name), message).joinToString(", ")
        )
        return views
    }

    /**
     * Ein Portfolio-Widget in der Grösse [widthDp] × [heightDp] (0 = unbekannt). [slot] trennt
     * die Chart-Bilder der Grössen eines Widgets im Zwischenspeicher ([portfolioCharts]).
     */
    private suspend fun portfolioViews(
        appWidgetId: Int,
        slot: Int,
        widthDp: Int,
        heightDp: Int,
        snapshot: PortfolioSnapshot,
        style: PortfolioStyle,
        outdated: Boolean,
        fontScale: Float,
        wantUsdt: Boolean,
    ): RemoteViews {
        val colors = style.colors
        val amount = snapshot.changeAmount?.takeIf { it.isFinite() }
        val percent = snapshot.changePercent?.takeIf { it.isFinite() }
        val layout = PortfolioWidgetMath.layout(
            widthDp = widthDp,
            heightDp = heightDp,
            fontScale = fontScale,
            hasChange = amount != null,
            wantUsdt = wantUsdt,
            positions = snapshot.positions.size,
        )
        val views = RemoteViews(
            context.packageName,
            when (layout.size) {
                PortfolioWidgetSize.MEDIUM -> R.layout.widget_portfolio_medium
                PortfolioWidgetSize.LARGE -> R.layout.widget_portfolio_large
                PortfolioWidgetSize.SMALL, PortfolioWidgetSize.TALL -> R.layout.widget_portfolio
            }
        )
        portfolioFrame(views, style)
        if (layout.size == PortfolioWidgetSize.SMALL || layout.size == PortfolioWidgetSize.TALL) {
            views.setViewVisibility(R.id.portfolio_lock_icon, View.GONE)
            views.setViewVisibility(R.id.portfolio_message, View.GONE)
        }
        views.setViewVisibility(R.id.portfolio_values, View.VISIBLE)

        // Richtung nach dem Vorzeichen des Betrags (nie nach dem Farbtausch): Farbe UND Pfeil
        val direction = amount?.let { PortfolioWidgetSeries.direction(it) }
        val priceColor = when (direction) {
            1 -> colors.upColor
            -1 -> colors.downColor
            else -> colors.neutralColor
        }
        val arrow = when (direction) {
            1 -> "▲"
            -1 -> "▼"
            else -> null
        }

        // Kopfzeile rechts: «▼ 2.31%» (24 h) als Pille in der Kursfarbe; wird es eng, fällt der
        // Titel weg (Logo und Pille bleiben), statt «Portf…»
        val pillText = if (percent != null && amount != null) {
            listOfNotNull(arrow, PortfolioSnapshotMath.unsignedPercent(percent)).joinToString(" ")
        } else null
        val title = context.getString(R.string.widget_portfolio_name)
        val titleFits = PortfolioWidgetMath.showsTitle(
            widthDp,
            textWidthDp(title, 13f),
            pillText?.let { textWidthDp(it, 11f, tabular = true) },
        )
        views.setTextViewText(R.id.portfolio_title, if (titleFits) title else "")
        if (pillText != null) {
            views.setViewVisibility(R.id.portfolio_pill, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_pill_text, pillText)
            views.setTextColor(R.id.portfolio_pill_text, priceColor)
            pill(views, R.id.portfolio_pill_bg, priceColor, style.dark, style.highContrast)
        } else {
            views.setViewVisibility(R.id.portfolio_pill, View.GONE)
        }

        // Gesamtwert (passt sich der Breite an)
        val total = PortfolioInsights.mask(PriceFormat.valueWithCurrency(snapshot.total, snapshot.currency), style.hideAmounts)
        views.setTextViewText(R.id.portfolio_total, total)
        views.setTextColor(R.id.portfolio_total, colors.textColor)

        // «▼ −1’968.40 CHF · 24h»; passt «· 24h» nicht in die Breite, ohne
        val textWidth = when {
            widthDp <= 0 -> null
            layout.size == PortfolioWidgetSize.MEDIUM -> (widthDp - 24f - 12f) / 2f
            else -> widthDp - 24f
        }
        if (layout.changeLine && amount != null) {
            val value = listOfNotNull(
                arrow,
                PortfolioInsights.mask(PortfolioSnapshotMath.signedAmount(amount, snapshot.currency), style.hideAmounts),
            ).joinToString(" ")
            val text = WidgetTextFit.firstFitting(
                listOf("$value · ${ChangeBasisText.shortLabel(context, style.basis)}", value),
                textWidth?.minus(WidgetTextFit.SAFETY_DP),
            ) { textWidthDp(it, 12f, tabular = true) }
            views.setViewVisibility(R.id.portfolio_change, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_change, text)
            views.setTextColor(R.id.portfolio_change, priceColor)
        } else {
            views.setViewVisibility(R.id.portfolio_change, View.GONE)
        }

        // «≈ 100’143.67 USDT» — je Widget wählbar; nicht, wenn die Anzeige schon USD ist
        val usdt = snapshot.totalUsdt?.takeIf { layout.usdt && !style.hideAmounts }?.let { PriceFormat.valueWithCurrency(it, USDT) }
        if (usdt != null) {
            views.setViewVisibility(R.id.portfolio_usdt, View.VISIBLE)
            views.setTextViewText(R.id.portfolio_usdt, "≈ $usdt")
            views.setTextColor(R.id.portfolio_usdt, colors.secondaryTextColor)
        } else {
            views.setViewVisibility(R.id.portfolio_usdt, View.GONE)
        }

        // Wertverlauf: Fläche ab genug Stundenwerten, sonst ruhig «Verlauf folgt»
        val points = snapshot.history.filter { it.value.isFinite() }
        val drawable = layout.chart && PortfolioWidgetSeries.drawable(points)
        // Verlauf seit Tagesbeginn: «heute» / «heute UTC»; sonst «24h» bzw. «48h» (ältere Aufnahmen)
        val chartBasis = snapshot.stamp?.basis ?: ChangeBasis.ROLLING_24H
        val periodShort = if (drawable) {
            when {
                chartBasis.isDay -> ChangeBasisText.shortLabel(context, chartBasis)
                spanAtMostDay(points) -> context.getString(R.string.widget_range_short_24h)
                else -> context.getString(R.string.widget_portfolio_range_short_48h)
            }
        } else null
        val chartDescription = if (drawable) {
            val periodLong = when {
                chartBasis.isDay -> ChangeBasisText.longLabel(context, chartBasis)
                spanAtMostDay(points) -> context.getString(R.string.widget_range_24h)
                else -> context.getString(R.string.widget_portfolio_range_48h)
            }
            val hiddenSpoken = context.getString(R.string.a11y_amount_hidden)
            A11yText.chart(context, periodLong, points.map { it.value }) {
                if (style.hideAmounts) hiddenSpoken else PriceFormat.valueWithCurrency(it, snapshot.currency)
            }
        } else null
        if (layout.chart) {
            views.setViewVisibility(R.id.portfolio_chart_box, View.VISIBLE)
            val chartColor = when (direction ?: if (PortfolioWidgetMath.isUp(points)) 1 else -1) {
                1 -> colors.upColor
                -1 -> colors.downColor
                else -> colors.neutralColor
            }
            val (chartW, chartH) = PortfolioWidgetMath.chartSizeDp(layout, widthDp, heightDp, fontScale)
            val bitmap = if (drawable) {
                withContext(Dispatchers.Default) {
                    runCatching { drawPortfolioChart(appWidgetId, slot, points, chartColor, colors.secondaryTextColor, chartW, chartH) }
                        .onFailure { Timber.w(it, "Wertverlauf für Portfolio-Widget %d nicht gezeichnet", appWidgetId) }
                        .getOrNull()
                }
            } else null
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.portfolio_chart, bitmap)
                views.setViewVisibility(R.id.portfolio_chart, View.VISIBLE)
                views.setViewVisibility(R.id.portfolio_chart_pending, View.GONE)
            } else {
                views.setViewVisibility(R.id.portfolio_chart, View.GONE)
                views.setViewVisibility(R.id.portfolio_chart_pending, View.VISIBLE)
                views.setTextColor(R.id.portfolio_chart_pending, colors.secondaryTextColor)
            }
        } else {
            views.setViewVisibility(R.id.portfolio_chart_box, View.GONE)
        }
        // Platzhalter hält die Fusszeile unten: klein und mittel (linke Spalte); sonst füllt der Chart
        views.setViewVisibility(
            R.id.portfolio_spacer,
            if (layout.size == PortfolioWidgetSize.SMALL || layout.size == PortfolioWidgetSize.MEDIUM) View.VISIBLE else View.GONE
        )

        // Gross: die drei grössten Positionen
        val positionsText = if (layout.size == PortfolioWidgetSize.LARGE) {
            renderPositions(views, snapshot.positions.take(layout.rows), colors, style.basis)
        } else null

        // «Stand 15:19 · 24h»; alter Stand ausgeschrieben: «veraltet · 06:42»
        val stamp = WidgetOutdated.stamp(context, snapshot.time)
        val timeText = if (outdated) WidgetOutdated.label(context, snapshot.time)
        else context.getString(R.string.widget_portfolio_as_of, stamp)
        views.setViewVisibility(R.id.portfolio_time, if (layout.footer) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.portfolio_time, listOfNotNull(timeText, periodShort).joinToString(" · "))
        views.setTextColor(R.id.portfolio_time, colors.secondaryTextColor)

        // Screenreader: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» —
        // dann ≈ USDT, Verlauf und Positionen (nur was sichtbar ist); veraltet immer
        val change = when {
            percent != null -> ChangeBasisText.spoken(context, style.basis, percent)
            amount != null && direction == 0 ->
                ChangeBasisText.spokenPhrase(context, style.basis, context.getString(R.string.a11y_change_flat))
            amount != null -> ChangeBasisText.spokenPhrase(
                context,
                style.basis,
                context.getString(
                    if (amount > 0) R.string.a11y_change_up else R.string.a11y_change_down,
                    if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden)
                    else PriceFormat.valueWithCurrency(kotlin.math.abs(amount), snapshot.currency)
                )
            )
            else -> null
        }
        views.setContentDescription(
            R.id.portfolio_root,
            listOfNotNull(
                "$title " + if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else total,
                change,
                if (outdated) WidgetOutdated.spoken(context, snapshot.time)
                else context.getString(R.string.widget_portfolio_as_of, stamp),
                usdt?.let { context.getString(R.string.a11y_converted, it) },
                chartDescription,
                positionsText,
            ).joinToString(", ")
        )
        return views
    }

    /**
     * Grössen des Widgets in dp: ab Android 12 alle, die der Launcher meldet (Hoch- und
     * Querformat, höchstens [MAX_PORTFOLIO_SIZES]); sonst bzw. ohne Angabe die eine aus
     * [widgetSizeDp]. Leer nie — unbekannt ist (0, 0).
     */
    private fun portfolioSizesDp(manager: AppWidgetManager, appWidgetId: Int): List<Pair<Int, Int>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
            val sizes = options?.let {
                runCatching { BundleCompat.getParcelableArrayList(it, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java) }
                    .getOrNull()
            }.orEmpty()
                .map { it.width.roundToInt() to it.height.roundToInt() }
                .filter { (w, h) -> w > 0 && h > 0 }
                .distinct()
                .take(MAX_PORTFOLIO_SIZES)
            if (sizes.isNotEmpty()) return sizes
        }
        return listOf(widgetSizeDp(manager, appWidgetId))
    }

    /**
     * Zeilen der Positionsliste (gross): Kürzel, Anteil als Text und dünner Balken (neutral, nicht
     * in der Akzentfarbe), Veränderung über 24 h mit Pfeil und Vorzeichen in der Kursfarbe
     * (ohne Vergleichswert «—»). Nicht gebrauchte Zeilen ausblenden.
     * @return Satz für den Screenreader oder null ohne Liste
     */
    private fun renderPositions(
        views: RemoteViews,
        shown: List<PortfolioPosition>,
        colors: WidgetColors,
        basis: ChangeBasis,
    ): String? {
        val spoken = mutableListOf<String>()
        PORTFOLIO_ROWS.forEachIndexed { index, ids ->
            val position = shown.getOrNull(index)
            if (position == null) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.symbol, position.symbol)
            views.setTextColor(ids.symbol, colors.textColor)
            val share = PortfolioWidgetMath.shareText(position.sharePercent)
            views.setTextViewText(ids.share, share)
            views.setTextColor(ids.share, colors.secondaryTextColor)
            views.setInt(ids.track, "setColorFilter", colors.secondaryTextColor or 0xFF000000.toInt())
            views.setInt(ids.track, "setImageAlpha", SHARE_TRACK_ALPHA)
            views.setInt(ids.bar, "setColorFilter", colors.secondaryTextColor or 0xFF000000.toInt())
            views.setInt(ids.bar, "setImageAlpha", SHARE_BAR_ALPHA)
            views.setInt(ids.bar, "setImageLevel", (position.sharePercent.coerceIn(0.0, 100.0) * 100.0).roundToInt())

            val change = position.change24hPercent?.takeIf { it.isFinite() }
            if (change != null) {
                val color = when {
                    kotlin.math.abs(change) < 0.005 -> colors.neutralColor
                    change > 0 -> colors.upColor
                    else -> colors.downColor
                }
                // Pfeil folgt dem Vorzeichen (nie dem Farbtausch); «0.00%» ohne Pfeil
                val text = PortfolioSnapshotMath.signedPercent(change)
                val arrow = PriceFormat.changeArrow(change)
                views.setTextViewText(ids.change, if (arrow.isEmpty()) text else "$arrow $text")
                views.setTextColor(ids.change, color)
                spoken += context.getString(
                    R.string.a11y_portfolio_position_24h, position.symbol, share, ChangeBasisText.spoken(context, basis, change)
                )
            } else {
                views.setTextViewText(ids.change, "—")
                views.setTextColor(ids.change, colors.secondaryTextColor)
                spoken += context.getString(
                    R.string.a11y_portfolio_position_24h, position.symbol, share, ChangeBasisText.spoken(context, basis, null)
                )
            }
        }
        val visible = shown.isNotEmpty()
        views.setViewVisibility(R.id.portfolio_positions, if (visible) View.VISIBLE else View.GONE)
        return if (visible) context.getString(R.string.a11y_portfolio_positions, spoken.joinToString("; ")) else null
    }

    /**
     * Hintergrund eines Widgets: Die Fläche mit runden Ecken (drawable/widget_background) liegt
     * als ImageView unter dem Inhalt; Farbe per setColorFilter, Deckkraft per setImageAlpha.
     * Beides geht in RemoteViews ab API 26 — eine Tönung des View-Hintergrunds
     * (setBackgroundTintList) erst ab API 31, setBackgroundColor verlöre die runden Ecken.
     */
    private fun background(views: RemoteViews, layerId: Int, colors: WidgetColors, opacityPercent: Int) {
        val color = colors.colorWithOpacity(opacityPercent)
        views.setInt(layerId, "setColorFilter", color or 0xFF000000.toInt())
        views.setInt(layerId, "setImageAlpha", Color.alpha(color))
    }

    /**
     * Breite eines Textes in dp, gemessen wie im Widget: [sp] mit der Schriftgrösse des
     * Systems, fett (wie die Kopfzeilen) und auf Wunsch mit Tabellenziffern.
     */
    private fun textWidthDp(text: String, sp: Float, bold: Boolean = true, tabular: Boolean = false): Float {
        if (text.isEmpty()) return 0f
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (tabular) fontFeatureSettings = "tnum"
        }
        return paint.measureText(text) / metrics.density
    }

    // ---- Widget «Was gerade auffällt» (Crypto Pulse)

    /** Höchstens ein Abruf der Pulse-Daten gleichzeitig (mehrere Auslöser kurz nacheinander). */
    private val pulseMutex = Mutex()

    /** Abruf im Hintergrund, damit [updateAll] (Kurs-Aktualisierung) nicht auf das Netz wartet. */
    private val pulseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun pulseWidgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, PulseWidgetProvider::class.java))
        }.getOrNull() ?: IntArray(0)
    }

    /**
     * Zeichnet die Widgets «Was gerade auffällt»: sofort aus dem Zwischenspeicher des
     * Markt-Tabs ([CycleCacheStore], Eintrag «pulse»). Ist er älter als dessen Gültigkeit
     * (5 Min., [CycleSource.PULSE]) und [fetch], frisch über [PulseDataSource] — dieselbe
     * Quelle wie die Karte —, speichern (die Karte zeigt ihn dann auch) und neu zeichnen.
     */
    suspend fun updatePulse(appWidgetIds: IntArray, fetch: Boolean = true) {
        if (appWidgetIds.isEmpty()) return
        val cached = readPulse()
        // Ohne gespeicherte Daten erst nach dem Abruf zeichnen (sonst kurz «nicht verfügbar»)
        if (cached != null || !fetch) drawPulse(appWidgetIds, cached)
        if (!fetch || isPulseFresh(cached)) return
        val loaded = loadPulse()
        if (loaded != null) drawPulse(appWidgetIds, loaded) else if (cached == null) drawPulse(appWidgetIds, null)
    }

    /**
     * Mit den anderen Widgets ([updateAll], gleicher Zeitplan): sofort aus dem Zwischenspeicher
     * zeichnen; ist er alt, im Hintergrund nachladen und dann neu zeichnen.
     */
    private suspend fun updatePulseWithOthers() {
        val ids = pulseWidgetIds()
        if (ids.isEmpty()) return
        val cached = readPulse()
        if (cached != null) drawPulse(ids, cached)
        if (isPulseFresh(cached)) return
        pulseScope.launch {
            val loaded = loadPulse()
            if (loaded != null || cached == null) drawPulse(pulseWidgetIds(), loaded)
        }
    }

    private fun isPulseFresh(value: CachedValue<PulseInput>?): Boolean =
        value != null && CycleCachePolicy.isFresh(value.savedAt, System.currentTimeMillis(), CycleSource.PULSE.ttlMillis)

    /** Gespeicherte Pulse-Eingaben, nur wenn sie sich auswerten lassen (wie im Markt-Tab). */
    private suspend fun readPulse(): CachedValue<PulseInput>? =
        cycleCacheStore.read(CycleSource.PULSE.key, CycleCacheCodecs.pulseInput)
            ?.takeIf { CryptoPulse.evaluate(it.value) != null }

    /**
     * Frisch laden (harte Zeitgrenze des Bereichs) und speichern; null bei Fehler. Lief
     * inzwischen schon ein Abruf (anderer Auslöser), gilt dessen frisches Ergebnis.
     */
    private suspend fun loadPulse(): CachedValue<PulseInput>? = pulseMutex.withLock {
        readPulse()?.takeIf { isPulseFresh(it) }?.let { return@withLock it }
        val fetched = try {
            withTimeoutOrNull(CycleSource.PULSE.timeoutMillis) { pulseDataSource.fetchInput() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.d(e, "Pulse für Widget: Zeitüberschreitung")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Pulse für Widget nicht verfügbar")
            null
        }
        val fresh = fetched?.takeIf { CryptoPulse.evaluate(it) != null } ?: return@withLock null
        val at = fresh.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        cycleCacheStore.write(CycleSource.PULSE.key, fresh, at, CycleCacheCodecs.pulseInput)
        CachedValue(fresh, at)
    }

    /**
     * Schlagzeile mit ▲/▼ in der Kursfarbe, Leitsatz (2 Zeilen, bei wenig Höhe 1), Coin-Chips
     * (ab 220 dp Breite BTC/ETH/SOL, sonst BTC) und Stand. Ohne (brauchbare) Daten ein ruhiger
     * Satz. Screenreader: das ganze Widget als ein Satz wie die Karte.
     */
    private suspend fun drawPulse(appWidgetIds: IntArray, value: CachedValue<PulseInput>?) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast
        val now = System.currentTimeMillis()
        val report = value?.takeIf { PulseWidgetMath.isShowable(it.savedAt, now) }?.let { CryptoPulse.evaluate(it.value) }
        val title = context.getString(R.string.pulse_now_title)
        // Fear & Greed nur aus dem Zwischenspeicher (Markt-Tab oder Pulse, ≤ 24 h) — kein eigener Abruf
        val fearGreedValue = if (report == null) null else {
            val cachedFng = cycleCacheStore.read(CycleSource.FEAR_GREED.key, CycleCacheCodecs.fearGreed)
            PulseWidgetMath.fearGreed(
                cached = cachedFng?.value?.value,
                cachedAt = cachedFng?.savedAt,
                pulse = value.value.fearGreed,
                pulseAt = value.savedAt,
                now = now,
            )
        }
        val fearGreedText = fearGreedValue?.let { PulseWidgetTexts.fearGreed(context, it) }

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            val views = RemoteViews(context.packageName, R.layout.widget_pulse)

            background(views, R.id.pulse_bg, colors, widgetPrefs.getOpacity(appWidgetId))
            views.setImageViewResource(R.id.pulse_logo, accent.logoRes(dark))
            views.setTextViewText(R.id.pulse_title, title)
            views.setTextColor(R.id.pulse_title, colors.textColor)
            views.setOnClickPendingIntent(R.id.pulse_root, openMarket())
            // Titel ganz oder gar nicht (dann nur das Logo), nie «Was gerade a…»
            val (widgetWidthDp, heightDp) = widgetSizeDp(manager, appWidgetId)
            val titleSp = PulseWidgetMath.titleSizeSp(widgetWidthDp) { textWidthDp(title, it) }
            views.setViewVisibility(R.id.pulse_title, if (titleSp != null) View.VISIBLE else View.INVISIBLE)
            views.setTextViewTextSize(R.id.pulse_title, TypedValue.COMPLEX_UNIT_SP, titleSp ?: 13f)

            if (report == null) {
                val message = context.getString(R.string.pulse_unavailable)
                views.setViewVisibility(R.id.pulse_content, View.GONE)
                views.setViewVisibility(R.id.pulse_message, View.VISIBLE)
                views.setTextViewText(R.id.pulse_message, message)
                views.setTextColor(R.id.pulse_message, colors.secondaryTextColor)
                views.setContentDescription(R.id.pulse_root, "$title. $message")
            } else {
                views.setViewVisibility(R.id.pulse_content, View.VISIBLE)
                views.setViewVisibility(R.id.pulse_message, View.GONE)

                // Zeichen folgt der Richtung, nie dem Farbtausch; gemischt/ruhig ohne Zeichen
                val headline = PulseWidgetTexts.headline(context, report.summary)
                val direction = PulseWidgetMath.direction(report.summary)
                val glyph = PulseWidgetMath.glyph(report.summary)
                views.setViewVisibility(R.id.pulse_glyph, if (glyph != null) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_glyph, glyph.orEmpty())
                views.setTextColor(R.id.pulse_glyph, if (direction > 0) colors.upColor else colors.downColor)
                views.setTextViewText(R.id.pulse_headline, headline)
                views.setTextColor(R.id.pulse_headline, colors.textColor)
                // Einzeilig 18 → 16 → 14 sp, sonst zweizeilig in 14 sp — nie «Breite Stä…»
                val headlineStyle = PulseWidgetMath.headlineStyle(widgetWidthDp, glyph != null) { textWidthDp(headline, it) }
                views.setTextViewTextSize(R.id.pulse_headline, TypedValue.COMPLEX_UNIT_SP, headlineStyle.sizeSp)
                views.setInt(R.id.pulse_headline, "setMaxLines", headlineStyle.lines)

                val lead = PulseWidgetTexts.lead(context, report)
                views.setTextViewText(R.id.pulse_lead, lead)
                views.setTextColor(R.id.pulse_lead, colors.textColor)
                views.setInt(R.id.pulse_lead, "setMaxLines", PulseWidgetMath.leadLines(heightDp, headlineStyle.lines))

                // «Fear & Greed 72 · Gier»: nur mittel/gross und wenn der Text ganz passt
                val showFng = fearGreedText != null && PulseWidgetMath.showsFearGreed(
                    widgetWidthDp, heightDp, headlineStyle.lines, textWidthDp(fearGreedText, 11f, bold = false, tabular = true)
                )
                views.setViewVisibility(R.id.pulse_fng, if (showFng) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_fng, if (showFng) fearGreedText else "")
                views.setTextColor(R.id.pulse_fng, colors.secondaryTextColor)

                // Nur so viele Chips, wie ganz in die Breite passen (nie ein abgeschnittener)
                val allCoins = PulseWidgetMath.coins(report, 3)
                val chipWidths = allCoins.map { textWidthDp(PulseWidgetMath.chipText(it), 11f, tabular = true) }
                val coins = allCoins.take(PulseWidgetMath.coinCount(widgetWidthDp, chipWidths))
                PULSE_CHIPS.forEachIndexed { index, (box, bg, text) ->
                    val coin = coins.getOrNull(index)
                    if (coin == null) {
                        views.setViewVisibility(box, View.GONE)
                        return@forEachIndexed
                    }
                    val color = when {
                        PulseWidgetMath.isFlat(coin.change) -> colors.secondaryTextColor
                        coin.change > 0 -> colors.upColor
                        else -> colors.downColor
                    }
                    views.setViewVisibility(box, View.VISIBLE)
                    views.setTextViewText(text, PulseWidgetMath.chipText(coin))
                    views.setTextColor(text, color)
                    pill(views, bg, color, dark, highContrast)
                }

                val time = PriceFormat.time(report.time).takeIf { it != "—" }
                val stand = time?.let { context.getString(R.string.pulse_updated, it) }
                val showTime = stand != null && PulseWidgetMath.showsTime(heightDp)
                views.setViewVisibility(R.id.pulse_time, if (showTime) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.pulse_time, stand.orEmpty())
                views.setTextColor(R.id.pulse_time, colors.secondaryTextColor)

                // «Was gerade auffällt. Breite Stärke. Bitcoin führt den Markt an. Bitcoin gestiegen um 2.8%, …»
                val spokenCoins = coins.joinToString(", ") { coin ->
                    coin.name + " " + A11yText.change(context, PulseWidgetMath.spokenChange(coin.change), decimals = 1)
                }
                views.setContentDescription(
                    R.id.pulse_root,
                    listOfNotNull(
                        "$title. $headline. $lead",
                        "$spokenCoins.",
                        fearGreedText?.takeIf { showFng }?.let { "$it." },
                        stand,
                    ).joinToString(" ")
                )
            }

            runCatching { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Pulse-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
    }

    /** Öffnet die App im Markt-Tab (Ziel «cycle» wie die App-Verknüpfung). */
    private fun openMarket(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent
            action = ACTION_OPEN_MARKET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, "cycle")
        }
        return PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Pillen-Hintergrund in der Kursfarbe, schwach deckend (bei hohem Kontrast kräftiger). */
    private fun pill(views: RemoteViews, id: Int, color: Int, dark: Boolean, highContrast: Boolean) {
        views.setInt(id, "setColorFilter", color or 0xFF000000.toInt())
        val alpha = when {
            highContrast -> if (dark) 0.22f else 0.12f
            dark -> 0.14f
            else -> 0.07f
        }
        views.setInt(id, "setImageAlpha", (alpha * 255).toInt())
    }

    /** Wertverlauf über höchstens rund einen Tag? (Dann «24h», sonst «48h».) */
    private fun spanAtMostDay(points: List<PortfolioValuePoint>): Boolean =
        points.last().time - points.first().time <= DAY_SPAN_MILLIS

    /**
     * Wertverlauf als Bitmap in der Grösse der Bildfläche ([wDp] × [hDp], aus
     * [PortfolioWidgetMath.chartSizeDp]); ohne Angaben eine feste Grösse. Gleiche Punkte,
     * Farben und Grösse ergeben dasselbe Bild — dann aus [portfolioCharts] statt neu gezeichnet.
     */
    private fun drawPortfolioChart(
        appWidgetId: Int,
        slot: Int,
        points: List<PortfolioValuePoint>,
        color: Int,
        baselineColor: Int,
        wDp: Float,
        hDp: Float,
    ): android.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (if (wDp > 0f) wDp else DEFAULT_CHART_WIDTH_DP).coerceAtLeast(40f)
        val h = (if (hDp > 0f) hDp else DEFAULT_CHART_HEIGHT_DP).coerceAtLeast(PortfolioWidgetMath.CHART_MIN_HEIGHT_DP)
        var scale = density
        val pixels = w * scale * h * scale
        if (pixels > MAX_CHART_PIXELS) scale *= kotlin.math.sqrt(MAX_CHART_PIXELS / pixels)
        val widthPx = (w * scale).toInt()
        val heightPx = (h * scale).toInt()
        val key = PortfolioChartKey(points, color, baselineColor, widthPx, heightPx, scale)
        val cacheId = appWidgetId * MAX_PORTFOLIO_SIZES + slot
        portfolioCharts.get(cacheId, key)?.let { return it }
        return WidgetChartRenderer.drawPortfolioArea(
            points = points,
            color = color,
            baselineColor = baselineColor,
            widthPx = widthPx,
            heightPx = heightPx,
            density = scale,
        ).also { portfolioCharts.put(cacheId, key, it) }
    }

    /** Gelöschte Portfolio-Widgets: zwischengespeicherte Charts aller Grössen freigeben. */
    fun forgetPortfolioWidgets(appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> repeat(MAX_PORTFOLIO_SIZES) { portfolioCharts.remove(id * MAX_PORTFOLIO_SIZES + it) } }
    }

    /**
     * Widget-Grösse in dp aus den Widget-Optionen wie beim Einzel-Widget
     * (Hochformat: minWidth × maxHeight, Querformat: maxWidth × minHeight); 0 = unbekannt.
     */
    private fun widgetSizeDp(manager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull() ?: return 0 to 0
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
        )
        val height = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
        )
        return width to height
    }

    /** Öffnet die App im Portfolio-Tab (falls eingeschaltet, sonst die Merkliste). */
    private fun openPortfolio(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent — ohne sie
            // teilte er sich Request-Code 2 mit der Mitteilung von Paar-Id 2 (AppNotifier)
            // und FLAG_UPDATE_CURRENT überschriebe gegenseitig das Ziel.
            action = ACTION_OPEN_PORTFOLIO
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, "portfolio")
        }
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    suspend fun update(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return

        // Akzentfarbe wie in der App; Hell/Dunkel je Widget (System/Dunkel/Hell).
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast

        // Liegt die letzte erfolgreiche Aktualisierung zu weit zurück: «veraltet · 06:42» statt der Uhrzeit
        val lastRefresh = refreshStats.lastRefresh()
        val outdated = OutdatedRule.isOutdated(lastRefresh, System.currentTimeMillis(), WidgetOutdated.afterMillis(settings))

        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            runCatching {
                manager.updateAppWidget(appWidgetId, render(manager, appWidgetId, colors, accent.logoRes(dark), lastRefresh, outdated))
            }.onFailure { Timber.w(it, "Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }

        // Fordert die Liste an, ihre Daten neu zu laden.
        // Seit API 31 zugunsten von RemoteCollectionItems abgekündigt; das
        // würde den RemoteViewsService ersetzen und minSdk 31 verlangen.
        @Suppress("DEPRECATION")
        runCatching { manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list) }
            .onFailure { Timber.w(it, "Widget-Liste konnte nicht aktualisiert werden") }
        scheduleOutdatedCheck()
    }

    private fun render(
        manager: AppWidgetManager,
        appWidgetId: Int,
        background: WidgetColors,
        logoRes: Int,
        lastRefresh: Long,
        outdated: Boolean,
    ): RemoteViews {
        val opacity = widgetPrefs.getOpacity(appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_list)

        background(views, R.id.widget_bg, background, opacity)
        // Kopf: Logo · «Merkliste» links, rechts «Uhrzeit · Dauer» und der Aktualisieren-Knopf.
        // Knopf neutral: weiss im Dunkeln, schwarz im Hellen — nicht in der Akzentfarbe
        views.setInt(R.id.widget_refresh, "setColorFilter", background.textColor)
        views.setImageViewResource(R.id.widget_logo, logoRes)

        // Uhrzeit zuerst: Nur daran erkennt man, ob die Daten alt sind. Nie abgeschnitten:
        // erst fällt die Dauer weg, dann wird der Titel kleiner, dann fällt die Uhrzeit weg.
        // Veraltet: «veraltet · 06:42» ohne Dauer; eng nur «veraltet» (das Wort bleibt).
        val title = context.getString(R.string.tab_watchlist)
        val header = ListWidgetHeader.layout(
            widthDp = widgetSizeDp(manager, appWidgetId).first,
            time = if (outdated) WidgetOutdated.label(context, lastRefresh) else PriceFormat.time(lastRefresh).takeIf { it != "—" },
            duration = if (outdated) null else PriceFormat.duration(refreshStats.lastDuration()),
            titleWidthDp = { textWidthDp(title, it) },
            statusWidthDp = { textWidthDp(it, 11f, bold = false, tabular = true) },
            fallback = if (outdated) context.getString(R.string.widget_outdated) else null,
        )
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, header.titleSp)
        views.setTextColor(R.id.widget_title, background.textColor)
        views.setTextViewText(R.id.widget_duration, header.status)
        views.setTextColor(R.id.widget_duration, background.secondaryTextColor)
        // Screenreader: «veraltet, letzte Aktualisierung 06:42» statt der Kurzform
        views.setContentDescription(
            R.id.widget_duration,
            if (outdated && header.status.isNotEmpty()) WidgetOutdated.spoken(context, lastRefresh) else null
        )
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty))
        views.setTextColor(R.id.widget_empty, background.secondaryTextColor)

        val adapterIntent = Intent(context, PriceWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Ohne eigene data-Uri teilen sich mehrere Widgets eine Fabrik.
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.widget_list, adapterIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        views.setPendingIntentTemplate(R.id.widget_list, openAppTemplate())
        // Der Kopf (Logo, Titel, Uhrzeit) öffnet die App
        views.setOnClickPendingIntent(R.id.widget_header, openApp())
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(appWidgetId))

        return views
    }

    /** Vorlage für die Zeilen; die Zeile ergänzt nur noch die Id ihres Paares. */
    private fun openAppTemplate(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: sonst gleiche Identität wie die allgemeine Mitteilung (Request-Code 0)
            action = ACTION_OPEN_WIDGET_ROW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: sonst gleiche Identität wie die Mitteilung von Paar-Id 1 (Request-Code 1)
            action = ACTION_OPEN_WIDGET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshIntent(appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PriceWidgetProvider::class.java).apply {
            action = PriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    /** Wovon das Bild des Portfolio-Wertverlaufs abhängt (siehe [portfolioCharts]). */
    private data class PortfolioChartKey(
        val points: List<PortfolioValuePoint>,
        val color: Int,
        val baselineColor: Int,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
    )

    private companion object {
        /** Bildfläche ohne Widget-Optionen (dp), etwa die bisherige Mindestgrösse. */
        const val DEFAULT_CHART_WIDTH_DP = 160f
        const val DEFAULT_CHART_HEIGHT_DP = 56f
        const val MAX_CHART_PIXELS = 1_000_000f
        /** Höchstens so lange auf die Kerzen eines Widgets warten (ganze Ausweich-Kette). */
        const val SPARKLINE_TIMEOUT_MILLIS = 20_000L
        /** Bis zu dieser Spanne heisst der Wertverlauf «24h», darüber «48h». */
        const val DAY_SPAN_MILLIS = 25 * 3_600_000L
        const val USDT = "USDT"
        /** Höchstens so viele Grössen je Portfolio-Widget (Hoch-/Querformat, Faltgeräte). */
        const val MAX_PORTFOLIO_SIZES = 4
        /** Deckkraft von Spur und Füllung des Anteil-Balkens (0–255). */
        const val SHARE_TRACK_ALPHA = 46
        const val SHARE_BAR_ALPHA = 170
        const val ACTION_OPEN_PORTFOLIO = "com.cryptochecker.app.action.OPEN_PORTFOLIO"
        const val ACTION_OPEN_MARKET = "com.cryptochecker.app.action.OPEN_MARKET"
        const val ACTION_OPEN_WIDGET = "com.cryptochecker.app.action.OPEN_FROM_WIDGET"
        const val ACTION_OPEN_WIDGET_ROW = "com.cryptochecker.app.action.OPEN_FROM_WIDGET_ROW"
    }
}
