// Ansicht «Chart» des Portfolio-Widgets (Teil von `PortfolioWidgetRenderer`): Layout je Grösse, Bild des
// Wertverlaufs (mit Zwischenspeicher je Widget und Grösse) und die Grössen des Widgets.
// Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein soll.
package com.cryptochecker.app.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSize
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Ansicht «Chart»: ein Portfolio-Widget in der Grösse [widthDp] × [heightDp] (0 = unbekannt) aus
 * der gezeigten Aufnahme [snapshot] (schon in der Anzeige-Einheit, [PortfolioWidgetChart.displayed])
 * und dem Zeitraum [plan]. [slot] trennt die Chart-Bilder der Grössen eines Widgets im Zwischenspeicher.
 */
internal suspend fun PortfolioWidgetRenderer.portfolioViews(
    appWidgetId: Int,
    slot: Int,
    widthDp: Int,
    heightDp: Int,
    snapshot: PortfolioSnapshot,
    plan: PortfolioChartPlan,
    style: PortfolioStyle,
    outdated: Boolean,
    fontScale: Float,
    wantUsdt: Boolean,
): RemoteViews {
    val colors = style.colors
    // «ohne 1 Coin» / «Heute ohne SOL»: Hinweis in der Zeile unter dem Betrag (gleicher Platz wie «≈ … USDT»),
    // passt sie nicht, in der Fusszeile
    val missing = missingText(snapshot, plan, overview = false)
    val layout = PortfolioWidgetMath.layout(
        widthDp = widthDp,
        heightDp = heightDp,
        fontScale = fontScale,
        hasChange = plan.changeAmount != null,
        wantUsdt = wantUsdt || missing != null,
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

    // Richtung nach dem Vorzeichen des Betrags (nie nach dem Farbtausch): Farbe UND Vorzeichen
    val direction = PortfolioWidgetChart.direction(plan)
    val priceColor = directionColor(direction, colors)
    val title = context.getString(R.string.widget_portfolio_name)

    val textWidth = when {
        widthDp <= 0 -> null
        layout.size == PortfolioWidgetSize.MEDIUM -> (widthDp - 24f - 12f) / 2f
        else -> widthDp - 24f
    }
    val values = bindValues(views, snapshot, plan, style, priceColor, textWidth, layout.changeLine, layout.usdt, wantUsdt, missing)

    // Chart wie in der App (Kerzen oder Linie, Hilfslinien, «Hoch …»); ohne genug Werte ruhig
    // «Verlauf folgt». 24 h mit Tages-Basis: feste Achse vom Tagesbeginn bis Tagesende
    val drawable = layout.chart && plan.hasChart
    val chartBasis = snapshot.stamp?.basis ?: ChangeBasis.ROLLING_24H
    val periodShort = if (drawable) {
        when {
            plan.range != PortfolioChartRange.DAY -> rangeShort(plan.range)
            chartBasis.isDay -> ChangeBasisText.shortLabel(context, chartBasis)
            spanAtMostDay(plan.bars) -> context.getString(R.string.widget_range_short_24h)
            else -> context.getString(R.string.widget_portfolio_range_short_48h)
        }
    } else null
    // Gewählter Zeitraum ohne Tageskurse: 24 h als Linie, dazu «7T folgt» in der Fusszeile
    val fallbackHint = fallbackHint(plan)
    val chartDescription = if (drawable) {
        val periodLong = when {
            plan.range != PortfolioChartRange.DAY -> rangeLong(plan.range)
            chartBasis.isDay -> ChangeBasisText.longLabel(context, chartBasis)
            spanAtMostDay(plan.bars) -> context.getString(R.string.widget_range_24h)
            else -> context.getString(R.string.widget_portfolio_range_48h)
        }
        val hiddenSpoken = context.getString(R.string.a11y_amount_hidden)
        A11yText.chart(context, periodLong, plan.bars.map { it.close }) {
            if (style.hideAmounts) hiddenSpoken else PriceFormat.valueWithCurrency(it, snapshot.currency)
        }
    } else null
    if (layout.chart) {
        views.setViewVisibility(R.id.portfolio_chart_box, View.VISIBLE)
        val chartUp = direction?.let { it >= 0 } ?: (plan.bars.isNotEmpty() && plan.bars.last().close >= plan.bars.first().open)
        val chartColor = if (chartUp) colors.upColor else colors.downColor
        val (chartW, chartH) = PortfolioWidgetMath.chartSizeDp(layout, widthDp, heightDp, fontScale)
        val bitmap = if (drawable) {
            withContext(Dispatchers.Default) {
                runCatching { drawPortfolioChart(appWidgetId, slot, plan, chartColor, style, chartW, chartH, fontScale) }
                    .onFailure { Timber.w(it, "Chart für Portfolio-Widget %d nicht gezeichnet", appWidgetId) }
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

    // Gross: die drei grössten Positionen mit MEINEM Gewinn/Verlust je Coin nach der %-Basis; Platz rechts
    // neben Kürzel (min. 3 em), Balken (min. ~24 dp samt Rändern 16 dp) und Anteil (min. 3 em)
    val positionsText = if (layout.size == PortfolioWidgetSize.LARGE) {
        val budget = textWidth?.let { it - 3 * 12f - 16f - 24f - 3 * 11f - 8f }
        PortfolioWidgetPositions.render(
            context, views, snapshot.positions.take(layout.rows), colors, style.basis, style.hideAmounts,
            pnlText = { change -> fitPnl(change, snapshot.currency, style, "", budget) },
            spokenPnl = { change -> spokenPnl(change, snapshot.currency, style, style.basis) },
        )
    } else null

    // «Stand 15:19 · 24h»; Hinweis ohne Platz unter dem Betrag: in die Fusszeile, vor der Zeitspanne
    val timeText = timeText(snapshot, outdated)
    val footerMissing = missing.takeIf { !values.missingInLine }
    bindFooter(
        views,
        layout.footer,
        textWidth,
        listOf(
            listOfNotNull(timeText, footerMissing, periodShort, fallbackHint).joinToString(" · "),
            listOfNotNull(timeText, footerMissing, periodShort).joinToString(" · "),
            listOfNotNull(timeText, footerMissing).joinToString(" · "),
            listOfNotNull(timeText, periodShort).joinToString(" · "),
        ),
        colors,
    )

    // Screenreader: «Portfolio 83’170.32 CHF, gesunken um 2.31% in 24 Stunden, Stand 15:19» —
    // dann ≈ USDT, Verlauf und Positionen (nur was sichtbar ist); veraltet immer
    views.setContentDescription(
        R.id.portfolio_root,
        listOfNotNull(
            spokenTotal(title, values.total, style),
            spokenChange(plan, snapshot, style),
            spokenTime(snapshot, outdated),
            values.usdt?.let { context.getString(R.string.a11y_converted, it) },
            missing,
            chartDescription,
            fallbackHint,
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
internal fun PortfolioWidgetRenderer.portfolioSizesDp(manager: AppWidgetManager, appWidgetId: Int): List<Pair<Int, Int>> {
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
    return listOf(toolkit.sizeDp(manager, appWidgetId))
}

/** Verlauf über höchstens rund einen Tag? (Dann «24h», sonst «48h».) */
internal fun PortfolioWidgetRenderer.spanAtMostDay(bars: List<ChartBar>): Boolean =
    bars.isEmpty() || bars.maxOf { it.end } - bars.first().start <= DAY_SPAN_MILLIS

/**
 * Chart als Bitmap in der Grösse der Bildfläche ([wDp] × [hDp], aus
 * [PortfolioWidgetMath.chartSizeDp]); ohne Angaben eine feste Grösse. Gleiche Balken, Farben
 * und Grösse ergeben dasselbe Bild — dann aus [portfolioCharts] statt neu gezeichnet.
 */
internal fun PortfolioWidgetRenderer.drawPortfolioChart(
    appWidgetId: Int,
    slot: Int,
    plan: PortfolioChartPlan,
    lineColor: Int,
    style: PortfolioStyle,
    wDp: Float,
    hDp: Float,
    fontScale: Float,
): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val w = (if (wDp > 0f) wDp else DEFAULT_CHART_WIDTH_DP).coerceAtLeast(40f)
    val h = (if (hDp > 0f) hDp else DEFAULT_CHART_HEIGHT_DP).coerceAtLeast(PortfolioWidgetMath.CHART_MIN_HEIGHT_DP)
    var scale = density
    val pixels = w * scale * h * scale
    if (pixels > MAX_CHART_PIXELS) scale *= kotlin.math.sqrt(MAX_CHART_PIXELS / pixels)
    val widthPx = (w * scale).toInt()
    val heightPx = (h * scale).toInt()
    val colors = style.colors
    val labelBacking = colors.colorWithOpacity(100)
    val key = PortfolioChartKey(
        plan = plan,
        lineColor = lineColor,
        palette = listOf(colors.upColor, colors.downColor, colors.textColor, colors.secondaryTextColor, labelBacking),
        highContrast = style.highContrast,
        hideAmounts = style.hideAmounts,
        widthPx = widthPx,
        heightPx = heightPx,
        density = scale,
        fontScale = fontScale,
    )
    val cacheId = appWidgetId * MAX_PORTFOLIO_SIZES + slot
    portfolioCharts.get(cacheId, key)?.let { return it }
    return WidgetChartRenderer.drawPortfolioChart(
        plan = plan,
        colors = colors,
        lineColor = lineColor,
        labelBacking = labelBacking,
        highContrast = style.highContrast,
        widthPx = widthPx,
        heightPx = heightPx,
        density = scale,
        fontScale = fontScale,
        highLabel = { value ->
            context.getString(
                R.string.portfolio_chart_high,
                PortfolioInsights.mask(PriceFormat.price(value), style.hideAmounts),
            )
        },
    ).also { portfolioCharts.put(cacheId, key, it) }
}

/** Bis zu dieser Spanne heisst der Wertverlauf «24h», darüber «48h». */
private const val DAY_SPAN_MILLIS = 25 * 3_600_000L
