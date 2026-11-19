// Ansicht «Übersicht» des Portfolio-Widgets (Teil von `PortfolioWidgetRenderer`): Gesamtwert, Kennzahlen
// (Heute · Zeitraum · Gesamt) und hoch die grössten Positionen. Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein soll
// (wie `PortfolioWidgetOverview.swift`).
package com.cryptochecker.app.widget

import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.resources.R

/**
 * Ansicht «Übersicht» (wie die Gesamtkarte im Portfolio-Tab): Kopfzeile, Gesamtwert,
 * Betrag und Prozent über den Zeitraum, ≈ USDT, Heute · 7 T · Gesamt ([PortfolioWidgetViews.figures]), hoch die
 * grössten Positionen ([PortfolioWidgetViews.overviewRows], Zeilen wie in der Liste) und Fusszeile — was in die Höhe
 * passt ([PortfolioWidgetViews.overviewLayout]).
 * @param logos Coin-Logo je Kürzel (Grossschreibung); null = Schalter «Coin-Logos in Widgets» aus
 */
fun PortfolioWidgetRenderer.overviewViews(
    widthDp: Int,
    heightDp: Int,
    snapshot: PortfolioSnapshot,
    plan: PortfolioChartPlan,
    style: PortfolioStyle,
    outdated: Boolean,
    fontScale: Float,
    wantUsdt: Boolean,
    logos: Map<String, android.graphics.Bitmap>?,
): RemoteViews {
    val colors = style.colors
    val missing = missingText(snapshot, plan, overview = true)
    val layout = PortfolioWidgetViews.overviewLayout(widthDp, heightDp, fontScale, plan.changeAmount != null, wantUsdt || missing != null)
    val views = RemoteViews(context.packageName, R.layout.widget_portfolio_overview)
    portfolioFrame(views, style)
    val priceColor = directionColor(PortfolioWidgetChart.direction(plan), colors)
    val title = context.getString(R.string.widget_portfolio_name)
    val textWidth = if (widthDp > 0) widthDp - 24f else null
    val values = bindValues(views, snapshot, plan, style, priceColor, textWidth, layout.changeLine, layout.usdt, wantUsdt, missing)
    val figuresText = bindFigures(views, PortfolioWidgetViews.figures(snapshot), layout, snapshot.currency, style)
    val topRows = PortfolioWidgetViews.overviewRows(snapshot.positions, layout, fontScale)
    val rowsText = bindOverviewRows(views, topRows, layout, snapshot, style, logos, textWidth)

    // Stand steht in der Kopfzeile (WidgetRefreshHeader); unten nur noch Hinweise
    val footerMissing = missing.takeIf { !values.missingInLine }
    val fallbackHint = fallbackHint(plan)
    bindFooter(
        views,
        layout.footer,
        textWidth,
        listOf(
            listOfNotNull(footerMissing, fallbackHint).joinToString(" · "),
            footerMissing.orEmpty(),
        ),
        colors,
    )
    views.setContentDescription(
        R.id.portfolio_root,
        listOfNotNull(
            spokenTotal(title, values.total, style),
            spokenChange(plan, snapshot, style),
            spokenTime(snapshot, outdated),
            values.usdt?.let { context.getString(R.string.a11y_converted, it) },
            missing,
            fallbackHint,
            figuresText,
            rowsText,
        ).joinToString(", ")
    )
    return views
}

/**
 * Kennzahlen der Übersicht: breit drei Spalten (Beschriftung, Wert, Betrag falls Platz), schmal drei
 * Zeilen (Beschriftung, Wert rechtsbündig). Wert «↗ +1.50%» als Text (keine Pille) in der Kursfarbe
 * nach dem Vorzeichen (Betrag, sonst Prozent; 0 grau ohne Pfeil), ohne Wert grau «—».
 * @return Satz für den Screenreader oder null ohne Kennzahlen
 */
