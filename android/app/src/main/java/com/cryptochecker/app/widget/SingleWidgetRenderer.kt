package com.cryptochecker.app.widget

import androidx.core.net.toUri
import com.cryptochecker.app.data.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.data.BoundedCache
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einzel-Widget: ein Paar mit Kurs, Veränderung und Chart (Kerzen oder Linie; 24 h / 7 / 30 Tage).
 * Die Stundenkerzen dienen auch dem Wertverlauf des Portfolio-Widgets ([cachedHourlyPrices]).
 */
@Singleton
class SingleWidgetRenderer @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository,
    private val watchRepository: com.cryptochecker.app.data.WatchRepository,
    internal val candleDataSource: CandleDataSource,
    internal val ownCandles: com.cryptochecker.app.data.remote.candles.OwnCandleDataSource,
    internal val candlePairs: com.cryptochecker.app.data.remote.candles.CandlePairs,
    private val toolkit: WidgetToolkit,
    private val coinLogos: WidgetCoinLogos,
    internal val tokenIdentityStore: com.cryptochecker.app.data.TokenIdentityStore,
) {
    /**
     * Mini-Chart-Kerzen je Symbol und Zeitraum, 10 Minuten zwischengespeichert (für Kerzen und Linie).
     * Begrenzt (sonst blieb jedes je geprüfte Paar bis zum Prozessende).
     */
    internal val sparkCache = BoundedCache<String, Pair<Long, List<WidgetCandle>>>(MEMORY_CACHE_ENTRIES)

    /**
     * Gesetzt, wenn ein Zeichnen ohne Netz (`fetch = false`) Kerzen brauchte, die im Zwischenspeicher
     * fehlten oder älter als 10 Minuten waren: Dann lohnt das Nachladen ([takeStaleCharts]).
     */
    internal val staleCharts = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fehlten seit dem letzten Aufruf Kerzen beim Zeichnen ohne Netz? Setzt den Merker zurück. */
    fun takeStaleCharts(): Boolean = staleCharts.getAndSet(false)

    /** Börse der Kerzen aus der Ausweich-Kette je Schlüssel von [sparkCache] (für «Chart: Binance»). */
    internal val sparkProvider = BoundedCache<String, String>(MEMORY_CACHE_ENTRIES)

    /**
     * Zuletzt gezeichneter Chart je Einzel-Widget: Gleiche Kerzen, Darstellung, Farben, Grösse
     * und gleicher angezeigter Kurs ergeben dasselbe Bild — dann nicht neu zeichnen.
     * Nur das jeweils letzte Bild je Widget, beim Löschen entfernt ([forget]).
     */
    internal val chartBitmaps = LatestPerWidget<ChartBitmapKey, android.graphics.Bitmap>()

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
            // Coin-Logo statt App-Logo, sofern eingeschaltet und ein Paar gewählt ist
            if (settings.widgetCoinLogos && watch != null) {
                views.setImageViewBitmap(
                    R.id.single_coin_logo,
                    coinLogos.bitmap(coinLogos.logoKey(watch), colors, logo = CoinLogos.allowedFor(watch.marketKey))
                )
                views.setViewVisibility(R.id.single_coin_logo, View.VISIBLE)
                views.setViewVisibility(R.id.single_logo, View.GONE)
            } else {
                views.setViewVisibility(R.id.single_coin_logo, View.GONE)
                views.setViewVisibility(R.id.single_logo, View.VISIBLE)
            }
            // Noch ohne Paar (z. B. aus der App hinzugefügt, Einrichten nicht geöffnet): Tippen öffnet das Einrichten;
            // sonst das Paar in der Merkliste mit seinem Aktionsblatt (wie eine Zeile des Listen-Widgets, iOS `widgetURL`)
            views.setOnClickPendingIntent(R.id.single_root, if (watch == null) configureSingle(appWidgetId) else openPair(appWidgetId, watch.id))

            if (watch == null) {
                views.setTextViewText(R.id.single_pair, context.getString(R.string.single_widget_choose))
                views.setTextColor(R.id.single_pair, colors.textColor)
                views.setAmountText(R.id.single_price, "—")
                views.setTextColor(R.id.single_price, colors.textColor)
            } else {
                // Deaktiviertes Paar: grau mit dem letzten Kurs, «Binance · deaktiviert», kein Chart (keine Abfrage)
                val off = watch.disabled
                val mainText = if (off) colors.secondaryTextColor else colors.textColor
                views.setTextColor(R.id.single_pair, mainText)
                views.setTextViewText(
                    R.id.single_market,
                    if (off) context.getString(R.string.watchlist_row_disabled, BidiText.isolate(watch.marketName)) else watch.marketName
                )
                views.setTextColor(R.id.single_market, colors.secondaryTextColor)
                // Währung klein und grau (Verhältnis zur grössten Kursgrösse 28 sp = [WidgetType.DISPLAY_COMPACT], widget_single.xml; autoSize skaliert mit)
                views.setAmountText(
                    R.id.single_price,
                    toolkit.quoteText(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset), watch.quoteAsset, WidgetType.DISPLAY_COMPACT, colors.secondaryTextColor)
                )
                views.setTextColor(R.id.single_price, mainText)

                // Veränderung gemäss %-Basis als farbiger Text (keine Pille), mit «24h» / «heute» — der
                // Chart daneben kann einen anderen Zeitraum zeigen. Ohne Bezug «—», ebenso bei
                // nicht mehr gehandelten Paaren (dann auch kein Chart) und veralteter Basis.
                val change = watch.shownChange(changeView)
                val day = ChangeBasisText.shortLabel(context, changeView.basis)
                // Pfeil wie in der Merkliste (folgt dem Vorzeichen, nie dem Farbtausch)
                val valueText = when {
                    change == null -> "—"
                    else -> PriceFormat.changePercent(change)?.let { text ->
                        PriceFormat.changeArrow(change).let { if (it.isEmpty()) text else "$it $text" }
                    } ?: PriceFormat.zeroPercent()
                }
                // Zeitraum dahinter klein und grau (nie rot/grün), wie iOS `WidgetChangeLabel.suffix`
                views.setAmountText(R.id.single_change, periodSuffixed(valueText, day, colors.secondaryTextColor))
                // Paar ganz, wenn es neben der Veränderung Platz hat, sonst nur die Basis
                // («BTC» statt «BT…»); die Veränderung wird nie gekürzt
                val changeWidth = toolkit.textWidthDp(valueText, WidgetType.LABEL, tabular = true) +
                    (if (day.isEmpty()) 0f else toolkit.textWidthDp(" $day", WidgetType.LABEL * PERIOD_SCALE, tabular = true))
                val pairLabel = WidgetTextFit.singlePairLabel(
                    pair = watch.displayName,
                    base = watch.baseAsset,
                    widgetWidthDp = toolkit.sizeDp(manager, appWidgetId).first,
                    changeWidthDp = changeWidth,
                ) { toolkit.textWidthDp(it, 14f) }
                views.setTextViewText(R.id.single_pair, pairLabel)
                views.setTextColor(
                    R.id.single_change,
                    when {
                        off || change == null || PriceFormat.changePercent(change) == null -> colors.neutralColor
                        change >= 0 -> colors.upColor
                        else -> colors.downColor
                    }
                )
                // Zeitraum des Charts neben der Uhrzeit, z. B. «14:05 · 7T»; alter Stand
                // ausgeschrieben: «veraltet · 06:42 · 7T»
                val range = widgetPrefs.getChartRange(appWidgetId)
                // Deaktiviert ist nie «veraltet» (es wird bewusst nicht abgefragt)
                // Regel der App (Abruf zu alt oder letzter Handel > 2 h, [WidgetFreshness.outdatedTime])
                val outdatedAt = if (off) null else WidgetFreshness.outdatedTime(watch, now, outdatedAfter)
                val outdated = outdatedAt != null
                // Börse ohne eigene Zeit, Kurs steht lange: «Kurs unverändert seit 14:05» statt frischer Uhrzeit
                // (wie die Zeile der App); sonst Zeit laut Börse bzw. letzter Abruf (WidgetFreshness)
                val unchangedSince = if (off) null else WidgetFreshness.unchangedSince(watch, now, outdatedAfter)
                val timeText = when {
                    outdatedAt != null -> WidgetOutdated.label(context, outdatedAt)
                    unchangedSince != null -> WidgetOutdated.unchanged(context, unchangedSince)
                    else -> PriceFormat.time(WidgetFreshness.priceTime(watch, now, outdatedAfter))
                }
                views.setTextViewText(
                    R.id.single_time,
                    timeText + " · " + context.getString(range.shortLabelRes)
                )
                views.setTextColor(R.id.single_time, colors.secondaryTextColor)

                val chartType = widgetPrefs.getChartType(appWidgetId)
                // Nur Kerzen desselben Tokens (Kurs des Paars ±25 %), sonst kein Chart
                val sourced = if (watch.isNotTraded || off) null
                else chartCandles(watch, range, fetch)
                val candles = sourced?.value
                val chartProvider = sourced?.provider
                // Screenreader: Verlauf als Satz (Zeitraum, Start, Ende, Änderung, Hoch, Tief);
                // Kerzen: erste Eröffnung, letzter Schluss, höchstes Hoch, tiefstes Tief
                val chartDescription = candles?.takeIf { it.size >= 2 }?.let {
                    A11yText.chart(context, context.getString(range.labelRes), WidgetChartGeometry.summary(it, chartType))
                }
                // Zeichnen nicht auf dem Main-Thread (Aufrufe auch aus App und ViewModel)
                val chartBitmap = candles?.takeIf { it.size >= 2 }?.let {
                    withContext(Dispatchers.Default) {
                        runCatchingCancellable {
                            drawChart(
                                manager, appWidgetId, it, chartType, range, colors, highContrast, watch.lastPrice,
                                ChangeBasisMath.chartZone(changeView.basis),
                                // Stablecoin: Mindesthöhe der Achse 1 % wie im Aktionsblatt
                                stable = PortfolioStables.isStable(watch.baseAsset),
                            )
                        }
                            .onFailure { e -> Timber.w(e, "Chart für Widget %d nicht gezeichnet", appWidgetId) }
                            .getOrNull()
                    }
                }
                // Kerzen einer anderen Börse (Ausweich-Kette): in der Börsen-Zeile nennen, «Kraken · Chart: Binance»
                val foreignChart = if (chartBitmap == null) null
                else com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(
                    listOf(watch.marketKey, watch.marketName), chartProvider,
                )
                    ?.let { context.getString(R.string.watch_row_source_chart, BidiText.isolate(it)) }
                if (foreignChart != null) {
                    views.setTextViewText(R.id.single_market, watch.marketName + " · " + foreignChart)
                }
                if (chartBitmap != null) {
                    views.setImageViewBitmap(R.id.single_chart, chartBitmap)
                    views.setViewVisibility(R.id.single_chart, android.view.View.VISIBLE)
                    views.setContentDescription(R.id.single_chart, listOfNotNull(chartDescription, foreignChart).joinToString(", "))
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
                        quote = watch.quoteAsset,
                        change24h = change,
                        basis = changeView.basis,
                        // Frische direkt nach der Veränderung (wie die Merkliste)
                        state = listOf(
                            if (off) context.getString(R.string.watch_disabled) else null,
                            outdatedAt?.let { WidgetOutdated.spoken(context, it) },
                            unchangedSince?.let { WidgetOutdated.unchanged(context, it) },
                        ),
                        extras = listOf(
                            chartDescription,
                            foreignChart,
                            if (outdated || unchangedSince != null) null
                            else PriceFormat.time(WidgetFreshness.priceTime(watch, now, outdatedAfter)).takeIf { it != "—" },
                        ),
                    )
                )
            }

            runCatchingCancellable { manager.updateAppWidget(appWidgetId, views) }
                .onFailure { Timber.w(it, "Einzel-Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }
        return true
    }

    /** Öffnet die App beim Paar [watchId] ([MainActivity.EXTRA_WATCH_ID]); eigene data-Uri je Widget. */
    private fun openPair(appWidgetId: Int, watchId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SINGLE_WIDGET
            data = "cryptochecker://widget/single/open/$appWidgetId".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
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

    /**
     * «↘ −0.30% 24h»: Wert in der Farbe der Zeile, [period] dahinter verkleinert ([PERIOD_SCALE]) und
     * grau [grey] — der Zeitraum ist nie rot/grün. Ohne Zeitraum nur der Wert.
     */
    private fun periodSuffixed(value: String, period: String, grey: Int): CharSequence {
        if (period.isEmpty()) return value
        val text = "$value $period"
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.RelativeSizeSpan(PERIOD_SCALE), value.length, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(grey), value.length, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        /** Zeitraum hinter der Veränderung: 80 % der Schrift (wie iOS `WidgetChangeLabel.suffix`). */
        const val PERIOD_SCALE = 0.8f

        /** Eigene Action: sonst gleiche Identität wie die allgemeine «App öffnen» des Widgets. */
        const val ACTION_OPEN_SINGLE_WIDGET = "com.cryptochecker.app.action.OPEN_FROM_SINGLE_WIDGET"
    }
}
