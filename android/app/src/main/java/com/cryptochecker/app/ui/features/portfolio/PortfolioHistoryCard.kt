package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Kurzname auf dem Chip («30 T») und ausgeschrieben («30 Tage») für Screenreader und Chart-Satz. */
private val PortfolioHistoryRange.shortRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_range_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_range_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_range_1y
    }

private val PortfolioHistoryRange.longRes: Int
    get() = when (this) {
        PortfolioHistoryRange.WEEK -> R.string.portfolio_history_period_7d
        PortfolioHistoryRange.MONTH -> R.string.portfolio_history_period_30d
        PortfolioHistoryRange.YEAR -> R.string.portfolio_history_period_1y
    }

/** «+1’234.56 CHF» / «−12.00 CHF» / «0.00 CHF» (bei praktisch 0 ohne Vorzeichen). */
private fun signedValue(value: Double, unit: String): String {
    val sign = when {
        PortfolioFormat.isZero(value) -> ""
        value > 0 -> "+"
        else -> "−"
    }
    return sign + PriceFormat.valueWithCurrency(abs(value), unit)
}

/** Mittag des Tags (lokal) in ms — für die Datumsbeschriftung. */
private fun dayMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Wertverlauf über den Positionen: Zeitraum-Chips (7 T / 30 T / 1 J), Änderung über den
 * Zeitraum (Betrag und Prozent mit Vorzeichen, Pfeil und Kursfarbe), Linie mit Fläche
 * und Hinweise (umgerechnet, Coins ohne Tageskurse, Käufe/Verkäufe im Zeitraum).
 * [history] null = lädt (Platzhalter). Ohne Bewegung gezeichnet (kein Aufdecken).
 */
@Composable
internal fun PortfolioHistoryCard(
    history: PortfolioHistoryUi?,
    range: PortfolioHistoryRange,
    onRange: (PortfolioHistoryRange) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.portfolio_history_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PortfolioHistoryRange.entries.forEach { option ->
                        val spoken = stringResource(
                            R.string.portfolio_history_range_a11y, stringResource(option.longRes)
                        )
                        FilterChip(
                            selected = option == range,
                            onClick = { onRange(option) },
                            label = {
                                Text(
                                    stringResource(option.shortRes),
                                    maxLines = 1,
                                    modifier = Modifier.semantics { contentDescription = spoken }
                                )
                            }
                        )
                    }
                }
            }

            // Beim Wechsel des Zeitraums bleibt der bisherige Verlauf stehen, bis der neue gerechnet ist
            if (history == null) HistorySkeleton() else HistoryContent(history)
        }
    }
}

@Composable
private fun HistoryContent(history: PortfolioHistoryUi) {
    val series = history.series
    val context = LocalContext.current
    val period = stringResource(history.range.longRes)
    if (series.hasChart) {
        val change = series.change ?: 0.0
        val color = plColor(change)
        val arrow = if (PortfolioFormat.isZero(change)) "" else PriceFormat.changeArrow(change)
        val amount = signedValue(change, history.unit).let { if (arrow.isEmpty()) it else "$arrow $it" }
        val spokenAmount = when {
            PortfolioFormat.isZero(change) -> stringResource(R.string.a11y_change_flat)
            change > 0 -> stringResource(R.string.a11y_change_up, PriceFormat.valueWithCurrency(abs(change), history.unit))
            else -> stringResource(R.string.a11y_change_down, PriceFormat.valueWithCurrency(abs(change), history.unit))
        }
        val spokenPercent = series.changePercent?.let { A11yText.change(context, it) }
        val spokenChange = if (spokenPercent != null) {
            stringResource(R.string.portfolio_history_change_a11y, period, spokenAmount, spokenPercent)
        } else {
            "$period: $spokenAmount"
        }

        // Änderung über den Zeitraum — ein Satz für den Screenreader
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clearAndSetSemantics { contentDescription = spokenChange }
        ) {
            Text(
                amount,
                style = MaterialTheme.typography.titleMedium.amountNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (series.changePercent != null) {
                PlPill(series.changePercent, modifier = Modifier.padding(start = 8.dp))
            }
        }

        val values = series.points.map { it.value }
        val chartSentence = A11yText.chart(context, period, values) { PriceFormat.valueWithCurrency(it, history.unit) }
        HistoryChart(
            values = values,
            change = change,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(top = 10.dp)
                .clearAndSetSemantics { contentDescription = chartSentence }
        )
        // Beginn und Ende der Achse (für Screenreader im Chart-Satz enthalten)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clearAndSetSemantics { }
        ) {
            Text(
                PortfolioFormat.date(dayMillis(series.points.first().epochDay)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.portfolio_history_today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        PortfolioHint(
            stringResource(
                if (series.points.isEmpty()) R.string.portfolio_history_unavailable
                else R.string.portfolio_history_too_short
            )
        )
    }

    // Hinweise unter dem Chart
    if (series.hasChart && history.converted) {
        HistoryCaption(stringResource(R.string.portfolio_history_converted))
    }
    if (series.skipped.isNotEmpty()) {
        HistoryCaption(stringResource(R.string.portfolio_history_without, series.skipped.joinToString(", ")))
    }
    if (series.hasChart && series.tradesInRange) {
        HistoryCaption(stringResource(R.string.portfolio_history_includes_trades))
    }
}

@Composable
private fun HistoryCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** Platzhalter in der Form von Änderung und Chart. */
@Composable
private fun HistorySkeleton() {
    val loading = stringResource(R.string.portfolio_history_loading)
    SkeletonPulse(modifier = Modifier.fillMaxWidth().semantics { contentDescription = loading }) {
        SkeletonLine(
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 6.dp).width(150.dp)
        )
        SkeletonBlock(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(140.dp),
            corner = 12.dp
        )
        SkeletonLine(
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp).width(80.dp)
        )
    }
}

/**
 * Linie mit sanfter Fläche (wie das Mini-Chart der Merkliste), Farbe nach der Änderung
 * über den Zeitraum (grau bei praktisch 0). Hoher Kontrast: Fläche kräftiger.
 */
@Composable
private fun HistoryChart(values: List<Double>, change: Double, modifier: Modifier = Modifier) {
    val color = plColor(change)
    val fillAlpha = if (LocalHighContrast.current) 0.20f else 0.12f
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val inset = stroke
        val w = size.width - 2 * inset
        val h = size.height - 2 * inset
        if (w <= 0f || h <= 0f || values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = max - min
        val path = Path()
        val area = Path()
        var top = size.height
        values.forEachIndexed { i, v ->
            val x = inset + w * i / values.lastIndex
            val y = if (range > 0.0) inset + h - ((v - min) / range * h).toFloat() else inset + h / 2
            if (y < top) top = y
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(inset + w, size.height)
        area.lineTo(inset, size.height)
        area.close()
        // Grundlinie unten, dezent
        drawLine(
            color = baseline,
            start = Offset(0f, size.height - 0.5f),
            end = Offset(size.width, size.height - 0.5f),
            strokeWidth = 1f
        )
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)),
                startY = top,
                endY = size.height,
            ),
        )
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
