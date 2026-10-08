package com.cryptochecker.app.widget

import com.cryptochecker.app.util.ChartSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/**
 * Reine Geometrie des Einzel-Widget-Charts (ohne Android): Preisstufen, Gitter,
 * Kerzen, Linie, Kurs-Etikett und welche Preisbeschriftungen sichtbar bleiben.
 * Gezeichnet wird in [WidgetChartRenderer]; getestet in WidgetChartGeometryTest.
 */

/** Chart-Art je Widget. Gespeichert wird der Name (siehe [WidgetPrefs.getChartType]). */
enum class WidgetChartType { CANDLES, LINE }

/** Abstand der senkrechten Gitterlinien: je Stunde, je Tag (lokal 00:00), je Woche (lokal Montag 00:00). */
enum class ChartGridUnit { HOUR, DAY, WEEK, MONTH }

/** Eine Kerze des Widget-Charts; [openTime] in Epoch-ms. */
data class WidgetCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
) {
    /** Steigend (Schluss ≥ Eröffnung) = Kursfarbe «steigend», sonst «fallend». */
    val up: Boolean get() = close >= open
}

/** Drei waagrechte Preisstufen: Tief, Mitte, Hoch. */
data class ChartLevels(val low: Double, val mid: Double, val high: Double)

enum class LevelKind { HIGH, MID, LOW }

/** Eine sichtbare Preisbeschriftung, senkrecht mittig auf [y]. */
data class PlacedLabel(val kind: LevelKind, val price: Double, val y: Float)

/** Senkrechter Bereich (oben < unten, Pixel). */
data class VSpan(val top: Float, val bottom: Float) {
    fun overlaps(other: VSpan, gap: Float = 0f): Boolean =
        top < other.bottom + gap && other.top < bottom + gap
}

/** Kerze in Pixeln: Docht [wickTop]–[wickBottom], Körper [bodyTop]–[bodyBottom], mittig auf [x]. */
data class CandleShape(
    val x: Float,
    val bodyWidth: Float,
    val bodyTop: Float,
    val bodyBottom: Float,
    val wickTop: Float,
    val wickBottom: Float,
    val up: Boolean,
)

/**
 * Lage aller Teile in einer Bitmap von [width] × [height] Pixeln. Rechts liegt eine
 * Spalte von [labelColumnWidth] für Beschriftungen und Kurs-Etikett; die Zeichenfläche
 * ist links davon. Oben und unten bleibt eine halbe Etiketthöhe frei, damit Etikett
 * und Beschriftungen an den Rändern ganz sichtbar sind.
 */
