// Gesamtwert, Betragszeile «↘ −1’968.40 CHF −2.31% · 24h», Zeile «≈ … USDT» und Fusszeile des
// Portfolio-Widgets (Teil von `PortfolioWidgetRenderer`).
// Eigene Datei, weil alle Ansichten (Chart, Übersicht, Liste) dieselbe Wert-Bindung nutzen.
package com.cryptochecker.app.widget

import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshotMath
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat

/**
 * Gesamtwert (passt sich der Breite an), darunter «↘ −1’968.40 CHF  −2.31% · 24h» bzw. «· 7T» —
 * Betrag und Prozent als Text in der Kursfarbe, der Zeitraum grau (nie rot/grün); wird es in
 * [textWidth] eng, fällt zuerst der Zeitraum, dann der Betrag weg ([WidgetTextFit.changeLineCandidates]).
 * Dazu «≈ 100’143.67 USDT · ohne 1 Coin» (wird es eng, nur der Hinweis). Jede Währung klein und grau
 * ([WidgetToolkit.quoteText]); die Breiten werden mit der verkleinerten Währung gemessen.
 * @param changeLine Platz für die Betragszeile; [usdtLine] Platz für die zweite Zeile
 * @param wantUsdt «≈ … USDT» gewünscht (Anzeige «Umrechnung + USDT»)
 */
internal fun PortfolioWidgetRenderer.bindValues(
    views: RemoteViews,
    snapshot: PortfolioSnapshot,
    plan: PortfolioChartPlan,
    style: PortfolioStyle,
    priceColor: Int,
    textWidth: Float?,
    changeLine: Boolean,
    usdtLine: Boolean,
    wantUsdt: Boolean,
    missing: String?,
): ValueTexts {
    val colors = style.colors
    val currency = snapshot.currency
    val total = PortfolioInsights.mask(PriceFormat.valueWithCurrency(snapshot.total, currency), style.hideAmounts)
    views.setTextViewText(R.id.portfolio_total, toolkit.quoteText(total, currency, TOTAL_MAX_SP, colors.secondaryTextColor))
    views.setTextColor(R.id.portfolio_total, colors.textColor)

    val amount = plan.changeAmount
    if (changeLine && amount != null) {
        // Schräger Pfeil wie in der App (folgt dem Vorzeichen des Betrags, nie dem Farbtausch)
        val arrow = PriceFormat.changeArrow(amount)
        // Zeitraum der Veränderung: 24 h nach der %-Basis, sonst «7T» / «30T» / «1J»
        val changePeriod = if (plan.range == PortfolioChartRange.DAY) {
            ChangeBasisText.shortLabel(context, style.basis)
        } else {
            rangeShort(plan.range)
        }
        val candidates = WidgetTextFit.changeLineCandidates(
            arrow = arrow,
            amount = PortfolioInsights.mask(PortfolioSnapshotMath.signedAmount(amount, currency), style.hideAmounts),
            percent = PortfolioWidgetChart.percentText(plan),
            period = changePeriod,
        )
        // Lieber etwas kleiner (12 → 11 sp) als ohne Betrag oder Prozent
        val (text, sp) = WidgetTextFit.firstFittingSized(
            candidates,
            WidgetTextFit.CHANGE_LINE_SIZES_SP,
            textWidth?.minus(WidgetTextFit.SAFETY_DP),
        ) { candidate, size -> toolkit.quoteTextWidthDp(candidate, currency, size, tabular = true) }
        views.setViewVisibility(R.id.portfolio_change, View.VISIBLE)
        views.setTextViewTextSize(R.id.portfolio_change, android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        views.setTextViewText(
            R.id.portfolio_change,
            toolkit.periodGrey(
                toolkit.quoteText(text, currency, sp, colors.secondaryTextColor),
                WidgetTextFit.periodStart(text, changePeriod),
                colors.secondaryTextColor,
            ),
        )
        views.setTextColor(R.id.portfolio_change, priceColor)
    } else {
        views.setViewVisibility(R.id.portfolio_change, View.GONE)
    }

    // «≈ 100’143.67 USDT» — nur bei «Umrechnung + USDT» (nicht, wenn die Anzeige schon USD ist)
    val usdt = snapshot.totalUsdt?.takeIf { wantUsdt && usdtLine && !style.hideAmounts }
        ?.let { PriceFormat.valueWithCurrency(it, USDT) }
    // Hinweis vor ≈ USDT: wird es eng, bleibt nur «ohne 1 Coin»
    val missingInLine = missing != null && usdtLine
    val secondLine = WidgetTextFit.firstFitting(
        listOfNotNull(
            listOfNotNull(usdt?.let { "≈ $it" }, missing.takeIf { missingInLine }).joinToString(" · "),
            missing.takeIf { missingInLine },
        ),
        textWidth?.minus(WidgetTextFit.SAFETY_DP),
    ) { toolkit.quoteTextWidthDp(it, USDT, USDT_SP, bold = false, tabular = true) }
    if (secondLine.isNotEmpty()) {
        views.setViewVisibility(R.id.portfolio_usdt, View.VISIBLE)
        views.setTextViewText(R.id.portfolio_usdt, toolkit.quoteText(secondLine, USDT, USDT_SP, colors.secondaryTextColor))
        views.setTextColor(R.id.portfolio_usdt, colors.secondaryTextColor)
    } else {
        views.setViewVisibility(R.id.portfolio_usdt, View.GONE)
    }
    return ValueTexts(total, usdt, missingInLine)
}

/** Fusszeile: der erste der [candidates] (lang → kurz), der in [textWidth] passt. */
internal fun PortfolioWidgetRenderer.bindFooter(views: RemoteViews, visible: Boolean, textWidth: Float?, candidates: List<String>, colors: WidgetColors) {
    views.setViewVisibility(R.id.portfolio_time, if (visible) View.VISIBLE else View.GONE)
    val text = WidgetTextFit.firstFitting(candidates, textWidth?.minus(WidgetTextFit.SAFETY_DP)) {
        toolkit.textWidthDp(it, FOOTER_SP, bold = false, tabular = true)
    }
    views.setTextViewText(R.id.portfolio_time, text)
    views.setTextColor(R.id.portfolio_time, colors.secondaryTextColor)
}

/** Grösste Schrift des Gesamtwerts (autoSizeMaxTextSize, widget_portfolio_value_block.xml). */
private const val TOTAL_MAX_SP = 26f

/**
 * Schrift der Zeile «≈ … USDT», des Betrags einer Kennzahl und des Werts einer Position (die Betragszeile
 * wählt ihre Grösse aus [WidgetTextFit.CHANGE_LINE_SIZES_SP]).
 */
private const val USDT_SP = 12f

/** Fusszeile «Stand 15:19» (wie textSize von portfolio_time in den Layouts). */
private const val FOOTER_SP = 12f
