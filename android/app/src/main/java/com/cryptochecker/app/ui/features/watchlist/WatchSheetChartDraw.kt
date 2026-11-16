package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.widget.LevelKind
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import com.cryptochecker.app.domain.widget.WidgetChartType
import kotlin.math.max
import kotlin.math.min

/** Farben des Blatt-Charts ([SheetChartCanvas]); [highContrast] macht Gitter, Stufen und Volumen kräftiger. */
internal data class SheetChartPaint(
    val up: Color,
    val down: Color,
    val accent: Color,
    val secondary: Color,
    val marker: Color,
    val highContrast: Boolean,
)

/** Gemessene Texte rechts: Etikett des aktuellen Kurses und die drei Preisstufen, mit Abstand und Innenrand. */
internal class SheetChartTag(
    val layout: TextLayoutResult,
    val levelLayouts: Map<LevelKind, TextLayoutResult>,
    val gap: Float,
    val padH: Float,
)

/**
 * Zeichnet die fertige Geometrie ([WidgetChartGeometry]) des Blatt-Charts: Zeitgitter, Preisstufen, Volumen, Kerzen bzw.
 * Linie, aktueller Kurs mit Etikett, Beschriftungen und beim Ziehen die Markierung. [geometry] und [scrub] werden erst
 * beim Zeichnen gelesen (Ziehen zeichnet nur neu, ohne Neuaufbau).
 */
internal fun Modifier.sheetChartDrawing(
    geometry: () -> WidgetChartGeometry?,
    scrub: () -> Int?,
    type: WidgetChartType,
    volumes: List<Float>?,
    paint: SheetChartPaint,
    tag: SheetChartTag,
): Modifier = drawWithCache {
    val dp = density
    val geo = geometry() ?: return@drawWithCache onDrawBehind { }
    val shown = geo.candles

    val hairline = max(1f, 0.6f * dp)
    val gridColor = paint.secondary.copy(alpha = paint.secondary.alpha * if (paint.highContrast) 0.35f else 0.12f)
    val levelColor = paint.secondary.copy(alpha = paint.secondary.alpha * if (paint.highContrast) 0.35f else 0.20f)
    val lineColor = if (geo.up) paint.up else paint.down
    val linePath = Path().apply {
        geo.linePoints.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(px, py) else lineTo(px, py) }
    }
    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(geo.linePoints.last().first, geo.plotBottom)
        lineTo(geo.linePoints.first().first, geo.plotBottom)
        close()
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f * dp, 2.5f * dp), 0f)
    val tagLeft = geo.plotRight + tag.gap

    onDrawBehind {
        // Zeitgitter und Preisstufen
        for (gx in geo.gridXs) drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), hairline)
        for (price in listOf(geo.levels.high, geo.levels.mid, geo.levels.low)) {
            val y = geo.y(price)
            drawLine(levelColor, Offset(geo.plotLeft, y), Offset(geo.plotRight, y), hairline)
        }
        // Volumen unten (dezent, hinter Kerzen bzw. Linie)
        if (volumes != null) {
            val zoneHeight = (geo.plotBottom - geo.plotTop) * SheetChart.VOLUME_HEIGHT_FRACTION
            val barWidth = max(1f * dp, geo.slotWidth * 0.6f)
            val volumeAlpha = if (paint.highContrast) 0.40f else 0.22f
            volumes.forEachIndexed { i, fraction ->
                val c = shown.getOrNull(i)
                if (c != null && fraction > 0f) {
                    val h = max(1f * dp, zoneHeight * fraction)
                    // Nur genäherter Umsatz (Menge × Schluss): halb so kräftig
                    val alpha = if (c.volumeApproximated) volumeAlpha * 0.5f else volumeAlpha
                    val color = (if (c.up) paint.up else paint.down).copy(alpha = alpha)
                    drawRect(color, Offset(geo.xCenter(i) - barWidth / 2f, geo.plotBottom - h), Size(barWidth, h))
                }
            }
        }
        // Kerzen oder Linie
        when (type) {
            WidgetChartType.CANDLES -> for (c in geo.candleShapes) {
                val color = if (c.up) paint.up else paint.down
                val wick = min(1f * dp, c.bodyWidth)
                drawRect(color, Offset(c.x - wick / 2f, c.wickTop), Size(wick, c.wickBottom - c.wickTop))
                drawRect(color, Offset(c.x - c.bodyWidth / 2f, c.bodyTop), Size(c.bodyWidth, c.bodyBottom - c.bodyTop))
            }
            WidgetChartType.LINE -> {
                drawPath(fillPath, lineColor.copy(alpha = 40f / 255f))
                drawPath(
                    linePath,
                    lineColor,
                    style = Stroke(width = 1.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        // Aktueller Kurs: gestrichelte Akzentlinie und Etikett
        drawLine(
            paint.accent.copy(alpha = if (paint.highContrast) 1f else 0.7f),
            Offset(geo.plotLeft, geo.currentY),
            Offset(geo.plotRight, geo.currentY),
            strokeWidth = max(1f, 0.8f * dp),
            pathEffect = dash,
        )
        drawRoundRect(
            paint.accent,
            topLeft = Offset(tagLeft, geo.tagSpan.top),
            size = Size(size.width - tagLeft, geo.tagSpan.bottom - geo.tagSpan.top),
            cornerRadius = CornerRadius(3f * dp),
        )
        drawText(
            tag.layout,
            topLeft = Offset(tagLeft + tag.padH, (geo.tagSpan.top + geo.tagSpan.bottom) / 2f - tag.layout.size.height / 2f),
        )
        // Beschriftungen der Preisstufen (das Etikett gewinnt)
        for (label in geo.labels) {
            val layout = tag.levelLayouts.getValue(label.kind)
            drawText(layout, topLeft = Offset(tagLeft + tag.padH, label.y - layout.size.height / 2f))
        }
        // Ziehen: senkrechte Markierung und Punkt auf dem Schluss
        scrub()?.let { i ->
            val candle = shown.getOrNull(i) ?: return@let
            val mx = geo.xCenter(i)
            drawLine(
                paint.marker.copy(alpha = 0.6f),
                Offset(mx, geo.plotTop),
                Offset(mx, geo.plotBottom),
                strokeWidth = max(1f, 1f * dp),
            )
            drawCircle(paint.marker, radius = 3.5f * dp, center = Offset(mx, geo.y(candle.close)))
        }
    }
}
