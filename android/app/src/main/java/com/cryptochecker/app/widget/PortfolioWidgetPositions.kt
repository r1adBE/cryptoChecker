package com.cryptochecker.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.WidgetQuantityFormat
import kotlin.math.roundToInt

/**
 * Positionsliste des grossen Portfolio-Widgets: die drei grössten Positionen mit Anteil
 * (Text und dünner Balken) und Veränderung.
 */
internal object PortfolioWidgetPositions {
    /**
     * Zeilen der Positionsliste (gross): Menge (klein, grau) und Kürzel, Anteil als Text und dünner Balken (neutral, nicht
     * in der Akzentfarbe), MEIN Gewinn/Verlust auf der Position nach der %-Basis («+1’250.00 CHF · +1.50%», [pnlText]
     * kürzt auf den Betrag) in der Kursfarbe nach dem Betrag (unbekannt «—»). Nicht gebrauchte Zeilen ausblenden.
     * @return Satz für den Screenreader oder null ohne Liste
     */
    fun render(
        context: Context,
        views: RemoteViews,
        shown: List<PortfolioPosition>,
        colors: WidgetColors,
        basis: ChangeBasis,
        hideAmounts: Boolean = false,
        pnlText: (PortfolioListTotalChange) -> String,
        spokenPnl: (PortfolioListTotalChange) -> String,
    ): String? {
        val spoken = mutableListOf<String>()
        PORTFOLIO_ROWS.forEachIndexed { index, ids ->
            val position = shown.getOrNull(index)
            if (position == null) {
                views.setViewVisibility(ids.row, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(ids.row, View.VISIBLE)
            views.setTextViewText(ids.symbol, position.symbol)
            views.setTextColor(ids.symbol, colors.textColor)
            // Menge klein und grau vor dem Kürzel («1.3 BTC»); bei verborgenen Beträgen keine
            val quantity = WidgetQuantityFormat.visible(position.quantity, hideAmounts)
            views.setViewVisibility(ids.quantity, if (quantity != null) View.VISIBLE else View.GONE)
            if (quantity != null) {
                views.setTextViewText(ids.quantity, quantity)
                views.setTextColor(ids.quantity, colors.secondaryTextColor)
            }
            val name = WidgetQuantityFormat.spokenName(position.symbol, quantity)
            val share = PortfolioWidgetMath.shareText(position.sharePercent)
            views.setTextViewText(ids.share, share)
            views.setTextColor(ids.share, colors.secondaryTextColor)
            views.setInt(ids.track, "setColorFilter", colors.secondaryTextColor or 0xFF000000.toInt())
            views.setInt(ids.track, "setImageAlpha", SHARE_TRACK_ALPHA)
            views.setInt(ids.bar, "setColorFilter", colors.secondaryTextColor or 0xFF000000.toInt())
            views.setInt(ids.bar, "setImageAlpha", SHARE_BAR_ALPHA)
            views.setInt(ids.bar, "setImageLevel", (position.sharePercent.coerceIn(0.0, 100.0) * 100.0).roundToInt())

            val pnl = PortfolioWidgetViews.rowPnl(position)
            if (pnl != null) {
                // Farbe nach dem Vorzeichen des Betrags (nie dem Farbtausch), 0 neutral
                val color = when (pnl.direction) {
                    0 -> colors.neutralColor
                    1 -> colors.upColor
                    else -> colors.downColor
                }
                views.setTextViewText(ids.change, pnlText(pnl))
                views.setTextColor(ids.change, color)
                spoken += context.getString(R.string.a11y_portfolio_position_24h, name, share, spokenPnl(pnl))
            } else {
                views.setTextViewText(ids.change, "—")
                views.setTextColor(ids.change, colors.secondaryTextColor)
                spoken += context.getString(
                    R.string.a11y_portfolio_position_24h, name, share, ChangeBasisText.spoken(context, basis, null)
                )
            }
        }
        val visible = shown.isNotEmpty()
        views.setViewVisibility(R.id.portfolio_positions, if (visible) View.VISIBLE else View.GONE)
        return if (visible) context.getString(R.string.a11y_portfolio_positions, spoken.joinToString("; ")) else null
    }

    /** Deckkraft von Spur und Füllung des Anteil-Balkens (0–255). */
    private const val SHARE_TRACK_ALPHA = 46
    private const val SHARE_BAR_ALPHA = 170
}

/** Ids einer Zeile der Positionsliste im Portfolio-Widget (widget_portfolio_large.xml). */
private class PortfolioRowIds(
    val row: Int,
    val quantity: Int,
    val symbol: Int,
    val track: Int,
    val bar: Int,
    val share: Int,
    val change: Int,
)

/** Die drei festen Zeilen der Positionsliste (statisch statt Liste mit RemoteViewsService). */
private val PORTFOLIO_ROWS = listOf(
    PortfolioRowIds(R.id.portfolio_pos_1, R.id.portfolio_pos_1_quantity, R.id.portfolio_pos_1_symbol, R.id.portfolio_pos_1_track, R.id.portfolio_pos_1_bar,
        R.id.portfolio_pos_1_share, R.id.portfolio_pos_1_change),
    PortfolioRowIds(R.id.portfolio_pos_2, R.id.portfolio_pos_2_quantity, R.id.portfolio_pos_2_symbol, R.id.portfolio_pos_2_track, R.id.portfolio_pos_2_bar,
        R.id.portfolio_pos_2_share, R.id.portfolio_pos_2_change),
    PortfolioRowIds(R.id.portfolio_pos_3, R.id.portfolio_pos_3_quantity, R.id.portfolio_pos_3_symbol, R.id.portfolio_pos_3_track, R.id.portfolio_pos_3_bar,
        R.id.portfolio_pos_3_share, R.id.portfolio_pos_3_change),
)
