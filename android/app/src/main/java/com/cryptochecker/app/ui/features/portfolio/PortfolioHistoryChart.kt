package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryPoint
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Linie mit sanfter Fläche (wie das Mini-Chart der Merkliste), Farbe nach der Änderung
 * über den Zeitraum (grau bei praktisch 0). Hoher Kontrast: Fläche kräftiger.
 * Lange drücken oder waagrecht ziehen (wie das Chart im Aktionsblatt) zeigt Markierung, Punkt
 * und «7. Okt. 2026 · 12’345.67 CHF» des nächsten Tages; Loslassen blendet beides aus.
 * Senkrechtes Wischen bleibt dem Bildschirm (Scrollen).
 */
@Composable
internal fun HistoryChart(
    points: List<PortfolioHistoryPoint>,
    unit: String,
    change: Double,
    modifier: Modifier = Modifier,
) {
    val color = plColor(change)
    val fillAlpha = if (LocalHighContrast.current) 0.20f else 0.12f
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val haptics = LocalHapticFeedback.current
    val values = remember(points) { points.map { it.value } }
    var scrub by remember(points) { mutableStateOf<Int?>(null) }
    // Rand für die Strichbreite — gleich beim Zeichnen und beim Ziehen
    val insetPx = with(LocalDensity.current) { 2.dp.toPx() }

    Box(
        modifier = modifier.pointerInput(points) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var total = Offset.Zero
                var x = down.position.x
                val start: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var decided: Boolean? = null
                    while (decided == null) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed) {
                            decided = false
                        } else {
                            total += change.positionChange()
                            x = change.position.x
                            if (abs(total.x) > slop && abs(total.x) > abs(total.y)) {
                                change.consume()
                                decided = true
                            } else if (abs(total.y) > slop) {
                                decided = false
                            }
                        }
                    }
                    decided
                }
                // null = lange gedrückt, ohne sich zu bewegen
                if (start == false) return@awaitEachGesture

                fun show(atX: Float) {
                    val index = PortfolioHistory.scrubIndex(atX, insetPx, size.width - 2 * insetPx, points.size)
                    // Leichtes Ticken je neuem Tag (folgt den System-Einstellungen)
                    if (index != null && index != scrub) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    scrub = index
                }
                show(x)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    show(change.position.x)
                }
                scrub = null
            }
        }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 2.dp.toPx()
            val inset = stroke
            val w = size.width - 2 * inset
            val h = size.height - 2 * inset
            if (w <= 0f || h <= 0f || values.size < 2) return@Canvas
            val min = values.min()
            val max = values.max()
            val range = max - min
            fun yOf(v: Double): Float = if (range > 0.0) inset + h - ((v - min) / range * h).toFloat() else inset + h / 2
            fun xOf(i: Int): Float = inset + w * i / values.lastIndex
            val path = Path()
            val area = Path()
            var top = size.height
            values.forEachIndexed { i, v ->
                val x = xOf(i)
                val y = yOf(v)
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
            // Ziehen: senkrechte Markierung und Punkt auf dem Wert
            scrub?.let { i ->
                val v = values.getOrNull(i) ?: return@let
                val mx = xOf(i)
                drawLine(marker.copy(alpha = 0.6f), Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(marker, radius = 3.5.dp.toPx(), center = Offset(mx, yOf(v)))
            }
        }

        // Etikett beim Ziehen: «7. Okt. 2026 · 12’345.67 CHF», über der Markierung
        val index = scrub
        val point = index?.let { points.getOrNull(it) }
        if (index != null && point != null) {
            Text(
                text = PortfolioFormat.date(dayMillis(point.epochDay)) + " · " +
                    maskAmount(PriceFormat.valueWithCurrency(point.value, unit)),
                style = MaterialTheme.typography.labelMedium.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val width = constraints.maxWidth.toFloat()
                        val w = width - 2 * insetPx
                        val centerX = if (points.size > 1) insetPx + w * index / points.lastIndex else width / 2f
                        val left = SheetChart.labelLeft(centerX, placeable.width.toFloat(), 0f, width)
                        layout(constraints.maxWidth, placeable.height) {
                            // Absolut (nicht gespiegelt): die Zeitachse läuft immer von links nach rechts
                            placeable.place(left.roundToInt(), 0)
                        }
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
