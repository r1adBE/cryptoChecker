package com.cryptochecker.app.domain.market

import java.time.LocalDate

/** Hoch, Tief und — falls vorhanden — ein zweites Hoch/Tief (Doppel-Top/-Bottom) eines Zyklus. */
data class CycleExtremesResult(
    val top: CycleMarker? = null,
    val bottom: CycleMarker? = null,
    val secondTop: CycleMarker? = null,
    val secondBottom: CycleMarker? = null,
)

/**
 * Markante Punkte eines Zyklus aus Tagesschlusskursen (Tag nach dem Halving → Kurs).
 *
 * - **Hoch:** höchster Kurs in den ersten [TOP_WINDOW_DAYS] Tagen. Kurz vor dem
 *   nächsten Halving kann der Kurs schon höher stehen (2020er-Zyklus: März 2024
 *   über dem Hoch von Nov. 2021) — das gehört zum nächsten Zyklus.
 * - **Tief:** tiefster Kurs nach dem Hoch, nur wenn mindestens [BEAR_THRESHOLD] darunter.
 * - **Doppel-Top/-Bottom:** zweites Hoch bzw. Tief, bei dem der tiefere der beiden
 *   Punkte höchstens [DOUBLE_TOLERANCE] unter dem höheren liegt, mindestens [DOUBLE_MIN_GAP_DAYS] Tage davor oder danach,
 *   und dazwischen eine Gegenbewegung von mindestens [DOUBLE_TOLERANCE] — sonst
 *   wäre es nur die Flanke desselben Hochs bzw. Tiefs.
 *   Beispiel 2020er-Zyklus: April/Nov. 2021 und Juni/Nov. 2022.
 */
object CycleExtremes {
    const val TOP_WINDOW_DAYS = 1000
    const val BEAR_THRESHOLD = 0.30
    const val DOUBLE_TOLERANCE = 0.20
    const val DOUBLE_MIN_GAP_DAYS = 90

    fun find(daily: List<Pair<Int, Double>>, halving: LocalDate, startPrice: Double): CycleExtremesResult {
        if (startPrice <= 0 || daily.isEmpty()) return CycleExtremesResult()
        val topRange = daily.indices.filter { daily[it].first <= TOP_WINDOW_DAYS }
        val topIndex = topRange.maxByOrNull { daily[it].second } ?: return CycleExtremesResult()
        val topPrice = daily[topIndex].second

        fun marker(index: Int, change: Double) = CycleMarker(
            day = daily[index].first,
            date = halving.plusDays(daily[index].first.toLong()),
            priceUsd = daily[index].second,
            multiple = daily[index].second / startPrice,
            change = change,
        )

        val top = marker(topIndex, topPrice / startPrice - 1.0)
        val secondTop = second(daily, topIndex, topRange, isTop = true)
            ?.let { marker(it, daily[it].second / startPrice - 1.0) }

        val afterTop = (topIndex + 1 until daily.size).toList()
        val bottomIndex = afterTop.minByOrNull { daily[it].second }
            ?.takeIf { daily[it].second <= topPrice * (1.0 - BEAR_THRESHOLD) }
        val bottom = bottomIndex?.let { marker(it, daily[it].second / topPrice - 1.0) }
        val secondBottom = bottomIndex?.let { second(daily, it, afterTop, isTop = false) }
            ?.let { marker(it, daily[it].second / topPrice - 1.0) }

        return CycleExtremesResult(top, bottom, secondTop, secondBottom)
    }

    /** Index des zweiten Hochs/Tiefs zu [main] innerhalb von [range], sonst null. */
    private fun second(daily: List<Pair<Int, Double>>, main: Int, range: List<Int>, isTop: Boolean): Int? {
        val (mainDay, mainPrice) = daily[main]
        val candidates = range.filter { i ->
            val (day, price) = daily[i]
            kotlin.math.abs(day - mainDay) >= DOUBLE_MIN_GAP_DAYS &&
                // Der tiefere der beiden Punkte liegt höchstens DOUBLE_TOLERANCE unter dem höheren
                (if (isTop) price >= mainPrice * (1 - DOUBLE_TOLERANCE) else mainPrice >= price * (1 - DOUBLE_TOLERANCE))
        }
        // Extremster Kandidat zuerst; er muss vom Hauptpunkt durch eine Gegenbewegung getrennt sein
        val ordered = if (isTop) candidates.sortedByDescending { daily[it].second } else candidates.sortedBy { daily[it].second }
        return ordered.firstOrNull { i ->
            val between = (minOf(i, main) + 1 until maxOf(i, main)).map { daily[it].second }
            if (between.isEmpty()) return@firstOrNull false
            if (isTop) between.min() <= daily[i].second * (1 - DOUBLE_TOLERANCE)
            else between.max() >= daily[i].second * (1 + DOUBLE_TOLERANCE)
        }
    }
}
