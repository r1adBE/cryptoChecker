package com.cryptochecker.app.widget

import com.cryptochecker.app.R
import com.cryptochecker.app.data.remote.CandleInterval

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
