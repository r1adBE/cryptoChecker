// Zeichnen der Portfolio-Charts: Rahmen und Masse (`ChartFrame`), Farben/Stifte, Achse, Raster, Linien,
// Kerzen, Höchstmarke und Markierung beim Wischen.
// Eigene Datei, weil es reine Zeichenlogik ohne Zustand ist; `HistoryChart` und `HistoryCompareChart`
// nutzen sie gemeinsam (wie `PortfolioChartRenderer.swift`).
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.domain.portfolio.PortfolioCandle
import com.cryptochecker.app.domain.portfolio.PortfolioChartAxis
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.LevelKind
import com.cryptochecker.app.widget.PlacedLabel
import com.cryptochecker.app.widget.VSpan
import com.cryptochecker.app.widget.WidgetChartGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Beschriftungen ≈ 10 sp; grosse Systemschrift bis 1.5× (wie im Aktionsblatt). */
private const val LABEL_SP = 10f

private const val MAX_FONT_SCALE = 1.5f

/** Über so vielen Punkten beim Ziehen kein Ticken je Punkt — es wäre ein Dauersurren. */
internal const val MAX_TICK_POINTS = 100

/**
 * Lage aller Teile des Wertverlauf-Charts in einer Fläche von [width] × [height] Pixeln — wie
 * `WidgetChartGeometry` im Aktionsblatt: rechts eine Spalte von [column] für Beschriftungen und
 * Wert-Etikett, links davon die Zeichenfläche; oben und unten eine halbe Etiketthöhe ([edge]) frei.
 * Jeder Punkt bzw. jede Kerze belegt einen gleich breiten Slot ([spans] = Tage je Slot).
 */
internal class ChartFrame(
    val width: Float,
    val height: Float,
    column: Float,
    edge: Float,
    val spans: List<LongRange>,
    val low: Double,
    val high: Double,
) {
    val plotLeft = 0f
    val plotRight = max(plotLeft + 1f, width - column)
    val plotTop = edge
    val plotBottom = max(plotTop + 1f, height - edge)
    val slot = (plotRight - plotLeft) / spans.size.coerceAtLeast(1)

    fun x(index: Int): Float = plotLeft + (index + 0.5f) * slot

    fun y(value: Double): Float {
        val span = high - low
        if (!(span > 0.0)) return (plotTop + plotBottom) / 2f
        return (plotBottom - (value - low) / span * (plotBottom - plotTop)).toFloat()
    }

    /** Senkrechte Gitterlinien an Kalendergrenzen ([PortfolioChartAxis.gridDays]), nur innerhalb der Fläche. */
    val gridXs: List<Float> = if (spans.isEmpty()) emptyList() else {
        PortfolioChartAxis.gridDays(spans.first().first, spans.last().last)
            .mapNotNull { PortfolioChartAxis.slotPosition(spans, it) }
            .map { plotLeft + it.toFloat() * slot }
            .filter { it > plotLeft + 0.5f && it < plotRight - 0.5f }
    }

    /** Waagrechte Stufen Tief, Mitte, Hoch. */
    val levels: Triple<Double, Double, Double> = PortfolioChartAxis.levels(low, high)
}

/** Zuletzt gezeichnete Lage für das Ziehen (kein Compose-Zustand). */
internal class FrameHolder {
    var frame: ChartFrame? = null
}

/** Farben und Schriften, gemeinsam für Wert- und Vergleichs-Chart. */
internal class ChartPaint(
    val accent: Color,
    val secondary: Color,
    val marker: Color,
    val background: Color,
    val highContrast: Boolean,
    val labelStyle: TextStyle,
    val tagStyle: TextStyle,
) {
    val gridColor: Color get() = secondary.copy(alpha = secondary.alpha * if (highContrast) 0.35f else 0.12f)
    val levelColor: Color get() = secondary.copy(alpha = secondary.alpha * if (highContrast) 0.35f else 0.20f)
}

