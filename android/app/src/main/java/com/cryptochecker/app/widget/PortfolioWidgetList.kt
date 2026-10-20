// Ansicht «Liste» des Portfolio-Widgets (Teil von `PortfolioWidgetRenderer`): Gesamtzeile und die grössten
// Positionen mit Anteil, Wert und Änderung. Eigene Datei, weil jede Ansicht des Widgets für sich lesbar sein
// soll (wie `PortfolioWidgetList.swift`).
package com.cryptochecker.app.widget

import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.util.WidgetQuantityFormat

/**
 * Ansicht «Liste» (wie die Positionen im Portfolio-Tab): Kopfzeile, so viele Positionen
 * nach Wert wie ganz passen ([PortfolioWidgetViews.listLayout]), fest darunter die Total-Zeile
 * ([bindListTotal]) und «Stand 15:19 · + 3 weitere».
 * Ohne gespeicherte Positionen (ältere Aufnahme) der Hinweis, die App zu öffnen.
 * @param logos Coin-Logo je Kürzel (Grossschreibung); null = Schalter «Coin-Logos in Widgets» aus
 */
internal fun PortfolioWidgetRenderer.listViews(
    widthDp: Int,
    heightDp: Int,
    snapshot: PortfolioSnapshot,
    plan: PortfolioChartPlan,
    style: PortfolioStyle,
    outdated: Boolean,
    fontScale: Float,
    logos: Map<String, android.graphics.Bitmap>?,
): RemoteViews {
    val colors = style.colors
    val layout = PortfolioWidgetViews.listLayout(snapshot.positions, snapshot.otherPositions, widthDp, heightDp, fontScale)
    val views = RemoteViews(context.packageName, R.layout.widget_portfolio_list)
    portfolioFrame(views, style)
    val title = context.getString(R.string.widget_portfolio_name)
    val textWidth = if (widthDp > 0) widthDp - 24f else null

    views.removeAllViews(R.id.portfolio_list_rows)
    // Zeitraum der Veränderung je Position, wie sie aufgenommen wurde («24h» bzw. «heute»)
    val rowBasis = PortfolioWidgetViews.rowChangeBasis(snapshot)
    val spokenRows = layout.shown.mapIndexed { index, position ->
        val (row, spoken) = listRow(position, index == layout.shown.size - 1, layout.wide, snapshot.currency, style, logos, rowBasis, textWidth)
        views.addView(R.id.portfolio_list_rows, row)
        spoken
    }
    // Rahmen bleibt sichtbar (auch leer): er hält die Fusszeilen unten
    views.setViewVisibility(R.id.portfolio_list_rows, View.VISIBLE)
    val hint = if (snapshot.positions.isEmpty()) context.getString(R.string.widget_portfolio_list_hint) else null
    views.setViewVisibility(R.id.portfolio_list_hint, if (hint != null) View.VISIBLE else View.GONE)
    if (hint != null) {
        views.setTextViewText(R.id.portfolio_list_hint, hint)
        views.setTextColor(R.id.portfolio_list_hint, colors.secondaryTextColor)
    }

    val more = layout.more.takeIf { it > 0 && snapshot.positions.isNotEmpty() }?.let {
        context.resources.getQuantityString(R.plurals.widget_portfolio_more, it, it)
    }
    // Total-Zeile (immer sichtbar, wie eine Listenzeile); «ohne 1 Coin» / «Heute ohne SOL» wie die Übersicht in «Stand …»
    val totalChange = PortfolioWidgetViews.listTotalChange(snapshot)
    val total = bindListTotal(views, snapshot, totalChange, style, rowBasis, textWidth, layout.wide)
    val missing = missingText(snapshot, plan, overview = true)
    val timeText = timeText(snapshot, outdated)
    bindFooter(
        views,
        layout.footer,
        textWidth,
        listOf(
            listOfNotNull(timeText, missing, more).joinToString(" · "),
            listOfNotNull(timeText, missing).joinToString(" · "),
            listOfNotNull(timeText, more).joinToString(" · "),
            timeText,
        ),
        colors,
    )

    // Screenreader: Gewinn/Verlust des Totals wie gezeigt (Heute/24 h nach der %-Basis)
    val spokenTotalChange = totalChange?.let { spokenPnl(it, snapshot.currency, style, rowBasis) }
    views.setContentDescription(
        R.id.portfolio_root,
        listOfNotNull(
            spokenTotal(title, total, style),
            spokenTotalChange,
            spokenTime(snapshot, outdated),
            missing,
            spokenRows.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.a11y_portfolio_positions, it.joinToString("; ")) },
            more,
            hint,
        ).joinToString(", ")
    )
    return views
}

