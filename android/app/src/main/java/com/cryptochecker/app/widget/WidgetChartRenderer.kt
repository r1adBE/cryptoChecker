package com.cryptochecker.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.cryptochecker.app.domain.portfolio.PortfolioValuePoint
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Zeichnet den Chart des Einzel-Widgets (Kerzen oder Linie) als Bitmap: schwaches
 * Zeitgitter, drei Preisstufen mit Beschriftung rechts, gestrichelte Akzentlinie und
 * Kurs-Etikett in der Akzentfarbe. Lage aller Teile aus [WidgetChartGeometry].
 */
object WidgetChartRenderer {

    /** Beschriftungen ≈ 9.5 sp, auf kleiner Fläche 8.5 sp. */
    private const val TEXT_SP = 9.5f
    private const val TEXT_SP_COMPACT = 8.5f
    /** Grosse Systemschrift nur begrenzt übernehmen, sonst frisst die Spalte den Chart. */
    private const val MAX_FONT_SCALE = 1.3f
    /** Die Beschriftungsspalte nimmt höchstens diesen Anteil der Breite ein. */
    private const val MAX_COLUMN_RATIO = 0.42f

    fun draw(
        candles: List<WidgetCandle>,
        type: WidgetChartType,
        range: WidgetChartRange,
        colors: WidgetColors,
        highContrast: Boolean,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        fontScale: Float,
        compact: Boolean,
        formatPrice: (Double) -> String,
        /** Kurs im Etikett (wie die Überschrift); null = letzter Schluss. */
        currentPrice: Double? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Bitmap {
        val w = widthPx.coerceAtLeast(2)
        val h = heightPx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val dp = density

        // --- Text und Beschriftungsspalte messen ---
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            fontFeatureSettings = "tnum"
            textSize = (if (compact) TEXT_SP_COMPACT else TEXT_SP) * dp * fontScale.coerceIn(0.85f, MAX_FONT_SCALE)
        }
        val tagTextPaint = Paint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD }
        val gap = 3f * dp
        val tagPadH = 3f * dp
        val tagPadV = 1.5f * dp

        val levels = WidgetChartGeometry.levels(candles, type)
        val labelTexts = listOf(levels.high, levels.mid, levels.low).map(formatPrice)
        val tagPrice = currentPrice?.takeIf { it > 0.0 } ?: candles.last().close
        val tagText = formatPrice(tagPrice)
        fun columnWidth(): Float {
            val labels = labelTexts.maxOf { textPaint.measureText(it) }
            val tag = tagTextPaint.measureText(tagText)
            return gap + max(labels, tag) + 2 * tagPadH
        }
        var column = columnWidth()
        val maxColumn = w * MAX_COLUMN_RATIO
        if (column > maxColumn) {
            // Schmales Widget: Schrift verkleinern statt überlappen
            val scale = max(0.6f, (maxColumn - gap - 2 * tagPadH) / (column - gap - 2 * tagPadH))
            textPaint.textSize *= scale
            tagTextPaint.textSize *= scale
            column = min(columnWidth(), maxColumn)
        }
        val fm = textPaint.fontMetrics
        val labelHeight = fm.descent - fm.ascent
        val tfm = tagTextPaint.fontMetrics
        val tagHeight = (tfm.descent - tfm.ascent) + 2 * tagPadV

        val geo = WidgetChartGeometry(
            candles = candles,
            type = type,
            gridUnit = range.gridUnit,
            intervalMillis = range.intervalMillis,
            zone = zone,
            width = w.toFloat(),
            height = h.toFloat(),
            labelColumnWidth = column,
            labelHeight = labelHeight,
            tagHeight = tagHeight,
            compact = compact,
            gap = 1f * dp,
            currentPrice = tagPrice,
        )

        // --- Farben ---
        val secondary = colors.secondaryTextColor
        val hairline = max(1f, 0.6f * dp)
        // Hoher Kontrast: Beschriftung in voller Nebentextfarbe, Gitter und Stufen mit 35 %
        val gridPaint = Paint().apply {
            color = withAlpha(secondary, if (highContrast) 0.35f else 0.12f)
            strokeWidth = hairline
            style = Paint.Style.STROKE
        }
        val levelPaint = Paint().apply {
            color = withAlpha(secondary, if (highContrast) 0.35f else 0.20f)
            strokeWidth = hairline
            style = Paint.Style.STROKE
        }
        textPaint.color = secondary

