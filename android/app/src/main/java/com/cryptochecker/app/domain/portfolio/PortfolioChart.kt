package com.cryptochecker.app.domain.portfolio

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Wie viele Tage eine Portfolio-Kerze umfasst. */
enum class PortfolioCandleBucket { DAY, WEEK, MONTH }

/**
 * Eine Kerze des Portfolio-Werts über die Tage [firstDay]..[lastDay] (Tage seit 1970-01-01, lokal).
 * Werte in der Einheit des Verlaufs (Währung oder USDT).
 */
data class PortfolioCandle(
    val firstDay: Long,
    val lastDay: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
) {
    /** Steigend (Schluss ≥ Eröffnung) = Kursfarbe «steigend». */
    val up: Boolean get() = close >= open
}

/**
 * Kerzen aus dem täglichen Wertverlauf (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioHistory.swift`, `PortfolioCandles`):
 *  - Tageskerze: Eröffnung = Wert am Ende des Vortags (erster Tag: seine eigene Eröffnung aus den
 *    Coin-Kerzen, sonst sein Schluss), Schluss = Wert am Tagesende — dieselbe Reihe wie die Linie
 *    (samt Umrechnung mit dem Tageskurs und Stablecoin-Regel).
 *  - Hoch/Tief: aus [PortfolioHistoryPoint.high]/[PortfolioHistoryPoint.low] (Σ Bestand × Hoch bzw.
 *    Tief der Coins — eine Schranke, siehe [PortfolioHistory.build]); immer so begrenzt, dass
 *    Tief ≤ min(Eröffnung, Schluss) und Hoch ≥ max(Eröffnung, Schluss). Ohne diese Werte
 *    Hoch/Tief = max/min(Eröffnung, Schluss), also ohne Docht.
 *  - Lange Zeiträume werden zusammengefasst, damit es höchstens rund 90 Kerzen sind: bis
 *    [MAX_DAILY_DAYS] Tage Tageskerzen, bis [MAX_WEEKLY_DAYS] Tage Wochenkerzen (ab Montag),
 *    darüber Monatskerzen. Die Linie behält ihre Tagespunkte.
 */
object PortfolioCandles {

    /** Bis zu so vielen Tagen eine Kerze je Tag (30 T, 7 T). */
    const val MAX_DAILY_DAYS = 92

    /** Bis zu so vielen Tagen Wochenkerzen (1 J = 53, höchstens 90); darüber Monatskerzen (5 J = 61). */
    const val MAX_WEEKLY_DAYS = 630

    /** Zusammenfassung für einen Verlauf über [dayCount] Tage. */
    fun bucketFor(dayCount: Int): PortfolioCandleBucket = when {
        dayCount <= MAX_DAILY_DAYS -> PortfolioCandleBucket.DAY
        dayCount <= MAX_WEEKLY_DAYS -> PortfolioCandleBucket.WEEK
        else -> PortfolioCandleBucket.MONTH
    }

    /** Erster Tag der Gruppe von [day]: der Tag selbst, sein Montag oder der Monatserste. */
    fun bucketStart(day: Long, bucket: PortfolioCandleBucket): Long = when (bucket) {
        PortfolioCandleBucket.DAY -> day
        // 1970-01-01 war ein Donnerstag: Montag = Tag − (Tag + 3) mod 7
        PortfolioCandleBucket.WEEK -> day - Math.floorMod(day + 3, 7L)
        PortfolioCandleBucket.MONTH -> LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()
    }

    /** Eine Kerze je Punkt (aufsteigend nach Tag); leer ohne Punkte. */
    fun daily(points: List<PortfolioHistoryPoint>): List<PortfolioCandle> {
        val out = ArrayList<PortfolioCandle>(points.size)
        for ((i, p) in points.withIndex()) {
            val close = p.value
            val open = if (i == 0) p.open?.takeIf { it.isFinite() } ?: close else points[i - 1].value
            val top = max(open, close)
            val bottom = min(open, close)
            val high = p.high?.takeIf { it.isFinite() }?.let { max(it, top) } ?: top
            val low = p.low?.takeIf { it.isFinite() }?.let { min(it, bottom) } ?: bottom
            out += PortfolioCandle(p.epochDay, p.epochDay, open, high, low, close)
        }
        return out
    }

    /**
     * Fasst aufeinanderfolgende Kerzen derselben Gruppe zusammen: Eröffnung der ersten, Schluss der
     * letzten, höchstes Hoch, tiefstes Tief.
     */
    fun aggregate(candles: List<PortfolioCandle>, bucket: PortfolioCandleBucket): List<PortfolioCandle> {
        if (bucket == PortfolioCandleBucket.DAY) return candles
        val out = ArrayList<PortfolioCandle>()
        var current: PortfolioCandle? = null
        var currentKey = Long.MIN_VALUE
        for (c in candles) {
            val key = bucketStart(c.firstDay, bucket)
            val open = current
            current = if (open != null && key == currentKey) {
                PortfolioCandle(open.firstDay, c.lastDay, open.open, max(open.high, c.high), min(open.low, c.low), c.close)
            } else {
                open?.let { out += it }
                currentKey = key
                c
            }
        }
        current?.let { out += it }
        return out
    }