/**
 * Total-Zeile der Liste, gestaltet wie die Listenzeilen (Build 46: [wide] einzeilig wie
 * widget_portfolio_list_item_wide.xml, sonst zweizeilig wie widget_portfolio_list_item.xml):
 * links «Gesamt» (fett wie das Kürzel) und «100%» (wie der Anteil), rechts der Wert (Währung klein und
 * grau; verborgen «•••») und MEIN Gewinn/Verlust wie in den Zeilen («24h +1’500.00 CHF · +1.61%», Zeitraum
 * [rowBasis]; die Summe der Zeilen); ohne Betrag bleibt die zweite Zeile rechts leer. Darüber die Trennlinie.
 * @return gezeigter Wert (für den Screenreader)
 */
internal fun PortfolioWidgetRenderer.bindListTotal(
    views: RemoteViews,
    snapshot: PortfolioSnapshot,
    change: PortfolioListTotalChange?,
    style: PortfolioStyle,
    rowBasis: ChangeBasis,
    textWidth: Float?,
    wide: Boolean,
): String {
    val colors = style.colors
    val currency = snapshot.currency
    views.setInt(R.id.portfolio_list_total_divider, "setBackgroundColor", colors.dividerColor)
    views.removeAllViews(R.id.portfolio_list_total)
    val row = RemoteViews(
        context.packageName,
        if (wide) R.layout.widget_portfolio_list_item_wide else R.layout.widget_portfolio_list_item
    )
    row.setViewVisibility(R.id.pl_logo, View.GONE)
    row.setViewVisibility(R.id.pl_quantity, View.GONE)
    row.setViewVisibility(R.id.pl_divider, View.GONE)
    val totalLabel = context.getString(R.string.portfolio_card_total)
    row.setTextViewText(R.id.pl_symbol, totalLabel)
    row.setTextColor(R.id.pl_symbol, colors.textColor)
    val share = PortfolioWidgetMath.shareText(PortfolioWidgetViews.LIST_TOTAL_SHARE)
    row.setTextViewText(R.id.pl_share, share)
    row.setTextColor(R.id.pl_share, colors.secondaryTextColor)
    val value = PortfolioInsights.mask(PriceFormat.valueWithCurrency(snapshot.total, currency), style.hideAmounts)
    row.setAmountText(R.id.pl_value, toolkit.quoteText(value, currency, ROW_VALUE_SP, colors.secondaryTextColor))
    row.setTextColor(R.id.pl_value, colors.textColor)
    if (change != null) {
        // Wie die Zeilen: Betrag vor Prozent; Farbe nach dem Vorzeichen des Betrags (0 grau)
        val label = ChangeBasisText.shortLabel(context, rowBasis)
        // Platz wie in den Zeilen ([listRow]): schmal neben dem Anteil, breit neben «Gesamt», Anteil und Wert
        val shareDp = toolkit.textWidthDp(share, 12f, bold = false, tabular = true)
        val budget = textWidth?.let {
            if (wide) it - toolkit.textWidthDp(totalLabel, WidgetType.LABEL) - 4f - shareDp - 4f -
                toolkit.textWidthDp(value, ROW_VALUE_SP, tabular = true) - 4f
            else it - shareDp - 4f
        }
        row.setViewVisibility(R.id.pl_change, View.VISIBLE)
        row.setAmountText(R.id.pl_change, periodPrefixed(label, fitPnl(change, currency, style, label, budget), colors))
        row.setTextColor(R.id.pl_change, directionColor(change.direction, colors))
    } else {
        row.setViewVisibility(R.id.pl_change, View.GONE)
    }
    views.addView(R.id.portfolio_list_total, row)
    return value
}