class WidgetChartGeometry(
    val candles: List<WidgetCandle>,
    val type: WidgetChartType,
    gridUnit: ChartGridUnit,
    intervalMillis: Long,
    zone: ZoneId,
    val width: Float,
    val height: Float,
    val labelColumnWidth: Float,
    labelHeight: Float,
    tagHeight: Float,
    /** Kleine Fläche: nur Hoch und Tief beschriften (plus Kurs-Etikett). */
    val compact: Boolean,
    /** Mindestabstand zwischen Beschriftungen bzw. Etikett (Pixel). */
    gap: Float = 0f,
    /**
     * Aktueller Kurs des Widgets (wie die Überschrift); null oder ≤ 0 = letzter Schluss.
     * Liegt er ausserhalb von Tief..Hoch, sitzen Etikett und Linie am oberen/unteren Rand.
     */
    currentPrice: Double? = null,
) {
    init {
        require(candles.size >= 2) { "mindestens zwei Kerzen" }
    }

    val plotLeft: Float = 0f
    val plotRight: Float = max(plotLeft + 1f, width - labelColumnWidth)
    private val edge = max(labelHeight, tagHeight) / 2f + 1f
    val plotTop: Float = edge
    val plotBottom: Float = max(plotTop + 1f, height - edge)

    val levels: ChartLevels = levels(candles, type)

    /** Gesamtrichtung (letzter Schluss ≥ erster Wert) — Farbe der Linie. */
    val up: Boolean = when (type) {
        WidgetChartType.CANDLES -> candles.last().close >= candles.first().open
        WidgetChartType.LINE -> candles.last().close >= candles.first().close
    }

    val slotWidth: Float = (plotRight - plotLeft) / candles.size

    fun y(price: Double): Float {
        val span = levels.high - levels.low
        if (span <= 0.0) return (plotTop + plotBottom) / 2f
        return (plotBottom - (price - levels.low) / span * (plotBottom - plotTop)).toFloat()
    }

    fun xCenter(index: Int): Float = plotLeft + (index + 0.5f) * slotWidth

    /** Zeitpunkt → x; Kerze i deckt [openTime_0 + i·Intervall, … + Intervall) ab. */
    fun xForTime(time: Long, intervalMillis: Long): Float =
        plotLeft + ((time - candles.first().openTime).toDouble() / intervalMillis * slotWidth).toFloat()

    /** x der senkrechten Gitterlinien, nur innerhalb der Zeichenfläche. */
    val gridXs: List<Float> = gridTimes(candles, gridUnit, intervalMillis, zone)
        .map { xForTime(it, intervalMillis) }
        .filter { it > plotLeft + 0.5f && it < plotRight - 0.5f }

    val candleShapes: List<CandleShape> by lazy {
        val bodyWidth = max(1f, slotWidth * CANDLE_WIDTH_RATIO)
        candles.mapIndexed { i, c ->
            val yo = y(c.open)
            val yc = y(c.close)
            var top = min(yo, yc)
            var bottom = max(yo, yc)
            if (bottom - top < 1f) {
                // Körper mindestens 1 px hoch, ohne die Zeichenfläche zu verlassen
                bottom = min(top + 1f, plotBottom)
                top = bottom - 1f
            }
            CandleShape(
                x = xCenter(i),
                bodyWidth = bodyWidth,
                bodyTop = top,
                bodyBottom = bottom,
                wickTop = y(c.high),
                wickBottom = y(c.low),
                up = c.up,
            )
        }
    }

    /** Punkte der Linie (Schlusskurse, mittig im Zeitfenster der Kerze). */
    val linePoints: List<Pair<Float, Float>> by lazy {
        candles.mapIndexed { i, c -> xCenter(i) to y(c.close) }
    }

    /** Kurs im Etikett: aktueller Kurs, ersatzweise der letzte Schluss. */
    val currentPrice: Double = currentPrice?.takeIf { it > 0.0 } ?: candles.last().close

    /** Höhe des aktuellen Kurses (gestrichelte Akzentlinie), auf die Zeichenfläche begrenzt. */
    val currentY: Float = y(this.currentPrice).coerceIn(plotTop, plotBottom)

    /** Kurs-Etikett: mittig auf [currentY], in die Bitmap geschoben. */
    val tagSpan: VSpan = run {
        val half = tagHeight / 2f
        val center = currentY.coerceIn(half, max(half, height - half))
        VSpan(center - half, center + half)
    }

    /** Sichtbare Preisbeschriftungen — nie überlappend, das Etikett gewinnt. */
    val labels: List<PlacedLabel> = resolveLabels(
        candidates = listOf(
            LevelKind.HIGH to levels.high,
            LevelKind.LOW to levels.low,
            LevelKind.MID to levels.mid,
        ).filter { !compact || it.first != LevelKind.MID }
            .map { (kind, price) -> PlacedLabel(kind, price, y(price)) },
        labelHeight = labelHeight,
        tag = tagSpan,
        gap = gap,
    )

    companion object {
        /** Körperbreite im Verhältnis zum Zeitfenster der Kerze (Vorgabe 60–70 %). */
        const val CANDLE_WIDTH_RATIO = 0.65f

        /** Unter dieser Chart-Höhe (dp) bzw. Breite nur Hoch/Tief beschriften. */
        const val COMPACT_HEIGHT_DP = 80f
        const val COMPACT_WIDTH_DP = 150f

        fun isCompact(widthDp: Float, heightDp: Float): Boolean =
            heightDp < COMPACT_HEIGHT_DP || widthDp < COMPACT_WIDTH_DP

        /** Kerzen: tiefstes Tief / höchstes Hoch; Linie: tiefster / höchster Schluss. */
        fun levels(candles: List<WidgetCandle>, type: WidgetChartType): ChartLevels {
            val low = when (type) {
                WidgetChartType.CANDLES -> candles.minOf { it.low }
                WidgetChartType.LINE -> candles.minOf { it.close }
            }
            val high = when (type) {
                WidgetChartType.CANDLES -> candles.maxOf { it.high }
                WidgetChartType.LINE -> candles.maxOf { it.close }
            }
            return ChartLevels(low = low, mid = (low + high) / 2.0, high = high)
        }

        /**
         * Zeitpunkte der senkrechten Gitterlinien, strikt zwischen Beginn der ersten
         * und Ende der letzten Kerze:
         *  - [ChartGridUnit.HOUR]: jede volle Stunde, d. h. jede Kerzengrenze,
         *  - [ChartGridUnit.DAY]: jede lokale Mitternacht,
         *  - [ChartGridUnit.WEEK]: jeder lokale Montag 00:00,
         *  - [ChartGridUnit.MONTH]: jeder lokale Monatserste 00:00.
         */
        fun gridTimes(
            candles: List<WidgetCandle>,
            unit: ChartGridUnit,
            intervalMillis: Long,
            zone: ZoneId,
        ): List<Long> {
            if (candles.isEmpty()) return emptyList()
            val start = candles.first().openTime
            val end = candles.last().openTime + intervalMillis
            return when (unit) {
                ChartGridUnit.HOUR -> {
                    val hour = 3_600_000L
                    var t = Math.floorDiv(start, hour) * hour + hour
                    buildList {
                        while (t < end) { add(t); t += hour }
                    }
                }
                ChartGridUnit.DAY, ChartGridUnit.WEEK, ChartGridUnit.MONTH -> {
                    var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    when (unit) {
                        ChartGridUnit.WEEK -> date = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        ChartGridUnit.MONTH -> date = date.withDayOfMonth(1)
                        else -> Unit
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
         * Platziert die Beschriftungen in Reihenfolge (Hoch, Tief, Mitte): Eine
         * Beschriftung, die das Etikett oder eine schon platzierte Beschriftung
         * überlappen würde, entfällt. Rückgabe in der Reihenfolge der Kandidaten.
         */
        fun resolveLabels(
            candidates: List<PlacedLabel>,
            labelHeight: Float,
            tag: VSpan?,
            gap: Float = 0f,
        ): List<PlacedLabel> {
            val taken = mutableListOf<VSpan>()
            if (tag != null) taken += tag
            val result = mutableListOf<PlacedLabel>()
            for (label in candidates) {
                val span = VSpan(label.y - labelHeight / 2f, label.y + labelHeight / 2f)
                if (taken.any { it.overlaps(span, gap) }) continue
                taken += span
                result += label
            }
            return result
        }

        /**
         * Kennzahlen für den Screenreader-Satz: Kerzen = erste Eröffnung, letzter
         * Schluss, höchstes Hoch, tiefstes Tief; Linie = Schlusskurse wie bisher.
         */
        fun summary(candles: List<WidgetCandle>, type: WidgetChartType): ChartSummary? {
            if (candles.size < 2) return null
            return when (type) {
                WidgetChartType.CANDLES -> ChartSummary(
                    start = candles.first().open,
                    end = candles.last().close,
                    high = candles.maxOf { it.high },
                    low = candles.minOf { it.low },
                )
                WidgetChartType.LINE -> ChartSummary.of(candles.map { it.close })
            }
        }
    }
}