    /** Fertige Kerzen für den Verlauf [points]: Tageskerzen, je nach Länge zusammengefasst. */
    fun build(points: List<PortfolioHistoryPoint>): List<PortfolioCandle> {
        if (points.isEmpty()) return emptyList()
        val days = (points.last().epochDay - points.first().epochDay + 1).toInt()
        return aggregate(daily(points), bucketFor(days))
    }
}

/** Abstand der senkrechten Gitterlinien im Wertverlauf. */
enum class PortfolioGridUnit { DAY, WEEK, MONTH, QUARTER, YEAR }

/**
 * Achsen und Lage im Wertverlauf-Chart (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioHistory.swift`, `PortfolioChartAxis`). Wie im Aktionsblatt eines Paars:
 * drei waagrechte Stufen (Tief, Mitte, Hoch) mit Beschriftung rechts, feine senkrechte Linien an
 * Kalendergrenzen. Jeder Punkt bzw. jede Kerze belegt einen gleich breiten Platz («Slot»).
 */
object PortfolioChartAxis {

    /** Unterteilung nach Länge: 7 T je Tag, 30 T je Woche, 1 J je Monat, länger je Quartal bzw. Jahr. */
    fun gridUnit(dayCount: Int): PortfolioGridUnit = when {
        dayCount <= 10 -> PortfolioGridUnit.DAY
        dayCount <= 62 -> PortfolioGridUnit.WEEK
        dayCount <= 400 -> PortfolioGridUnit.MONTH
        dayCount <= 1100 -> PortfolioGridUnit.QUARTER
        else -> PortfolioGridUnit.YEAR
    }

    /**
     * Tage, an denen eine senkrechte Linie beginnt (Tagesbeginn, Montag, Monats-, Quartals- bzw.
     * Jahreserster), strikt nach [firstDay] und höchstens [lastDay] — die Linie liegt am linken
     * Rand dieses Tags.
     */
    fun gridDays(firstDay: Long, lastDay: Long): List<Long> {
        if (lastDay <= firstDay) return emptyList()
        val unit = gridUnit((lastDay - firstDay + 1).toInt())
        val out = ArrayList<Long>()
        if (unit == PortfolioGridUnit.DAY) {
            for (d in firstDay + 1..lastDay) out += d
            return out
        }
        if (unit == PortfolioGridUnit.WEEK) {
            var d = PortfolioCandles.bucketStart(firstDay, PortfolioCandleBucket.WEEK) + 7
            while (d <= lastDay) {
                out += d
                d += 7
            }
            return out
        }
        val step = when (unit) {
            PortfolioGridUnit.QUARTER -> 3L
            PortfolioGridUnit.YEAR -> 12L
            else -> 1L
        }
        val start = LocalDate.ofEpochDay(firstDay)
        // Erster Monat der Unterteilung (Januar/April/Juli/Oktober bzw. Januar) bis zum Starttag
        val monthIndex = (start.monthValue - 1) / step.toInt() * step.toInt()
        var date = LocalDate.of(start.year, monthIndex + 1, 1)
        while (true) {
            val d = date.toEpochDay()
            if (d > lastDay) break
            if (d > firstDay) out += d
            date = date.plusMonths(step)
        }
        return out
    }

    /**
     * Lage des Tagesbeginns [day] in Slots ab dem linken Rand: Slot i umfasst die Tage
     * [spans]`[i]` (erster bis letzter Tag, lückenlos aufsteigend); innerhalb eines Slots anteilig.
     * null, wenn kein Slot den Tag enthält.
     */
    fun slotPosition(spans: List<LongRange>, day: Long): Double? {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = spans[mid]
            when {
                day < span.first -> hi = mid - 1
                day > span.last -> lo = mid + 1
                else -> {
                    val length = (span.last - span.first + 1).toDouble()
                    return mid + (day - span.first) / length
                }
            }
        }
        return null
    }

    /** Index des Slots, der [day] enthält; null ausserhalb. */
    fun slotOfDay(spans: List<LongRange>, day: Long): Int? = slotPosition(spans, day)?.toInt()

    /** Index des höchsten Werts (bei Gleichstand der erste); null ohne gültige Werte. */
    fun maxIndex(values: List<Double>): Int? {
        var best: Int? = null
        for ((i, v) in values.withIndex()) {
            if (!v.isFinite()) continue
            val b = best
            if (b == null || v > values[b]) best = i
        }
        return best
    }

    /** Waagrechte Stufen (Tief, Mitte, Hoch) einer Skala von [low] bis [high]. */
    fun levels(low: Double, high: Double): Triple<Double, Double, Double> = Triple(low, (low + high) / 2.0, high)

    /** Bereich des Gewinn/Verlust-Charts: schliesst 0 immer ein (Nulllinie sichtbar). */
    fun pnlBounds(low: Double, high: Double): Pair<Double, Double> = minOf(low, 0.0) to maxOf(high, 0.0)

    /** Skala der Kerzen: tiefstes Tief bis höchstes Hoch; null ohne Kerzen. */
    fun candleBounds(candles: List<PortfolioCandle>): Pair<Double, Double>? {
        if (candles.isEmpty()) return null
        return candles.minOf { it.low } to candles.maxOf { it.high }
    }
}