@Composable
internal fun rememberChartPaint(): ChartPaint {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = LocalDensity.current.fontScale.takeIf { it > 0f } ?: 1f
    val labelStyle = MaterialTheme.typography.labelSmall.amountNumbers().copy(
        fontSize = (LABEL_SP * min(fontScale, MAX_FONT_SCALE) / fontScale).sp,
        fontWeight = FontWeight.Medium,
        color = secondary,
    )
    return ChartPaint(
        accent = MaterialTheme.colorScheme.primary,
        secondary = secondary,
        marker = MaterialTheme.colorScheme.onSurface,
        background = MaterialTheme.colorScheme.surfaceContainer,
        highContrast = LocalHighContrast.current,
        labelStyle = labelStyle,
        tagStyle = labelStyle.copy(fontWeight = FontWeight.SemiBold, color = AppColors.onAccent),
    )
}

/** Gemessene Texte der Achse: drei Stufen und das Etikett rechts. */
internal class AxisTexts(val levels: Map<LevelKind, TextLayoutResult>, val tag: TextLayoutResult)

/** Abstände der rechten Spalte (Pixel). */
private class AxisMetrics(density: Float) {
    val gap = 3f * density
    val tagPadH = 3f * density
    val tagPadV = 1.5f * density
}

/**
 * Misst Beschriftungen ([format] je Stufe, [tagText] fürs Etikett) und legt den Rahmen an —
 * gleiche Regeln wie im Aktionsblatt (Spalte höchstens 42 % der Breite).
 */
internal fun CacheDrawScope.measureFrame(
    measurer: TextMeasurer,
    paint: ChartPaint,
    spans: List<LongRange>,
    low: Double,
    high: Double,
    format: (Double) -> String,
    tagText: String,
): Pair<ChartFrame, AxisTexts> {
    val m = AxisMetrics(density)
    val (lo, mid, hi) = PortfolioChartAxis.levels(low, high)
    val texts = AxisTexts(
        levels = mapOf(
            LevelKind.HIGH to measurer.measure(format(hi), paint.labelStyle),
            LevelKind.MID to measurer.measure(format(mid), paint.labelStyle),
            LevelKind.LOW to measurer.measure(format(lo), paint.labelStyle),
        ),
        tag = measurer.measure(tagText, paint.tagStyle),
    )
    val labelWidth = texts.levels.values.maxOf { it.size.width }.toFloat()
    val column = min(m.gap + max(labelWidth, texts.tag.size.width.toFloat()) + 2 * m.tagPadH, size.width * 0.42f)
    val labelHeight = texts.levels.values.maxOf { it.size.height }.toFloat()
    val tagHeight = texts.tag.size.height + 2 * m.tagPadV
    val edge = max(labelHeight, tagHeight) / 2f + 1f
    return ChartFrame(size.width, size.height, column, edge, spans, low, high) to texts
}

/** Etikett-Bereich: mittig auf [y], in die Fläche geschoben. */
private fun tagSpan(y: Float, tagHeight: Float, height: Float): VSpan {
    val half = tagHeight / 2f
    val center = y.coerceIn(half, max(half, height - half))
    return VSpan(center - half, center + half)
}

/** Zeitgitter und die drei Stufen (feine Linien), wie im Aktionsblatt. */
internal fun DrawScope.drawGrid(frame: ChartFrame, paint: ChartPaint) {
    val hairline = max(1f, 0.6f * density)
    for (gx in frame.gridXs) drawLine(paint.gridColor, Offset(gx, 0f), Offset(gx, size.height), hairline)
    val (lo, mid, hi) = frame.levels
    for (v in listOf(hi, mid, lo)) {
        val y = frame.y(v)
        drawLine(paint.levelColor, Offset(frame.plotLeft, y), Offset(frame.plotRight, y), hairline)
    }
}

/**
 * Etikett rechts in der Akzentfarbe auf Höhe [y] (optional mit gestrichelter Linie [dashed]) und
 * die Beschriftungen der Stufen — eine Beschriftung, die das Etikett überdecken würde, entfällt.
 */
