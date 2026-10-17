package com.cryptochecker.app.widget

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/** Ein Punkt in Pixeln. */
data class ChartXY(val x: Float, val y: Float)

/**
 * Reine Geometrie des Portfolio-Widget-Charts (ohne Android, getestet in PortfolioWidgetChartTest;
 * gespiegelt in `PortfolioWidgetChartGeometry`, Shared/Portfolio/PortfolioWidgetPlan.swift) — Aussehen
 * wie die Charts der App (Aktionsblatt, Wertverlauf): drei zarte waagrechte Hilfslinien (Hoch, Mitte,
 * Tief), feine senkrechte Linien an Kalendergrenzen, Kerzen (Körper 65 % ihrer Zeit) oder Linie, der
 * höchste Wert als Punkt mit «Hoch …» daneben. Keine Beschriftungsspalte (zu wenig Platz im Widget).
 *
 * x folgt der Zeit über die Achse [axis] (ohne: Beginn des ersten bis Ende des letzten Balkens) —
 * so bleibt bei der Tages-Basis der Rest des Tags frei. Die Zeichenfläche reicht von [inset]
 * links/rechts bis Breite − [inset] und von [topInset] bis Höhe − [bottomInset].
 */
class PortfolioWidgetChartGeometry(
    val bars: List<ChartBar>,
    val type: WidgetChartType,
    axis: Pair<Long, Long>?,
    gridUnit: ChartGridUnit,
    zone: ZoneId,
    val width: Float,
    val height: Float,
    inset: Float,
    topInset: Float,
    bottomInset: Float,
) {
    init {
        require(bars.size >= 2) { "mindestens zwei Balken" }
    }

    val plotLeft: Float = inset
    val plotRight: Float = max(plotLeft + 1f, width - inset)
    val plotTop: Float = topInset
    val plotBottom: Float = max(plotTop + 1f, height - bottomInset)

    /** Skala: Kerzen tiefstes Tief bis höchstes Hoch, Linie tiefster bis höchster Schluss. */
    val low: Double = when (type) {
        WidgetChartType.CANDLES -> bars.minOf { it.low }
        WidgetChartType.LINE -> bars.minOf { it.close }
    }
    val high: Double = when (type) {
        WidgetChartType.CANDLES -> bars.maxOf { it.high }
        WidgetChartType.LINE -> bars.maxOf { it.close }
    }

    /**
     * Achse: [low]..[high], aber mindestens [WidgetChartGeometry.MIN_SPAN] der Mitte hoch (wie Einzel-Widget
     * und Aktionsblatt) — ein ruhiger Tag sieht so nicht wie ein Absturz aus.
     */
    val axisLevels: ChartLevels =
        WidgetChartGeometry.widenLevels(ChartLevels(low, (low + high) / 2.0, high), WidgetChartGeometry.MIN_SPAN)

    private val axisStart: Long
    private val axisEnd: Long

    init {
        val fixed = axis?.takeIf { it.second > it.first }
        axisStart = fixed?.first ?: bars.first().start
        axisEnd = fixed?.second ?: bars.maxOf { it.end }
    }

    /** Gesamtrichtung (letzter Schluss ≥ erster Wert) — Farbe der Linie ohne bekannte Veränderung. */
    val up: Boolean = when (type) {
        WidgetChartType.CANDLES -> bars.last().close >= bars.first().open
        WidgetChartType.LINE -> bars.last().close >= bars.first().close
    }

    /** Zeitpunkt → x, auf die Zeichenfläche begrenzt; ohne Zeitspanne nach der Lage [index]. */
    fun x(time: Long, index: Int = 0): Float {
        val span = axisEnd - axisStart
        val f = if (span > 0L) {
            (time - axisStart).toDouble() / span
        } else {
            index.toDouble() / (bars.size - 1)
        }
        return (plotLeft + f.coerceIn(0.0, 1.0) * (plotRight - plotLeft)).toFloat()
    }

    fun y(value: Double): Float {
        val span = axisLevels.high - axisLevels.low
        if (!(span > 0.0)) return (plotTop + plotBottom) / 2f
        return (plotBottom - (value - axisLevels.low) / span * (plotBottom - plotTop)).toFloat()
    }

    /** y der drei Hilfslinien: Hoch, Mitte, Tief der Achse. */
    val levelYs: List<Float> = listOf(y(axisLevels.high), y(axisLevels.mid), y(axisLevels.low))

    /** Punkte der Linie (Schluss am Beginn des Balkens). */
    val linePoints: List<ChartXY> by lazy {
        bars.mapIndexed { i, b -> ChartXY(x(b.start, i), y(b.close)) }
    }

    /** Kerzen: mittig in ihrer Zeit, Körper 65 % davon (mindestens 1 px breit und hoch). */
    val candleShapes: List<CandleShape> by lazy {
        bars.mapIndexed { i, b ->
            val xs = x(b.start, i)
            val xe = x(b.end, i)
            val bodyWidth = max(1f, (xe - xs) * WidgetChartGeometry.CANDLE_WIDTH_RATIO)
            val yo = y(b.open)
            val yc = y(b.close)
            var top = min(yo, yc)
            var bottom = max(yo, yc)
            if (bottom - top < 1f) {
                bottom = min(top + 1f, plotBottom)
                top = bottom - 1f
            }
            CandleShape(
                x = (xs + xe) / 2f,
                bodyWidth = bodyWidth,
                bodyTop = top,
                bodyBottom = bottom,
                wickTop = y(b.high),
                wickBottom = y(b.low),
                up = b.up,
            )
        }
    }

    /** Balken mit dem höchsten Wert (Kerzen: Hoch, Linie: Schluss; bei Gleichstand der erste). */
    val highIndex: Int = run {
        var best = 0
        for (i in bars.indices) {
            val v = if (type == WidgetChartType.CANDLES) bars[i].high else bars[i].close
            val b = if (type == WidgetChartType.CANDLES) bars[best].high else bars[best].close
            if (v > b) best = i
        }
        best
    }

    /** Lage der Hoch-Markierung. */
    val highPoint: ChartXY
        get() = when (type) {
            WidgetChartType.CANDLES -> ChartXY(candleShapes[highIndex].x, y(high))
            WidgetChartType.LINE -> linePoints[highIndex]
        }

    /** x der senkrechten Gitterlinien, nur innerhalb der Zeichenfläche. */
    val gridXs: List<Float> = gridTimes(axisStart, axisEnd, gridUnit, zone)
        .map { x(it) }
        .filter { it > plotLeft + 0.5f && it < plotRight - 0.5f }

    companion object {
        /**
         * Zeitpunkte der senkrechten Linien strikt zwischen [start] und [end]: jede volle Stunde,
         * jede lokale Mitternacht, jeder lokale Montag bzw. Monatserste 00:00 (wie
         * [WidgetChartGeometry.gridTimes]).
         */
        fun gridTimes(start: Long, end: Long, unit: ChartGridUnit, zone: ZoneId): List<Long> {
            if (end <= start) return emptyList()
            return when (unit) {
                ChartGridUnit.HOUR -> {
                    val hour = 3_600_000L
                    var t = Math.floorDiv(start, hour) * hour + hour
                    buildList {
                        while (t < end) {
                            add(t)
                            t += hour
                        }
                    }
                }
                ChartGridUnit.DAY, ChartGridUnit.WEEK, ChartGridUnit.MONTH -> {
                    var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    date = when (unit) {
                        ChartGridUnit.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        ChartGridUnit.MONTH -> date.withDayOfMonth(1)
                        else -> date
                    }
                    buildList {
                        var guard = 0
                        while (guard++ < 1000) {
                            val t = startOfDay(date, zone)
                            if (t >= end) break
                            if (t > start) add(t)
                            date = when (unit) {
                                ChartGridUnit.WEEK -> date.plusDays(7L)
                                ChartGridUnit.MONTH -> date.plusMonths(1L)
                                else -> date.plusDays(1L)
                            }
                        }
                    }
                }
            }
        }

        private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()

        /**
         * Linker Rand der «Hoch …»-Beschriftung (Breite [labelWidth]) neben der Markierung bei
         * [markerX]: rechts davon, wenn sie in der linken Hälfte liegt, sonst links; ganz in
         * [left]..[right].
         */
        fun labelLeft(markerX: Float, labelWidth: Float, left: Float, right: Float, gap: Float): Float {
            val rightSide = markerX < (left + right) / 2f
            val raw = if (rightSide) markerX + gap else markerX - gap - labelWidth
            return raw.coerceIn(left, max(left, right - labelWidth))
        }
    }
}
