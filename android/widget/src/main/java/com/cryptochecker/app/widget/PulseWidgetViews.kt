package com.cryptochecker.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.domain.format.MonthYearFormat
import com.cryptochecker.app.domain.market.BitcoinHalving
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.util.LocaleNumbers
import com.cryptochecker.app.domain.util.PercentText
import com.cryptochecker.resources.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

// Ansichten «Gewinner und Verlierer 24 h» und «Bitcoin-Halving» des Pulse-Widgets ([PulseWidgetView]); der Impuls
// bleibt in [drawPulseReport]. Gleiche Kopfzeile, Fläche und Farben; wie iOS `PulseWidgetViews.swift`.

/** Zeilen der Ansicht «Gewinner und Verlierer» (reine Regel, getestet in PulseWidgetViewsTest). */
object PulseWidgetMovers {
    const val MAX_ROWS = 3
    private const val ROW_DP = 22
    private const val FIXED_DP = 24 + 20 + 6 + 16 + 16

    /** Zeilen je Spalte bei der Höhe [heightDp]: Kopf, Spaltentitel und Stand abgezogen, je Coin 22 dp, 1…3. */
    fun rows(heightDp: Int): Int = if (heightDp <= 0) MAX_ROWS else ((heightDp - FIXED_DP) / ROW_DP).coerceIn(1, MAX_ROWS)
}

private val GAIN_ROWS = listOf(
    Triple(R.id.pulse_gain_row_1, R.id.pulse_gain_sym_1, R.id.pulse_gain_chg_1),
    Triple(R.id.pulse_gain_row_2, R.id.pulse_gain_sym_2, R.id.pulse_gain_chg_2),
    Triple(R.id.pulse_gain_row_3, R.id.pulse_gain_sym_3, R.id.pulse_gain_chg_3),
)
private val LOSS_ROWS = listOf(
    Triple(R.id.pulse_loss_row_1, R.id.pulse_loss_sym_1, R.id.pulse_loss_chg_1),
    Triple(R.id.pulse_loss_row_2, R.id.pulse_loss_sym_2, R.id.pulse_loss_chg_2),
    Triple(R.id.pulse_loss_row_3, R.id.pulse_loss_sym_3, R.id.pulse_loss_chg_3),
)

/** Blendet die Bereiche der anderen Ansichten aus; [shown] = sichtbarer Bereich. */
internal fun showPulseSection(views: RemoteViews, shown: Int) {
    for (id in listOf(R.id.pulse_content, R.id.pulse_message, R.id.pulse_movers, R.id.pulse_halving)) {
        views.setViewVisibility(id, if (id == shown) View.VISIBLE else View.GONE)
    }
}

/**
 * Gewinner links, Verlierer rechts (je bis zu [rows], aus der Rangliste des Pulse, CoinGecko Top 100 ohne
 * Stablecoins), Veränderung in der Kursfarbe; darunter der Stand. Leere Seite: «Kein Coin ist in 24 h gestiegen».
 * @return Satz für den Screenreader
 */
internal fun drawPulseMovers(
    context: Context,
    views: RemoteViews,
    colors: WidgetColors,
    movers: MarketMoversResult,
    rows: Int,
    stand: String?,
): String {
    showPulseSection(views, R.id.pulse_movers)
    val gainTitle = context.getString(R.string.movers_gainers)
    val lossTitle = context.getString(R.string.movers_losers)
    views.setTextViewText(R.id.pulse_gain_title, gainTitle)
    views.setTextViewText(R.id.pulse_loss_title, lossTitle)
    views.setTextColor(R.id.pulse_gain_title, colors.secondaryTextColor)
    views.setTextColor(R.id.pulse_loss_title, colors.secondaryTextColor)
    val gainers = movers.gainers.filter { (it.change24h ?: 0.0) > 0.0 }.take(rows)
    val losers = movers.losers.filter { (it.change24h ?: 0.0) < 0.0 }.take(rows)
    drawMoverColumn(views, colors, GAIN_ROWS, gainers)
    drawMoverColumn(views, colors, LOSS_ROWS, losers)
    if (gainers.isEmpty()) views.setTextViewText(R.id.pulse_gain_title, context.getString(R.string.movers_no_gainers))
    if (losers.isEmpty()) views.setTextViewText(R.id.pulse_loss_title, context.getString(R.string.movers_no_losers))
    views.setViewVisibility(R.id.pulse_movers_time, if (stand != null) View.VISIBLE else View.GONE)
    views.setTextViewText(R.id.pulse_movers_time, stand.orEmpty())
    views.setTextColor(R.id.pulse_movers_time, colors.secondaryTextColor)
    fun spoken(list: List<MoverCoin>) = list.joinToString(", ") { it.symbol + " " + PercentText.signed(it.change24h ?: 0.0, 1) }
    return listOfNotNull(
        context.getString(R.string.movers_title),
        context.getString(R.string.label_value, gainTitle, spoken(gainers)),
        context.getString(R.string.label_value, lossTitle, spoken(losers)),
        stand,
    ).joinToString(". ")
}

private fun drawMoverColumn(views: RemoteViews, colors: WidgetColors, ids: List<Triple<Int, Int, Int>>, coins: List<MoverCoin>) {
    ids.forEachIndexed { index, (row, symbol, change) ->
        val coin = coins.getOrNull(index)
        views.setViewVisibility(row, if (coin != null) View.VISIBLE else View.GONE)
        if (coin == null) return@forEachIndexed
        val value = coin.change24h ?: 0.0
        views.setTextViewText(symbol, coin.symbol)
        views.setTextColor(symbol, colors.textColor)
        views.setTextViewText(change, PercentText.signed(value, 1))
        views.setTextColor(change, if (value > 0) colors.upColor else colors.downColor)
    }
}

/**
 * Nächstes Halving nach Kalender ([BitcoinHalving.info], ohne Netz): Monat gross («~April 2028»), «noch etwa 512 Tage»,
 * letztes Halving und «Nach Kalender geschätzt». @return Satz für den Screenreader
 */
internal fun drawPulseHalving(context: Context, views: RemoteViews, colors: WidgetColors, today: LocalDate = LocalDate.now()): String {
    showPulseSection(views, R.id.pulse_halving)
    val info = BitcoinHalving.info(today)
    val monthYear = info.nextHalvingEstimate.format(MonthYearFormat.formatter())
    val month = "~$monthYear"
    val days = ChronoUnit.DAYS.between(today, info.nextHalvingEstimate).coerceAtLeast(0).toInt()
    val left = context.getString(
        R.string.pulse_widget_halving_left,
        context.resources.getQuantityString(R.plurals.alarm_near_window_days, days, days),
    )
    val last = context.getString(
        R.string.neutral_halving_last,
        info.lastHalving.format(LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
    )
    val note = context.getString(R.string.pulse_widget_halving_note)
    views.setTextViewText(R.id.pulse_halving_date, month)
    views.setTextColor(R.id.pulse_halving_date, colors.textColor)
    views.setTextViewText(R.id.pulse_halving_left, left)
    views.setTextColor(R.id.pulse_halving_left, colors.textColor)
    views.setTextViewText(R.id.pulse_halving_last, last)
    views.setTextColor(R.id.pulse_halving_last, colors.secondaryTextColor)
    views.setTextViewText(R.id.pulse_halving_note, note)
    views.setTextColor(R.id.pulse_halving_note, colors.secondaryTextColor)
    // Screenreader ohne «~» (würde als «Tilde» gelesen); «geschätzt» sagt der Hinweis
    return listOf(context.getString(R.string.insights_halving_title), monthYear, left, last, note).joinToString(". ")
}