internal fun DrawScope.drawAxis(frame: ChartFrame, paint: ChartPaint, texts: AxisTexts, y: Float, dashed: Boolean) {
    val m = AxisMetrics(density)
    val tagHeight = texts.tag.size.height + 2 * m.tagPadV
    val span = tagSpan(y, tagHeight, size.height)
    val left = frame.plotRight + m.gap
    if (dashed) {
        drawLine(
            paint.accent.copy(alpha = if (paint.highContrast) 1f else 0.7f),
            Offset(frame.plotLeft, y),
            Offset(frame.plotRight, y),
            strokeWidth = max(1f, 0.8f * density),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 2.5f * density), 0f),
        )
    }
    drawRoundRect(
        paint.accent,
        topLeft = Offset(left, span.top),
        size = Size(size.width - left, span.bottom - span.top),
        cornerRadius = CornerRadius(3f * density),
    )
    drawText(texts.tag, topLeft = Offset(left + m.tagPadH, (span.top + span.bottom) / 2f - texts.tag.size.height / 2f))
    val (lo, mid, hi) = frame.levels
    val labelHeight = texts.levels.values.maxOf { it.size.height }.toFloat()
    val placed = WidgetChartGeometry.resolveLabels(
        candidates = listOf(
            PlacedLabel(LevelKind.HIGH, hi, frame.y(hi)),
            PlacedLabel(LevelKind.LOW, lo, frame.y(lo)),
            PlacedLabel(LevelKind.MID, mid, frame.y(mid)),
        ),
        labelHeight = labelHeight,
        tag = span,
        gap = 1f * density,
    )
    for (label in placed) {
        val layout = texts.levels.getValue(label.kind)
        drawText(layout, topLeft = Offset(left + m.tagPadH, label.y - layout.size.height / 2f))
    }
}

/** Ziehen: senkrechte Markierung und Punkt bei [value] in Slot [index]. */
internal fun DrawScope.drawScrubMarker(frame: ChartFrame, paint: ChartPaint, index: Int, value: Double) {
    val mx = frame.x(index)
    drawLine(
        paint.marker.copy(alpha = 0.6f),
        Offset(mx, frame.plotTop),
        Offset(mx, frame.plotBottom),
        strokeWidth = max(1f, 1f * density),
    )
    drawCircle(paint.marker, radius = 3.5f * density, center = Offset(mx, frame.y(value)))
}

/** Pfad durch [values] (je Slot ein Wert, mittig). */
internal fun linePath(frame: ChartFrame, values: List<Double>): Path = Path().apply {
    values.forEachIndexed { i, v -> if (i == 0) moveTo(frame.x(i), frame.y(v)) else lineTo(frame.x(i), frame.y(v)) }
}

/** Beschriftung der Achse: kompakt wie im Aktionsblatt («12’345», «0.2576»); 0 als «0». */
internal fun axisValue(value: Double): String = if (value > 0.0) PriceFormat.price(value) else "0"

/** Gewinn/Verlust auf der Achse: mit Vorzeichen («+1’234», «−56.3»); praktisch 0 als «0». */
internal fun signedAxisValue(value: Double): String = when {
    PortfolioFormat.isZero(value) -> "0"
    value > 0.0 -> "+" + PriceFormat.price(value)
    else -> "−" + PriceFormat.price(abs(value))
}

/** Gestrichelte Nulllinie über die ganze Zeichenfläche (Gewinn/Verlust). */
internal fun DrawScope.drawZeroLine(frame: ChartFrame, color: Color) {
    val zero = frame.y(0.0)
    drawLine(
        color = color.copy(alpha = 0.7f),
        start = Offset(frame.plotLeft, zero),
        end = Offset(frame.plotRight, zero),
        strokeWidth = max(1f, 1f * density),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 4f * density))
    )
}

/**
 * Gewinn/Verlust als Linie: über der Nulllinie in [up], darunter in [down] (je an der Nulllinie
 * abgeschnitten), zarte Fläche bis zur Nulllinie.
 */