fun PortfolioWidgetRenderer.bindFigures(
    views: RemoteViews,
    figures: List<PortfolioFigure>,
    layout: PortfolioOverviewLayout,
    currency: String,
    style: PortfolioStyle,
): String? {
    val colors = style.colors
    views.removeAllViews(R.id.portfolio_figures_wide)
    views.removeAllViews(R.id.portfolio_figures_narrow)
    val container = if (layout.wide) R.id.portfolio_figures_wide else R.id.portfolio_figures_narrow
    views.setViewVisibility(R.id.portfolio_figures_wide, if (layout.figures && layout.wide) View.VISIBLE else View.GONE)
    views.setViewVisibility(R.id.portfolio_figures_narrow, if (layout.figures && !layout.wide) View.VISIBLE else View.GONE)
    if (!layout.figures) return null
    val spoken = figures.map { figure ->
        val item = RemoteViews(
            context.packageName,
            if (layout.wide) R.layout.widget_portfolio_figure_column else R.layout.widget_portfolio_figure_row
        )
        // «Gesamt [CHF]» bzw. «Gesamt [USDT]»: Währung der gezeigten Beträge (Anzeige des Widgets)
        val label = when (figure.kind) {
            PortfolioFigureKind.TODAY -> context.getString(R.string.portfolio_card_today)
            PortfolioFigureKind.WEEK -> context.getString(R.string.portfolio_history_range_7d)
            PortfolioFigureKind.TOTAL -> context.getString(R.string.ledger_since_purchase_currency, currency)
        }
        val spokenLabel = context.getString(
            when (figure.kind) {
                PortfolioFigureKind.TODAY -> R.string.portfolio_card_today
                PortfolioFigureKind.WEEK -> R.string.portfolio_history_period_7d
                PortfolioFigureKind.TOTAL -> R.string.ledger_since_purchase_spoken
            }
        )
        item.setTextViewText(R.id.fig_label, label)
        item.setTextColor(R.id.fig_label, colors.secondaryTextColor)
        val percent = figure.percent
        // Einheit des Betrags: «7 T» ohne Tageskurse der Anzeigewährung in USDT (nie USDT × heutiger Kurs)
        val unit = figure.unit ?: currency
        if (percent != null) {
            val direction = PortfolioWidgetSeries.direction(figure.amount ?: percent)
            // Ein Coin fehlt (kein Devisenkurs am Kauftag, seither gekauft/verkauft): «*» wie «unvollständig» der Karte
            val text = PortfolioWidgetViews.figureText(direction, PortfolioSnapshotMath.signedPercent(percent)) +
                if (figure.incomplete) "*" else ""
            item.setAmountText(R.id.fig_value, text)
            item.setTextColor(R.id.fig_value, directionColor(direction, colors))
        } else {
            item.setAmountText(R.id.fig_value, NO_VALUE)
            item.setTextColor(R.id.fig_value, colors.secondaryTextColor)
        }
        val amount = figure.amount
        if (layout.wide && layout.figureAmounts) {
            val text = amount?.let { PortfolioInsights.mask(PortfolioSnapshotMath.signedAmount(it, unit), style.hideAmounts) } ?: NO_VALUE
            item.setViewVisibility(R.id.fig_amount, View.VISIBLE)
            item.setAmountText(R.id.fig_amount, toolkit.quoteText(text, unit, FIGURE_AMOUNT_SP, colors.secondaryTextColor))
            item.setTextColor(R.id.fig_amount, colors.textColor)
        } else {
            item.setViewVisibility(R.id.fig_amount, View.GONE)
        }
        views.addView(container, item)
        // «7 Tage: gestiegen um 2.10%, +123.45 CHF»; ohne Wert «7 Tage: noch nicht verfügbar»
        val spokenAmount = amount?.let {
            if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else PortfolioSnapshotMath.signedAmount(it, unit)
        }
        val change = percent?.let { A11yText.change(context, it) }
        if (change == null && spokenAmount == null) {
            context.getString(R.string.portfolio_card_figure_none_a11y, spokenLabel)
        } else {
            val incomplete = context.getString(R.string.portfolio_export_incomplete).takeIf { figure.incomplete }
            context.getString(R.string.label_value, spokenLabel, listOfNotNull(change, spokenAmount, incomplete).joinToString(", "))
        }
    }
    return spoken.joinToString("; ")
}

private const val FIGURE_AMOUNT_SP = 12f

/**
 * Grösste Positionen im freien Platz einer hohen Übersicht, Zeilen wie in der Ansicht «Liste» ([listRow]); ohne Platz
 * ausgeblendet.
 * @return Satz für den Screenreader oder null ohne Zeilen
 */
private fun PortfolioWidgetRenderer.bindOverviewRows(
    views: RemoteViews,
    shown: List<PortfolioPosition>,
    layout: PortfolioOverviewLayout,
    snapshot: PortfolioSnapshot,
    style: PortfolioStyle,
    logos: Map<String, android.graphics.Bitmap>?,
    textWidth: Float?,
): String? {
    views.removeAllViews(R.id.portfolio_overview_rows)
    views.setViewVisibility(R.id.portfolio_overview_rows, if (shown.isEmpty()) View.GONE else View.VISIBLE)
    if (shown.isEmpty()) return null
    val basis = PortfolioWidgetViews.rowChangeBasis(snapshot)
    val spoken = shown.mapIndexed { index, position ->
        val (row, text) = listRow(position, index == shown.size - 1, layout.wide, snapshot.currency, style, logos, basis, textWidth)
        views.addView(R.id.portfolio_overview_rows, row)
        text
    }
    return context.getString(R.string.a11y_portfolio_positions, spoken.joinToString("; "))
}