/**
 * Eine Zeile der Liste: Coin-Logo (falls eingeschaltet), Menge (klein, grau), Kürzel, Anteil (grau), Wert (Währung klein
 * und grau) und MEIN Gewinn/Verlust auf der Position nach der %-Basis («24h +1’250.00 CHF · +1.50%», Farbe nach dem
 * Betrag; bei knapper Breite nur der Betrag; unbekannt grau «—», [PortfolioWidgetViews.rowPnl]);
 * Trennlinie wie in der Merkliste, nicht nach der letzten Zeile.
 * @return Zeile und Satz für den Screenreader
 */
internal fun PortfolioWidgetRenderer.listRow(
    position: PortfolioPosition,
    last: Boolean,
    wide: Boolean,
    currency: String,
    style: PortfolioStyle,
    logos: Map<String, android.graphics.Bitmap>?,
    rowBasis: ChangeBasis,
    textWidth: Float?,
): Pair<RemoteViews, String> {
    val colors = style.colors
    val row = RemoteViews(
        context.packageName,
        if (wide) R.layout.widget_portfolio_list_item_wide else R.layout.widget_portfolio_list_item
    )
    val logo = logos?.get(position.symbol.uppercase())
    if (logo != null) {
        row.setImageViewBitmap(R.id.pl_logo, logo)
        row.setViewVisibility(R.id.pl_logo, View.VISIBLE)
    } else {
        row.setViewVisibility(R.id.pl_logo, View.GONE)
    }
    row.setTextViewText(R.id.pl_symbol, position.symbol)
    row.setTextColor(R.id.pl_symbol, colors.textColor)
    // Menge klein und grau vor dem Kürzel («1.3 BTC»); bei verborgenen Beträgen keine
    val quantity = WidgetQuantityFormat.visible(position.quantity, style.hideAmounts)
    row.setViewVisibility(R.id.pl_quantity, if (quantity != null) View.VISIBLE else View.GONE)
    if (quantity != null) {
        row.setTextViewText(R.id.pl_quantity, quantity)
        row.setTextColor(R.id.pl_quantity, colors.secondaryTextColor)
    }
    val share = PortfolioWidgetMath.shareText(position.sharePercent)
    row.setTextViewText(R.id.pl_share, share)
    row.setTextColor(R.id.pl_share, colors.secondaryTextColor)
    val value = PortfolioInsights.mask(PriceFormat.valueWithCurrency(position.value, currency), style.hideAmounts)
    row.setAmountText(R.id.pl_value, toolkit.quoteText(value, currency, ROW_VALUE_SP, colors.secondaryTextColor))
    row.setTextColor(R.id.pl_value, colors.textColor)

    // Gewinn/Verlust: Farbe nach dem Vorzeichen des Betrags (nie dem Farbtausch), 0 grau; unbekannt «—»
    val pnl = PortfolioWidgetViews.rowPnl(position)
    if (pnl != null) {
        val label = ChangeBasisText.shortLabel(context, rowBasis)
        // Platz für «24h …»: schmal neben dem Anteil, breit neben Menge, Kürzel, Anteil und Wert
        val logoDp = if (logo != null) (if (wide) 24f else 28f) else 0f
        val shareDp = toolkit.textWidthDp(share, 12f, bold = false, tabular = true)
        // Breit: Menge steht in derselben Zeile (12 sp, 3 dp Abstand zum Kürzel)
        val quantityDp = if (wide && quantity != null) toolkit.textWidthDp(quantity, 12f, bold = false, tabular = true) + 3f else 0f
        val budget = textWidth?.let {
            if (wide) it - logoDp - quantityDp - toolkit.textWidthDp(position.symbol, WidgetType.LABEL) - 4f - shareDp - 4f -
                toolkit.textWidthDp(value, ROW_VALUE_SP, tabular = true) - 4f
            else it - logoDp - shareDp - 4f
        }
        // Zeitraum klein und grau davor: «24h +1’250.00 CHF · +1.50%»
        row.setAmountText(R.id.pl_change, periodPrefixed(label, fitPnl(pnl, currency, style, label, budget), colors))
        row.setTextColor(R.id.pl_change, directionColor(pnl.direction, colors))
    } else {
        row.setAmountText(R.id.pl_change, NO_VALUE)
        row.setTextColor(R.id.pl_change, colors.secondaryTextColor)
    }
    row.setInt(R.id.pl_divider, "setBackgroundColor", colors.dividerColor)
    row.setViewVisibility(R.id.pl_divider, if (last) View.GONE else View.VISIBLE)

    val spokenValue = if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else value
    val spoken = context.getString(R.string.a11y_portfolio_position, WidgetQuantityFormat.spokenName(position.symbol, quantity), spokenValue, share) +
        ", " + (pnl?.let { spokenPnl(it, currency, style, rowBasis) } ?: ChangeBasisText.spoken(context, rowBasis, null))
    return row to spoken
}

