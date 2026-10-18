// Etikett beim Ziehen im Chart des Aktionsblatts (Teil von `SheetChartCanvas`): Kurs und Zeit der berührten Kerze,
// Eröffnung/Hoch/Tief/Schluss, Veränderung und Volumen. Eigene Datei, weil das Zeichnen des Charts sonst zu lang würde.
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.CompactAmount
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartType
import java.util.Date
import kotlin.math.roundToInt

/**
 * Etikett beim Ziehen über der Markierung: «98’450 USDT · Di 14:00», im Kerzen-Modus Eröffnung/Hoch/Tief/Schluss, die
 * Anzeige «+1.20%» und «Vol 12.3M USDT» ([showVolume]: nur mit Volumen-Balken). [xCenter] liefert beim Anordnen die Mitte
 * der berührten Kerze aus der zuletzt gezeichneten Geometrie.
 */
@Composable
internal fun SheetChartScrubLabel(
    candles: List<WidgetCandle>,
    type: WidgetChartType,
    index: Int,
    candle: WidgetCandle,
    showVolume: Boolean,
    quote: String,
    timeFormat: android.icu.text.DateFormat,
    xCenter: () -> Float,
    labelColor: Color,
) {
    val change = SheetChart.scrubChange(candles, type, index)
    // «Vol 12.3M USDT» der berührten Kerze, nur mit Volumen-Balken
    val volumeText = candle.volume?.takeIf { showVolume && it.isFinite() && it > 0.0 }
        ?.let {
            // Genähert (Quelle ohne Quote-Umsatz): «≈» davor
            val amount = CompactAmount.format(it, quote)
            stringResource(
                R.string.sheet_chart_volume,
                BidiText.isolate(if (candle.volumeApproximated) "≈ $amount" else amount)
            )
        }
    Column(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val centerX = xCenter()
                val left = SheetChart.labelLeft(centerX, placeable.width.toFloat(), 0f, constraints.maxWidth.toFloat())
                layout(constraints.maxWidth, placeable.height) {
                    // Absolut (nicht gespiegelt): die Zeitachse läuft immer von links nach rechts
                    placeable.place(left.roundToInt(), 0)
                }
            }
            .clip(ChipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = BidiText.isolate(PriceFormat.priceWithCurrency(candle.close, quote)) + " · " +
                timeFormat.format(Date(candle.openTime)),
            style = MaterialTheme.typography.labelMedium.amountNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (type == WidgetChartType.CANDLES) {
            OhlcReadout(candle, labelColor)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            ChangePill(change = change, dashWhenMissing = true)
            if (volumeText != null) {
                Text(
                    text = volumeText,
                    style = MaterialTheme.typography.labelSmall.amountNumbers(),
                    color = labelColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Spacing.xs)
                )
            }
        }
    }
}

/**
 * Kerzen-Modus beim Ziehen: Eröffnung · Hoch / Tief · Schluss der Kerze in zwei Zeilen,
 * Bezeichnung grau, Wert in Textfarbe.
 */
@Composable
private fun OhlcReadout(candle: WidgetCandle, labelColor: Color) {
    val valueColor = MaterialTheme.colorScheme.onSurface
    val open = stringResource(R.string.sheet_chart_open)
    val high = stringResource(R.string.ticker_high)
    val low = stringResource(R.string.ticker_low)
    val close = stringResource(R.string.sheet_chart_close)
    fun line(a: String, av: Double, b: String, bv: Double) = buildAnnotatedString {
        withStyle(SpanStyle(color = labelColor)) { append(a); append(' ') }
        withStyle(SpanStyle(color = valueColor)) { append(BidiText.isolate(PriceFormat.price(av))) }
        append("  ")
        withStyle(SpanStyle(color = labelColor)) { append(b); append(' ') }
        withStyle(SpanStyle(color = valueColor)) { append(BidiText.isolate(PriceFormat.price(bv))) }
    }
    val style = MaterialTheme.typography.labelSmall.amountNumbers()
    Text(line(open, candle.open, high, candle.high), style = style, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    Text(line(low, candle.low, close, candle.close), style = style, maxLines = 1)
}
