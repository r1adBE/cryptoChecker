package com.cryptochecker.app.widget

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.resources.R

/** Coins im Widget «Was gerade auffällt» ([PulseWidgetRenderer]): bis zu drei Texte in widget_pulse.xml. */
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
            val color = when {
                PulseWidgetMath.isFlat(coin.change) -> colors.secondaryTextColor
                coin.change > 0 -> colors.upColor
                else -> colors.downColor
            }
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
}