        // --- Zeitgitter ---
        for (x in geo.gridXs) canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint)

        // --- Preisstufen (Linien; Beschriftung weiter unten) ---
        val levelPrices = buildList {
            add(levels.high)
            if (!compact) add(levels.mid)
            add(levels.low)
        }
        for (price in levelPrices) {
            val y = geo.y(price)
            canvas.drawLine(geo.plotLeft, y, geo.plotRight, y, levelPaint)
        }

        // --- Kerzen oder Linie ---
        when (type) {
            WidgetChartType.CANDLES -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                for (c in geo.candleShapes) {
                    paint.color = if (c.up) colors.upColor else colors.downColor
                    val wick = min(1f * dp, c.bodyWidth)
                    canvas.drawRect(c.x - wick / 2f, c.wickTop, c.x + wick / 2f, c.wickBottom, paint)
                    canvas.drawRect(c.x - c.bodyWidth / 2f, c.bodyTop, c.x + c.bodyWidth / 2f, c.bodyBottom, paint)
                }
            }
            WidgetChartType.LINE -> {
                val color = if (geo.up) colors.upColor else colors.downColor
                val line = Path()
                geo.linePoints.forEachIndexed { i, (x, y) -> if (i == 0) line.moveTo(x, y) else line.lineTo(x, y) }
                val fill = Path(line).apply {
                    lineTo(geo.linePoints.last().first, geo.plotBottom)
                    lineTo(geo.linePoints.first().first, geo.plotBottom)
                    close()
                }
                canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    alpha = 40
                    style = Paint.Style.FILL
                })
                canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    strokeWidth = 1.5f * dp
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                })
            }
        }

        // --- Aktueller Kurs: gestrichelte Akzentlinie und Etikett ---
        canvas.drawLine(geo.plotLeft, geo.currentY, geo.plotRight, geo.currentY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(colors.accentColor, if (highContrast) 1f else 0.7f)
            strokeWidth = max(1f, 0.8f * dp)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(3f * dp, 2.5f * dp), 0f)
        })
        val tagLeft = geo.plotRight + gap
        val tagRect = RectF(tagLeft, geo.tagSpan.top, w.toFloat(), geo.tagSpan.bottom)
        canvas.drawRoundRect(tagRect, 3f * dp, 3f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.accentColor
            style = Paint.Style.FILL
        })
        tagTextPaint.color = colors.onAccentColor
        canvas.drawText(tagText, tagLeft + tagPadH, tagRect.centerY() - (tfm.ascent + tfm.descent) / 2f, tagTextPaint)

        // --- Beschriftungen der Preisstufen (rechts, bündig mit dem Etikett-Text) ---
        for (label in geo.labels) {
            val text = formatPrice(label.price)
            canvas.drawText(text, tagLeft + tagPadH, label.y - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        return bitmap
    }

    /**
     * Wertverlauf des Portfolio-Widgets (24 h): Linie 1.75 dp in [color] (Kursfarbe der
     * Richtung), Fläche darunter als Verlauf von 25 % an der Linie bis 0 unten, gestrichelte
     * schwache Linie beim Ausgangswert (erster Punkt), kleiner Punkt am letzten Wert. Keine
     * Achsen, keine Beschriftung. Lage aus [PortfolioWidgetMath.linePoints].
     * [axis]: feste Zeitachse (Tages-Basis: Tagesbeginn bis Tagesende) — der Verlauf füllt
     * nur den bisherigen Teil des Tages; null = erster bis letzter Punkt.
     */
    fun drawPortfolioArea(
        points: List<PortfolioValuePoint>,
        color: Int,
        baselineColor: Int,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        axis: Pair<Long, Long>? = null,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(2)
        val h = heightPx.coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = 1.75f * density
        val dot = 2.5f * density
        // Rand: Platz für den Punkt am Ende und runde Linienenden
        val inset = dot + stroke / 2f
        val xy = PortfolioWidgetMath.linePoints(points, w - 2f * inset, h.toFloat(), inset, axis?.first, axis?.second)
            .map { (x, y) -> x + inset to y }
        if (xy.size < 2) return bitmap
        val line = Path()
        xy.forEachIndexed { i, (x, y) -> if (i == 0) line.moveTo(x, y) else line.lineTo(x, y) }
        val fill = Path(line).apply {
            lineTo(xy.last().first, h.toFloat())
            lineTo(xy.first().first, h.toFloat())
            close()
        }
        val top = xy.minOf { it.second }
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, top, 0f, h.toFloat(),
                withAlpha(color, 0.25f), withAlpha(color, 0f),
                Shader.TileMode.CLAMP,
            )
        })
        PortfolioWidgetMath.valueY(points, points.first().value, h.toFloat(), inset)?.let { y ->
            canvas.drawLine(xy.first().first, y, xy.last().first, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(baselineColor, 0.45f)
                strokeWidth = max(1f, 0.75f * density)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
            })
        }
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = stroke
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        })
        val (lx, ly) = xy.last()
        canvas.drawCircle(lx, ly, dot, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        })
        return bitmap
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
