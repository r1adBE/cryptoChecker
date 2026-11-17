package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.BidiText
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import com.cryptochecker.app.domain.widget.WidgetFreshness
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.domain.widget.WidgetTextFit
import com.cryptochecker.app.domain.widget.WidgetType
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.resources.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

// Einzel-Widget mit gewähltem Paar ([SingleWidgetRenderer.update]). Eigene Datei, weil der Renderer sonst zu lang würde.

/** Einzel-Widget mit gewähltem Paar ([SingleWidgetRenderer.update]): Texte, Chart und der Satz für den Screenreader. */
internal suspend fun SingleWidgetRenderer.drawSingleWatch(
    views: RemoteViews,
    watch: WatchEntity,
    colors: WidgetColors,
    manager: AppWidgetManager,
    appWidgetId: Int,
    now: Long,
    outdatedAfter: Long,
    changeView: ChangeView,
    online: Boolean,
    fetch: Boolean,
    highContrast: Boolean,
    toolkit: WidgetToolkit,
    widgetPrefs: WidgetPrefs,
    defaultDispatcher: CoroutineDispatcher,
) {
    // Deaktiviertes Paar: grau mit dem letzten Kurs, «Binance · deaktiviert», kein Chart (keine Abfrage)
    val off = watch.disabled
    // Börse stillgelegt (ExchangeRetirement): grau ohne Kurs, «Bitpanda liefert keine Kurse mehr»
    val retired = ExchangeRetirement.exchangeOf(watch.lastError)
    val grey = off || retired != null
    val mainText = if (grey) colors.secondaryTextColor else colors.textColor
    views.setTextColor(R.id.single_pair, mainText)
    views.setTextViewText(
        R.id.single_exchange,
        when {
            retired != null -> context.getString(R.string.exchange_retired_short, BidiText.isolate(retired))
            off -> context.getString(R.string.watchlist_row_disabled, BidiText.isolate(watch.exchangeName))
            else -> watch.exchangeName
        }
    )
    val shownPrice = PriceFormat.priceWithCurrency(watch.lastPrice.takeIf { retired == null }, watch.quoteAsset)
    views.setTextColor(R.id.single_exchange, colors.secondaryTextColor)
    // Währung klein und grau (Verhältnis zur grössten Kursgrösse 28 sp = [WidgetType.DISPLAY_COMPACT], widget_single.xml; autoSize skaliert mit)
    views.setAmountText(
        R.id.single_price,
        toolkit.quoteText(shownPrice, watch.quoteAsset, WidgetType.DISPLAY_COMPACT, colors.secondaryTextColor)
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
        (if (day.isEmpty()) 0f else toolkit.textWidthDp(" $day", WidgetType.LABEL * SingleWidgetRenderer.PERIOD_SCALE, tabular = true))
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
    val outdatedAt = if (grey) null else WidgetFreshness.outdatedTime(watch, now, outdatedAfter)
    val outdated = outdatedAt != null
    // Börse ohne eigene Zeit, Kurs steht lange: «Kurs unverändert seit 14:05» statt frischer Uhrzeit
    // (wie die Zeile der App); sonst Zeit laut Börse bzw. letzter Abruf (WidgetFreshness)
    val unchangedSince = if (grey) null else WidgetFreshness.unchangedSince(watch, now, outdatedAfter)
    val offline = WidgetStatusRule.kind(online, outdated, watch.lastUpdate).let {
        it == WidgetStatusRule.Kind.OFFLINE || it == WidgetStatusRule.Kind.OFFLINE_NO_TIME
    }
    val timeText = when {
        offline -> WidgetOffline.label(context, watch.lastUpdate)
        outdatedAt != null -> WidgetOutdated.label(context, outdatedAt)
        unchangedSince != null -> WidgetOutdated.unchanged(context, unchangedSince)
        else -> PriceFormat.time(WidgetFreshness.priceTime(watch, now, outdatedAfter))
    }
    views.setTextViewText(
        R.id.single_time,
        timeText + " · " + context.getString(range.shortLabelRes)
    )
    views.setTextColor(R.id.single_time, colors.secondaryTextColor)

    val (chartDescription, foreignChart) = drawSingleChart(
        views = views,
        watch = watch,
        range = range,
        off = off,
        fetch = fetch,
        manager = manager,
        appWidgetId = appWidgetId,
        colors = colors,
        highContrast = highContrast,
        changeView = changeView,
        widgetPrefs = widgetPrefs,
        defaultDispatcher = defaultDispatcher,
    )
    // Das ganze Widget als ein Satz: Paar, Kurs, Änderung, Verlauf, Uhrzeit
    views.setContentDescription(
        R.id.single_root,
        A11yText.row(
            context = context,
            pair = watch.displayName,
            exchange = watch.exchangeName,
            price = shownPrice,
            quote = watch.quoteAsset,
            change24h = change,
            basis = changeView.basis,
            // Frische direkt nach der Veränderung (wie die Merkliste)
            state = listOf(
                if (off) context.getString(R.string.watch_disabled) else null,
                retired?.let { context.getString(R.string.exchange_retired_short, it) },
                if (offline) WidgetOffline.label(context, watch.lastUpdate) else null,
                outdatedAt?.let { WidgetOutdated.spoken(context, it) },
                unchangedSince?.let { WidgetOutdated.unchanged(context, it) },
            ),
            extras = listOf(
                chartDescription,
                foreignChart,
                if (offline || outdated || unchangedSince != null) {
                    null
                } else {
                    PriceFormat.time(WidgetFreshness.priceTime(watch, now, outdatedAfter)).takeIf { it != "—" }
                },
            ),
        )
    )
}

/** Chart des Einzel-Widgets; liefert den Satz zum Verlauf und «Chart: Binance» (beide null ohne Chart bzw. eigene Börse). */
private suspend fun SingleWidgetRenderer.drawSingleChart(
    views: RemoteViews,
    watch: WatchEntity,
    range: WidgetChartRange,
    off: Boolean,
    fetch: Boolean,
    manager: AppWidgetManager,
    appWidgetId: Int,
    colors: WidgetColors,
    highContrast: Boolean,
    changeView: ChangeView,
    widgetPrefs: WidgetPrefs,
    defaultDispatcher: CoroutineDispatcher,
): Pair<String?, String?> {
    val chartType = widgetPrefs.getChartType(appWidgetId)
    // Nur Kerzen desselben Tokens (Kurs des Paars ±25 %), sonst kein Chart
    val sourced = if (watch.isNotTraded || off) {
        null
    } else {
        chartCandles(watch, range, fetch)
    }
    val candles = sourced?.value
    val chartProvider = sourced?.provider
    // Screenreader: Verlauf als Satz (Zeitraum, Start, Ende, Änderung, Hoch, Tief);
    // Kerzen: erste Eröffnung, letzter Schluss, höchstes Hoch, tiefstes Tief
    val chartDescription = candles?.takeIf { it.size >= 2 }?.let {
        A11yText.chart(context, context.getString(range.labelRes), WidgetChartGeometry.summary(it, chartType))
    }
    // Zeichnen nicht auf dem Main-Thread (Aufrufe auch aus App und ViewModel)
    val chartBitmap = candles?.takeIf { it.size >= 2 }?.let {
        withContext(defaultDispatcher) {
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
    val foreignChart = if (chartBitmap == null) {
        null
    } else {
        com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(
            listOf(watch.exchangeKey, watch.exchangeName),
            chartProvider,
        )
            ?.let { context.getString(R.string.watch_row_source_chart, BidiText.isolate(it)) }
    }
    if (foreignChart != null) {
        views.setTextViewText(R.id.single_exchange, watch.exchangeName + " · " + foreignChart)
    }
    if (chartBitmap != null) {
        views.setImageViewBitmap(R.id.single_chart, chartBitmap)
        views.setViewVisibility(R.id.single_chart, android.view.View.VISIBLE)
        views.setContentDescription(R.id.single_chart, listOfNotNull(chartDescription, foreignChart).joinToString(", "))
    } else {
        views.setViewVisibility(R.id.single_chart, android.view.View.INVISIBLE)
    }
    return chartDescription to foreignChart
}
