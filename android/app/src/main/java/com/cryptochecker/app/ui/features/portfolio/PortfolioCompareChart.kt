// Vergleichs-Chart der Verlaufskarte: Wert in der gewählten Währung gegen USDT in Prozent (Währungseffekt).
// Eigene Datei, weil er eine eigene Ansicht mit eigener Legende ist (wie `PortfolioCompareChart.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.PortfolioCompare
import com.cryptochecker.app.domain.portfolio.PortfolioCompareSeries
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.amountNumbers

/**
 * Vergleich in Prozent seit dem Ausgangspunkt mit demselben Gitter wie [HistoryChart]: Stufen in
 * Prozent rechts, 0 % gestrichelt, die gewählte Währung kräftig in [color], USDT dünn und grau,
 * Etikett mit dem heutigen Prozentwert der Währung (nur Linien, keine Kerzen). Ziehen wie bei
 * [HistoryChart] — der Tag geht an [onScrub] (die Karte zeigt darunter den Währungseffekt dieses
 * Tags), das Etikett zeigt «7. Okt. 2026 · CHF +7.00% · USDT +12.10%». Prozente bleiben auch bei
 * «Beträge verbergen» sichtbar.
 */
@Composable
internal fun HistoryCompareChart(
    compare: PortfolioCompareSeries,
    currency: String,
    color: Color,
    scrub: Int?,
    onScrub: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val paint = rememberChartPaint()
    val zeroLine = MaterialTheme.colorScheme.outline
    val holder = remember { FrameHolder() }
    val currentOnScrub by rememberUpdatedState(onScrub)
    val bounds = remember(compare) { PortfolioCompare.bounds(compare) }
    val spans = remember(compare) { compare.epochDays.map { it..it } }
    val count = spans.size

    Box(
        modifier = modifier.historyScrub(
            key = compare,
            count = count,
            haptics = haptics,
            indexAt = { x -> holder.frame?.let { SheetChart.scrubIndex(x, it.plotLeft, it.slot, count) } },
        ) { currentOnScrub(it) }
            .drawWithCache {
                if (count < 2) return@drawWithCache onDrawBehind { }
                val format: (Double) -> String = { PortfolioFormat.signedPercent(it) }
                val (frame, texts) = measureFrame(
                    measurer, paint, spans, bounds.first, bounds.second, format, format(compare.currency.last())
                )
                holder.frame = frame
                drawContent(frame, paint) {
                    // 0 %: gestrichelt über die ganze Zeichenfläche
                    val zero = frame.y(0.0)
                    drawLine(
                        color = zeroLine.copy(alpha = 0.7f),
                        start = Offset(frame.plotLeft, zero),
                        end = Offset(frame.plotRight, zero),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    )
                    drawPath(
                        linePath(frame, compare.usdt),
                        paint.secondary,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        linePath(frame, compare.currency),
                        color,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    val i = scrub
                    if (i != null && i in 0..compare.lastIndex) {
                        drawCircle(paint.secondary, radius = 2.5.dp.toPx(), center = Offset(frame.x(i), frame.y(compare.usdt[i])))
                        drawScrubMarker(frame, paint, i, compare.currency[i])
                    }
                    // Etikett ohne eigene Linie (die 0-%-Linie ist schon gestrichelt)
                    drawAxis(frame, paint, texts, frame.y(compare.currency.last()).coerceIn(frame.plotTop, frame.plotBottom), dashed = false)
                }
            }
    ) {
        val index = scrub
        if (index != null && index in 0..compare.lastIndex) {
            Text(
                text = PortfolioFormat.date(dayMillis(compare.epochDays[index])) + " · " +
                    compareValuesText(compare, currency, index),
                style = MaterialTheme.typography.labelMedium.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .scrubLabelPlacement { holder.frame?.x(index) ?: 0f }
                    .clip(ChipShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** «CHF +7.00% · USDT +12.10%» für den Tag [index]. */
private fun compareValuesText(compare: PortfolioCompareSeries, currency: String, index: Int): String =
    "$currency ${PortfolioFormat.signedPercent(compare.currency[index])} · " +
        "${PortfolioFormat.USDT} ${PortfolioFormat.signedPercent(compare.usdt[index])}"
