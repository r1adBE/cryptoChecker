package com.cryptochecker.app.util

import android.content.Context
import com.cryptochecker.app.R

/**
 * Sätze für Screenreader (TalkBack): Zeilen der Merkliste, Widgets und Charts.
 * Änderungen mit Richtungswort statt Vorzeichen, siehe [ChartSummary.unsignedPercent].
 */
object A11yText {

    /** «up 2.35%» / «down 1.20%» / «unchanged». */
    fun change(context: Context, percent: Double?, decimals: Int = 2): String =
        when (ChartSummary.direction(percent)) {
            ChangeDirection.UP -> context.getString(
                R.string.a11y_change_up, ChartSummary.unsignedPercent(percent ?: 0.0, decimals)
            )
            ChangeDirection.DOWN -> context.getString(
                R.string.a11y_change_down, ChartSummary.unsignedPercent(percent ?: 0.0, decimals)
            )
            ChangeDirection.FLAT -> context.getString(R.string.a11y_change_flat)
        }

    /**
     * Veränderung über 24 Stunden: «up 2.35% in 24 hours»; ohne Wert
     * «24-hour change not available» (Pille «—»).
     */
    fun change24h(context: Context, percent: Double?): String =
        if (percent == null || !percent.isFinite()) context.getString(R.string.a11y_change_24h_none)
        else context.getString(R.string.a11y_change_24h, change(context, percent))

    /**
     * «Chart 24h: from 100 to 110, up 10.00%. High 112, low 98.» — oder
     * «Chart: no data» bei weniger als zwei Werten.
     */
    fun chart(
        context: Context,
        period: String,
        values: List<Double>,
        format: (Double) -> String = PriceFormat::price,
    ): String = chart(context, period, ChartSummary.of(values), format)

    /** Wie oben, mit fertigen Kennzahlen (z. B. aus Kerzen: erste Eröffnung, letzter Schluss). */
    fun chart(
        context: Context,
        period: String,
        summary: ChartSummary?,
        format: (Double) -> String = PriceFormat::price,
    ): String {
        val s = summary ?: return context.getString(R.string.a11y_chart_empty)
        return context.getString(
            R.string.a11y_chart,
            period,
            format(s.start),
            format(s.end),
            change(context, s.changePercent),
            format(s.high),
            format(s.low),
        )
    }

    /**
     * Eine Kurszeile (Merkliste, Widget) als ein Satz, Teile mit «, » verbunden:
     * Paar und Börse, Kurs, Veränderung über 24 Stunden ([change24h]), dann die
     * optionalen Teile in der gegebenen Reihenfolge.
     */
    fun row(
        context: Context,
        pair: String,
        market: String,
        price: String,
        change24h: Double?,
        extras: List<String?> = emptyList(),
        /** %-Basis der Veränderung (Zeitraum im Satz). */
        basis: com.cryptochecker.app.domain.watch.ChangeBasis = com.cryptochecker.app.domain.watch.ChangeBasis.ROLLING_24H,
    ): String = buildList {
        add(context.getString(R.string.a11y_row_pair, pair, market))
        add(context.getString(R.string.a11y_price, price))
        // Ohne Bezug ein kurzer Hinweis statt einer Zahl (Pille «—»)
        add(ChangeBasisText.spoken(context, basis, change24h))
        extras.filterNotNull().filter { it.isNotBlank() }.forEach(::add)
    }.joinToString(", ")
}
