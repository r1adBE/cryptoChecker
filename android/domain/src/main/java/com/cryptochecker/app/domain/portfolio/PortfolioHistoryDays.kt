package com.cryptochecker.app.domain.portfolio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Tage des Wertverlaufs (Teil von [PortfolioHistory]): erster Tag, Begrenzung, nötige Tageskerzen und Abfrage-Stücke,
// Punkt unter dem Finger, Schluss an einem Tag. Erweiterungsfunktionen, damit `PortfolioHistory.startDay(…)` usw.
// bleiben; gespiegelt in `Shared/Portfolio/PortfolioHistory+Days.swift`.

/**
 * Erster Tag des Verlaufs: erster Transaktionstag (UTC: kleinster Tag, dessen Ende nicht
 * vor [firstTradeMillis] liegt) oder Beginn des Zeitraums, je nachdem, was später ist;
 * nie nach [todayEpochDay]. «Seit 1. Kauf»: Beginn = erster Transaktionstag, frühestens
 * [DATA_START_EPOCH_DAY]. Tage nach UTC ([utcDayEnd], wie [build]); `dayEndMillis` bleibt für ältere Aufrufer.
 */
fun PortfolioHistory.startDay(
    range: PortfolioHistoryRange,
    firstTradeMillis: Long,
    todayEpochDay: Long,
    @Suppress("UNUSED_PARAMETER") dayEndMillis: (Long) -> Long = { utcDayEnd(it) },
): Long {
    val dayEndMillis: (Long) -> Long = { utcDayEnd(it) }
    var lo = if (range == PortfolioHistoryRange.SINCE_FIRST) {
        // Der lokale Tag liegt höchstens einen Tag neben dem UTC-Tag (Zeitzonen −12 h … +14 h)
        min(todayEpochDay, max(DATA_START_EPOCH_DAY, epochDayOfUtcMillis(firstTradeMillis) - 2))
    } else {
        todayEpochDay - range.days
    }
    var hi = todayEpochDay
    while (lo < hi) {
        val mid = lo + (hi - lo) / 2
        if (dayEndMillis(mid) < firstTradeMillis) lo = mid + 1 else hi = mid
    }
    return lo
}

/** «Seit 1. Kauf» und der erste Kauf liegt vor dem ersten Tag mit Kursdaten ([DATA_START_EPOCH_DAY]). */
fun PortfolioHistory.isCapped(
    range: PortfolioHistoryRange,
    firstTradeMillis: Long,
    todayEpochDay: Long,
    @Suppress("UNUSED_PARAMETER") dayEndMillis: (Long) -> Long = { utcDayEnd(it) },
): Boolean = range == PortfolioHistoryRange.SINCE_FIRST && DATA_START_EPOCH_DAY <= todayEpochDay &&
    firstTradeMillis <= utcDayEnd(DATA_START_EPOCH_DAY - 1)

/**
 * Tageskerzen je Coin für [range]: [MAX_DAYS] für 7 T, 30 T und 1 J (eine gemeinsame
 * Abfrage); «Seit 1. Kauf» bis zum Starttag samt einem Tag davor (Zeitzone), mindestens
 * [MAX_DAYS]. Ohne Transaktion ([firstTradeMillis] null) [MAX_DAYS].
 */
fun PortfolioHistory.candleDays(
    range: PortfolioHistoryRange,
    firstTradeMillis: Long?,
    todayEpochDay: Long,
    dayEndMillis: (Long) -> Long,
): Int {
    if (range != PortfolioHistoryRange.SINCE_FIRST || firstTradeMillis == null) return MAX_DAYS
    val start = startDay(range, firstTradeMillis, todayEpochDay, dayEndMillis)
    return max(MAX_DAYS, (todayEpochDay - start + 2).toInt())
}

/**
 * Abfragen für die letzten [days] UTC-Tage bis einschliesslich [todayUtcDay]: Stücke von
 * höchstens [perRequest] Tagen, aufsteigend und lückenlos; das letzte endet heute,
 * ein kürzeres Stück steht vorn. Leer bei [days] ≤ 0.
 */
fun PortfolioHistory.candleChunks(days: Int, todayUtcDay: Long, perRequest: Int = MAX_CANDLES_PER_REQUEST): List<LongRange> {
    if (days <= 0 || perRequest <= 0) return emptyList()
    val first = todayUtcDay - days + 1
    val out = ArrayList<LongRange>()
    var end = todayUtcDay
    while (end >= first) {
        val start = max(first, end - perRequest + 1)
        out += start..end
        end = start - 1
    }
    return out.reversed()
}

/**
 * Punkt unter dem Finger beim Ziehen über den Wertverlauf: Punkt i liegt bei
 * [left] + [width]·i/(count−1) → der nächstgelegene, an den Rändern der erste/letzte.
 * null ohne Punkte oder bei ungültigem x.
 */
fun PortfolioHistory.scrubIndex(x: Float, left: Float, width: Float, count: Int): Int? {
    if (count <= 0 || x.isNaN()) return null
    if (count == 1 || !(width > 0f)) return 0
    val i = ((x - left) / width * (count - 1)).roundToInt()
    return i.coerceIn(0, count - 1)
}

/** Schluss am Tag [day] oder der letzte davor; null vor der ersten Kerze. */
fun PortfolioHistory.closeOnOrBefore(sorted: List<Pair<Long, Double>>, day: Long): Double? {
    var lo = 0
    var hi = sorted.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid].first <= day) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return if (found >= 0) sorted[found].second else null
}
