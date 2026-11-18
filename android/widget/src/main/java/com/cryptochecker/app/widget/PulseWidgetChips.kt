package com.cryptochecker.app.widget

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.resources.R

/** Coins im Widget «Was gerade auffällt» ([PulseWidgetRenderer]): bis zu drei Texte bzw. Listenzeilen in widget_pulse.xml. */
internal object PulseWidgetChips {

    /** Die drei Coins des Widgets als Text (widget_pulse.xml). */
    private val CHIPS = listOf(R.id.pulse_chip_1, R.id.pulse_chip_2, R.id.pulse_chip_3)

    /**
     * Farbiger Text ohne Pille (wie iOS): Kürzel in der Textfarbe, Veränderung in der Kursfarbe (flach: grau).
     * Plätze ohne Coin bleiben verborgen.
     */
    fun draw(views: RemoteViews, coins: List<PulseWidgetCoin>, colors: WidgetColors) {
        CHIPS.forEachIndexed { index, id ->
            val coin = coins.getOrNull(index)
            if (coin == null) {
                views.setViewVisibility(id, View.GONE)
                return@forEachIndexed
            }
            val color = changeColor(coin, colors)
            val text = PulseWidgetMath.chipText(coin)
            views.setViewVisibility(id, View.VISIBLE)
            views.setTextViewText(
                id,
                SpannableString(text).apply {
                    setSpan(ForegroundColorSpan(colors.textColor), 0, coin.symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                },
            )
            views.setTextColor(id, color)
        }
    }

    /** Zeilen der Liste (widget_pulse.xml): Name, Veränderung. */
    private val ROWS = listOf(
        Triple(R.id.pulse_list_row_1, R.id.pulse_list_name_1, R.id.pulse_list_change_1),
        Triple(R.id.pulse_list_row_2, R.id.pulse_list_name_2, R.id.pulse_list_change_2),
        Triple(R.id.pulse_list_row_3, R.id.pulse_list_name_3, R.id.pulse_list_change_3),
    )

    /**
     * Gross mit Platz ([PulseWidgetList]): die Coins untereinander — Name in der Textfarbe, Veränderung in der
     * Kursfarbe (flach: grau), darüber rechts «24h». Leer = Liste verborgen.
     */
    fun drawList(views: RemoteViews, coins: List<PulseWidgetCoin>, colors: WidgetColors, period: String) {
        views.setViewVisibility(R.id.pulse_list, if (coins.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.pulse_list_period, period)
        views.setTextColor(R.id.pulse_list_period, colors.secondaryTextColor)
        ROWS.forEachIndexed { index, (row, name, change) ->
            val coin = coins.getOrNull(index)
            views.setViewVisibility(row, if (coin != null) View.VISIBLE else View.GONE)
            if (coin == null) return@forEachIndexed
            views.setTextViewText(name, coin.name)
            views.setTextColor(name, colors.textColor)
            views.setTextViewText(change, PulseWidgetMath.changeText(coin.change))
            views.setTextColor(change, changeColor(coin, colors))
        }
    }

    private fun changeColor(coin: PulseWidgetCoin, colors: WidgetColors): Int = when {
        PulseWidgetMath.isFlat(coin.change) -> colors.secondaryTextColor
        coin.change > 0 -> colors.upColor
        else -> colors.downColor
    }
}
