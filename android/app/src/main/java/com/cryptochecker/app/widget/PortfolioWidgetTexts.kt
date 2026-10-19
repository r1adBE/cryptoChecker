// Texte des Portfolio-Widgets (Teil von `PortfolioWidgetRenderer`): Hinweis «aus», Zeitraum kurz/lang,
// fehlende Coins, Stand, Ausweich-Hinweis, Farbe der Richtung und die Screenreader-Sätze zu Änderung und
// Gesamtwert. Eigene Datei, weil es reine Textregeln sind, die alle Ansichten des Widgets teilen.
package com.cryptochecker.app.widget

import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmTimingHint
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat

/** «Einschalten unter Einstellungen › Portfolio.» — Pfad aus den Namen der App (Build 83: Zeile unter «Allgemein»). */
internal fun PortfolioWidgetRenderer.offHint(): String = context.getString(
    R.string.widget_portfolio_off_hint,
    AlarmTimingHint.settingsPath(
        context.getString(R.string.settings_title),
        context.getString(R.string.portfolio_title),
    ),
)

/** Tages-Basis: feste Zeitachse vom Tagesbeginn bis Tagesende für den 24-h-Verlauf; sonst null. */
internal fun PortfolioWidgetRenderer.dayAxis(snapshot: PortfolioSnapshot): Pair<Long, Long>? =
    snapshot.stamp?.takeIf { it.basis.isDay }
        ?.let { s -> ChangeBasisMath.dayEnd(s.basis, s.dayStart)?.let { end -> s.dayStart to end } }

/** Kurzform eines Zeitraums mit Tagesverlauf: «7T», «30T», «1J». */
internal fun PortfolioWidgetRenderer.rangeShort(range: PortfolioChartRange): String = context.getString(
    when (range) {
        PortfolioChartRange.WEEK -> R.string.widget_range_short_7d
        PortfolioChartRange.MONTH -> R.string.widget_range_short_30d
        PortfolioChartRange.YEAR -> R.string.widget_range_short_1y
        PortfolioChartRange.DAY -> R.string.widget_range_short_24h
    }
)

/** Langform eines Zeitraums mit Tagesverlauf: «7 Tage», «30 Tage», «1 Jahr». */
internal fun PortfolioWidgetRenderer.rangeLong(range: PortfolioChartRange): String = context.getString(
    when (range) {
        PortfolioChartRange.WEEK -> R.string.widget_range_7d
        PortfolioChartRange.MONTH -> R.string.widget_range_30d
        PortfolioChartRange.YEAR -> R.string.widget_range_1y
        PortfolioChartRange.DAY -> R.string.widget_range_24h
    }
)

/** Kursfarbe der Richtung (+1 / −1 / 0 bzw. ohne Betrag neutral). */
internal fun PortfolioWidgetRenderer.directionColor(direction: Int?, colors: WidgetColors): Int = when (direction) {
    1 -> colors.upColor
    -1 -> colors.downColor
    else -> colors.neutralColor
}

/**
 * «ohne 1 Coin»: offene Coins ohne Kurs zählen im Gesamtwert als 0. Dazu «Heute ohne SOL», wenn
 * die sichtbare «Heute»-Zahl Coins auslässt (wie der Hinweis der Gesamtkarte,
 * [PortfolioWidgetViews.todaySkipped]) und «teilweise alter Kurs» ([PortfolioSnapshot.stalePrices]).
 * Alles fehlt: null.
 */
internal fun PortfolioWidgetRenderer.missingText(snapshot: PortfolioSnapshot, plan: PortfolioChartPlan, overview: Boolean): String? {
    val coins = snapshot.missingPrices.takeIf { it > 0 }?.let {
        context.resources.getQuantityString(R.plurals.widget_portfolio_missing_coins, it, it)
    }
    val today = PortfolioWidgetViews.todaySkipped(snapshot, plan, overview).takeIf { it.isNotEmpty() }?.let {
        context.getString(R.string.widget_portfolio_today_without, it.joinToString(", "))
    }
    // «7 T ohne SOL»: dieselbe Teilsumme wie «Gewinn 7 T» der App, mit derselben Nennung
    val period = PortfolioWidgetViews.periodSkipped(snapshot, plan, overview)?.let { (range, list) ->
        context.getString(R.string.widget_portfolio_period_without, rangeShort(range), list.joinToString(", "))
    }
    // Teilweise alter Kurs: der Wert zählt Coins mit ihrem letzten Kurs — nie als frisch ausgeben
    val stale = if (snapshot.stalePrices > 0) context.getString(R.string.widget_portfolio_partly_stale) else null
    return listOfNotNull(coins, stale, today, period).joinToString(" · ").ifEmpty { null }
}

/** «Stand 15:19»; alter Stand ausgeschrieben: «veraltet · 06:42». */
internal fun PortfolioWidgetRenderer.timeText(snapshot: PortfolioSnapshot, outdated: Boolean): String =
    if (outdated) WidgetOutdated.label(context, snapshot.time)
    else context.getString(R.string.widget_portfolio_as_of, WidgetOutdated.stamp(context, snapshot.time))

/** Stand für den Screenreader: «Stand 15:19» bzw. «veraltet, letzte Aktualisierung 06:42». */
internal fun PortfolioWidgetRenderer.spokenTime(snapshot: PortfolioSnapshot, outdated: Boolean): String =
    if (outdated) WidgetOutdated.spoken(context, snapshot.time)
    else context.getString(R.string.widget_portfolio_as_of, WidgetOutdated.stamp(context, snapshot.time))

/** Gewählter Zeitraum ohne Tageskurse: «7T folgt» (das Widget zeigt 24 h); sonst null. */
internal fun PortfolioWidgetRenderer.fallbackHint(plan: PortfolioChartPlan): String? =
    if (plan.fallback) context.getString(R.string.widget_portfolio_range_fallback, rangeShort(plan.requested)) else null

/**
 * Veränderung für den Screenreader: «gesunken um 2.31% in 24 Stunden» (nach der %-Basis) bzw.
 * «gestiegen um 4.10%, 7 Tage»; null ohne Betrag.
 */
internal fun PortfolioWidgetRenderer.spokenChange(plan: PortfolioChartPlan, snapshot: PortfolioSnapshot, style: PortfolioStyle): String? {
    val amount = plan.changeAmount ?: return null
    val percent = plan.changePercent
    val direction = PortfolioWidgetChart.direction(plan)
    val byAmount = {
        context.getString(
            if (amount > 0) R.string.a11y_change_up else R.string.a11y_change_down,
            if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden)
            else PriceFormat.valueWithCurrency(kotlin.math.abs(amount), snapshot.currency)
        )
    }
    return when {
        plan.range != PortfolioChartRange.DAY -> {
            val phrase = when {
                percent != null -> A11yText.change(context, percent)
                direction == 0 -> context.getString(R.string.a11y_change_flat)
                else -> byAmount()
            }
            "$phrase, ${rangeLong(plan.range)}"
        }
        percent != null -> ChangeBasisText.spoken(context, style.basis, percent)
        direction == 0 -> ChangeBasisText.spokenPhrase(context, style.basis, context.getString(R.string.a11y_change_flat))
        else -> ChangeBasisText.spokenPhrase(context, style.basis, byAmount())
    }
}

/** Gesamtwert für den Screenreader (verborgen: «Betrag verborgen»). */
internal fun PortfolioWidgetRenderer.spokenTotal(title: String, total: String, style: PortfolioStyle): String =
    "$title " + if (style.hideAmounts) context.getString(R.string.a11y_amount_hidden) else total
