package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.widget.WidgetCandle

/** Ein Punkt einer Vergleichslinie: Zeit (Epoch-ms) und Änderung in % seit Beginn des Zeitraums. */
data class ComparePoint(val time: Long, val percent: Double)

/** Kennzahlen einer Zeile der Vergleichstabelle in %; null = nicht berechenbar (zu wenig Kerzen, kein Kurs). */
data class CompareFigures(val change7d: Double?, val change30d: Double?, val fromHigh30d: Double?)

/** Achsen des Vergleichs-Charts: Zeitspanne und %-Spanne (enthält immer 0). */
data class CompareBounds(val minTime: Long, val maxTime: Long, val minPercent: Double, val maxPercent: Double)

/**
 * Vergleichsansicht (Merkliste › markieren › «Vergleichen»): 2 bis 4 Paare, eine Linie je
 * Paar in % ab Beginn des Zeitraums, darunter Kurs, 24 h, 7 T, 30 T und Abstand zum 30-Tage-Hoch. Nur Kerzen, die die
 * App fürs Aktionsblatt ohnehin lädt. Wie iOS `CompareMath` (Paritäts-Fixture `compare.json`).
 */
object CompareMath {
    const val MIN_PAIRS = 2
    const val MAX_PAIRS = 4
    private const val DAY_MILLIS = 86_400_000L

    /** Lässt sich mit [count] markierten Paaren vergleichen? */
    fun canCompare(count: Int): Boolean = count in MIN_PAIRS..MAX_PAIRS

    /**
     * Linie in % ab dem ersten gültigen Schlusskurs (endlich, > 0); ungültige Schlüsse fallen weg. Weniger als zwei
     * Punkte → leer (keine Linie).
     */
    fun normalized(candles: List<WidgetCandle>): List<ComparePoint> {
        val valid = candles.filter { it.close.isFinite() && it.close > 0.0 }.sortedBy { it.openTime }
        if (valid.size < 2) return emptyList()
        val base = valid.first().close
        return valid.map { ComparePoint(it.openTime, (it.close / base - 1.0) * 100.0) }
    }

    /**
     * Kennzahlen aus Tageskerzen ([daily], ältere zuerst oder ungeordnet) und dem aktuellen Kurs [price] zur Zeit [now]:
     * 7 T bzw. 30 T = Kurs gegen den Schluss der letzten Tageskerze, die spätestens vor 7 bzw. 30 Tagen begann;
     * Abstand zum Hoch = Kurs gegen das höchste Hoch der Kerzen der letzten 30 Tage (samt Kurs selbst, also ≤ 0).
     */
    fun figures(daily: List<WidgetCandle>, price: Double?, now: Long): CompareFigures {
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return CompareFigures(null, null, null)
        val sorted = daily.filter { it.close.isFinite() && it.close > 0.0 }.sortedBy { it.openTime }
        fun changeSince(days: Int): Double? {
            val cutoff = now - days * DAY_MILLIS
            val ref = sorted.lastOrNull { it.openTime <= cutoff }?.close ?: return null
            return (p / ref - 1.0) * 100.0
        }
        val windowStart = now - 30 * DAY_MILLIS
        val highs = sorted.filter { it.openTime >= windowStart }.map { it.high }.filter { it.isFinite() && it > 0.0 }
        val high = if (highs.isEmpty()) null else maxOf(highs.max(), p)
        return CompareFigures(changeSince(7), changeSince(30), high?.let { (p / it - 1.0) * 100.0 })
    }

    /** Achsen über alle Linien; 0 % immer sichtbar. null ohne Punkte. */
    fun bounds(series: List<List<ComparePoint>>): CompareBounds? {
        val all = series.flatten()
        if (all.isEmpty()) return null
        val minP = minOf(0.0, all.minOf { it.percent })
        val maxP = maxOf(0.0, all.maxOf { it.percent })
        return CompareBounds(all.minOf { it.time }, all.maxOf { it.time }, minP, if (maxP > minP) maxP else minP + 1.0)
    }

    /** Letzter Wert einer Linie (Änderung über den ganzen Zeitraum); null = keine Linie. */
    fun last(series: List<ComparePoint>): Double? = series.lastOrNull()?.percent
}