internal fun DrawScope.drawPnlLine(frame: ChartFrame, values: List<Double>, up: Color, down: Color) {
    val zero = frame.y(0.0)
    val line = linePath(frame, values)
    val fill = Path().apply {
        addPath(line)
        lineTo(frame.x(values.lastIndex), zero)
        lineTo(frame.x(0), zero)
        close()
    }
    val stroke = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    clipRect(top = 0f, bottom = zero) {
        drawPath(fill, up.copy(alpha = 40f / 255f))
        drawPath(line, up, style = stroke)
    }
    clipRect(top = zero, bottom = size.height) {
        drawPath(fill, down.copy(alpha = 40f / 255f))
        drawPath(line, down, style = stroke)
    }
}

/** Linie mit zarter Fläche bis zur Grundlinie (wie die Linie im Aktionsblatt). */
internal fun DrawScope.drawValueLine(frame: ChartFrame, values: List<Double>, color: Color) {
    val line = linePath(frame, values)
    val fill = Path().apply {
        addPath(line)
        lineTo(frame.x(values.lastIndex), frame.plotBottom)
        lineTo(frame.x(0), frame.plotBottom)
        close()
    }
    drawPath(fill, color.copy(alpha = 40f / 255f))
    drawPath(line, color, style = Stroke(width = 1.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Kerzen: Körper 65 % des Slots, Docht höchstens 1 dp (wie im Aktionsblatt). */
internal fun DrawScope.drawCandles(frame: ChartFrame, candles: List<PortfolioCandle>, up: Color, down: Color) {
    val bodyWidth = max(1f, frame.slot * WidgetChartGeometry.CANDLE_WIDTH_RATIO)
    val wick = min(1f * density, bodyWidth)
    candles.forEachIndexed { i, c ->
        val color = if (c.up) up else down
        val cx = frame.x(i)
        val yo = frame.y(c.open)
        val yc = frame.y(c.close)
        var top = min(yo, yc)
        var bottom = max(yo, yc)
        if (bottom - top < 1f) {
            // Körper mindestens 1 px hoch
            bottom = min(top + 1f, frame.plotBottom)
            top = bottom - 1f
        }
        val wickTop = frame.y(c.high)
        val wickBottom = frame.y(c.low)
        drawRect(color, Offset(cx - wick / 2f, wickTop), Size(wick, wickBottom - wickTop))
        drawRect(color, Offset(cx - bodyWidth / 2f, top), Size(bodyWidth, bottom - top))
    }
}

/**
 * Höchster Tageswert: kleiner Punkt in Slot [slot] auf [value] und «Hoch 12’345» daneben (rechts
 * davon in der linken Hälfte, sonst links), senkrecht mittig, ganz in der Zeichenfläche.
 */
internal fun DrawScope.drawHighMark(frame: ChartFrame, paint: ChartPaint, slot: Int, value: Double, layout: TextLayoutResult) {
    val mx = frame.x(slot)
    val my = frame.y(value)
    drawCircle(paint.background, radius = 3.5f * density, center = Offset(mx, my))
    drawCircle(paint.marker, radius = 2.5f * density, center = Offset(mx, my))
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val pad = 2f * density
    val gap = 5f * density
    val rightSide = mx < (frame.plotLeft + frame.plotRight) / 2f
    val rawLeft = if (rightSide) mx + gap else mx - gap - w
    val left = rawLeft.coerceIn(frame.plotLeft + pad, max(frame.plotLeft + pad, frame.plotRight - w - pad))
    val top = (my - h / 2f).coerceIn(0f, max(0f, size.height - h))
    drawRoundRect(
        paint.background.copy(alpha = 0.85f),
        topLeft = Offset(left - pad, top),
        size = Size(w + 2 * pad, h),
        cornerRadius = CornerRadius(3f * density),
    )
    drawText(layout, topLeft = Offset(left, top))
}