/** Gewinn/Verlust, der in [budget] dp passt (samt Zeitraum [label] davor): Betrag · Prozent, sonst nur der Betrag. */
internal fun PortfolioWidgetRenderer.fitPnl(change: PortfolioListTotalChange, currency: String, style: PortfolioStyle, label: String, budget: Float?): String {
    val candidates = PortfolioWidgetViews.pnlTexts(change, currency, style.hideAmounts)
    val prefixDp = if (label.isEmpty()) 0f else toolkit.textWidthDp("$label ", 12f * PERIOD_SCALE, bold = false)
    return WidgetTextFit.firstFitting(candidates, budget?.minus(prefixDp + WidgetTextFit.SAFETY_DP)) {
        toolkit.textWidthDp(it, 12f, tabular = true)
    }
}

/**
 * Gewinn/Verlust für den Screenreader: «up 1’250.00 CHF, up 1.50% in 24 hours» (Betrag verborgen: «amount hidden»).
 */
internal fun PortfolioWidgetRenderer.spokenPnl(change: PortfolioListTotalChange, currency: String, style: PortfolioStyle, basis: ChangeBasis): String {
    val amount = if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else {
        val text = PriceFormat.valueWithCurrency(kotlin.math.abs(change.amount), currency)
        when (change.direction) {
            0 -> context.getString(R.string.a11y_change_flat)
            1 -> context.getString(R.string.a11y_change_up, text)
            else -> context.getString(R.string.a11y_change_down, text)
        }
    }
    val phrase = listOfNotNull(amount, change.percent?.let { A11yText.change(context, it) }).joinToString(", ")
    return ChangeBasisText.spokenPhrase(context, basis, phrase)
}

/** «24h ↘ −0.25%»: [period] verkleinert ([PERIOD_SCALE]) und grau vor dem Wert (Farbe des Werts setzt die Zeile). */
internal fun PortfolioWidgetRenderer.periodPrefixed(period: String, value: String, colors: WidgetColors): CharSequence {
    val text = "$period $value"
    return android.text.SpannableString(text).apply {
        setSpan(android.text.style.RelativeSizeSpan(PERIOD_SCALE), 0, period.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.ForegroundColorSpan(colors.secondaryTextColor), 0, period.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private const val ROW_VALUE_SP = WidgetType.LABEL

/** Zeitraum vor der Veränderung einer Listenzeile: 80 % der Schrift (wie iOS `WidgetChangeLabel.prefix`). */
private const val PERIOD_SCALE = 0.8f
