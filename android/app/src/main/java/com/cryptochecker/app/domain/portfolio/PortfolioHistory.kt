package com.cryptochecker.app.domain.portfolio

import kotlin.math.min

/** Zeitraum des Wertverlaufs im Portfolio-Tab. */
enum class PortfolioHistoryRange(val days: Int) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
}

/** Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). [epochDay] = Tage seit 1970-01-01. */
data class PortfolioHistoryPoint(val epochDay: Long, val value: Double)

/** Fertiger Verlauf eines Zeitraums. */
data class PortfolioHistorySeries(
    /** Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat. */
    val points: List<PortfolioHistoryPoint>,
    /** Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»). */
    val skipped: List<String>,
    /** Letzter minus erster Wert; null mit weniger als zwei Punkten. */
    val change: Double?,
    /** Änderung in Prozent des ersten Werts; null ohne Ausgangswert. */
    val changePercent: Double?,
    /** Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft — die Änderung ist dann nicht nur Kursbewegung. */
    val tradesInRange: Boolean,
) {
    /** Genug für eine Linie. */
    val hasChart: Boolean get() = points.size >= 2

    companion object {
        val EMPTY = PortfolioHistorySeries(emptyList(), emptyList(), null, null, false)
    }
}

/**
 * Wertverlauf des Portfolios aus den Transaktionen (reines Kotlin, testbar; gespiegelt in
 * `Shared/Portfolio/PortfolioHistory.swift`):
 *  - Bestand je Coin und Tag = alle Käufe/Verkäufe bis zum Tagesende (lokale Zeitzone,
 *    über [dayEndMillis]); Verkäufe werden wie in [PortfolioCalculator] beim Bestand gekappt.
 *  - Wert = Σ Bestand × Tagesschlusskurs (UTC-Tageskerze desselben Kalendertags) × [fxRate].
 *    Fehlt an einem Tag die Kerze, gilt der letzte bekannte Schluss davor; vor der ersten
 *    Kerze zählt der Coin nicht.
 *  - Heute: Bestand nach allen Transaktionen × aktueller Kurs (sonst letzter Schluss).
 *  - Stablecoins = 1 ohne Kerzen. Coins ganz ohne Kerzen fehlen und stehen in [PortfolioHistorySeries.skipped].
 *  - Beginn = erster Transaktionstag oder Beginn des Zeitraums, je nachdem, was später ist.
 */
object PortfolioHistory {

    /** Tageskerzen je Coin (eine Abfrage mit diesem Limit deckt «1 J» samt heute ab). */
    const val MAX_DAYS = 366

    private const val DAY_MILLIS = 86_400_000L

    fun isStable(coin: String): Boolean = CutoffExport.isStable(coin)

    /** UTC-Tag einer Kerzen-Eröffnungszeit (ms). */
    fun epochDayOfUtcMillis(millis: Long): Long = Math.floorDiv(millis, DAY_MILLIS)

    /**
     * Bestand eines Coins nach jeder Transaktion: (Zeit, Bestand danach), zeitlich
     * aufsteigend (bei Gleichstand nach Id). Ungültige Mengen werden übersprungen.
     */
    fun holdingsTimeline(coin: String, trades: List<PortfolioTrade>): List<Pair<Long, Double>> {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        val own = trades.filter { PortfolioCalculator.normalizeCoin(it.coin) == symbol }
            .sortedWith(compareBy<PortfolioTrade> { it.time }.thenBy { it.id })
        var holdings = 0.0
        val out = ArrayList<Pair<Long, Double>>(own.size)
        for (t in own) {
            val amount = t.amount
            if (!(amount > 0.0) || amount.isInfinite()) continue
            holdings = when (t.type) {
                PortfolioTxType.BUY -> holdings + amount
                PortfolioTxType.SELL -> holdings - min(amount, holdings)
            }
            if (holdings <= PortfolioCalculator.EPS) holdings = 0.0
            out += t.time to holdings
        }
        return out
    }

    /** Bestand zum Zeitpunkt [atMillis] (einschliesslich) aus [holdingsTimeline]. */
    fun holdingsAt(timeline: List<Pair<Long, Double>>, atMillis: Long): Double {
        var result = 0.0
        for ((time, holdings) in timeline) {
            if (time > atMillis) break
            result = holdings
        }
        return result
    }

    /**
     * Verlauf für [range] bis [todayEpochDay].
     * @param closes Tagesschluss in USDT je Coin (Grossschreibung) und UTC-Tag; fehlt ein Coin
     *   (oder ist seine Liste leer), hat er keinen Verlauf.
     * @param livePrices aktuelle USDT-Kurse für den heutigen Punkt.
     * @param dayEndMillis letzte Millisekunde eines Tags in der lokalen Zeitzone.
     * @param fxRate Umrechnung USDT → Anzeigewährung (1 = keine).
     */
    fun build(
        trades: List<PortfolioTrade>,
        closes: Map<String, Map<Long, Double>>,
        livePrices: Map<String, Double>,
        range: PortfolioHistoryRange,
        todayEpochDay: Long,
        dayEndMillis: (Long) -> Long,
        fxRate: Double = 1.0,
    ): PortfolioHistorySeries {
        val valid = trades.filter { it.amount > 0.0 && !it.amount.isInfinite() }
        if (valid.isEmpty()) return PortfolioHistorySeries.EMPTY
        val rate = fxRate.takeIf { it > 0.0 && it.isFinite() } ?: 1.0

        // Erster Tag mit einer Transaktion (lokal): kleinster Tag, dessen Ende nach ihr liegt
        val firstTime = valid.minOf { it.time }
        val rangeStart = todayEpochDay - range.days
        var start = rangeStart
        while (start < todayEpochDay && dayEndMillis(start) < firstTime) start++

        val coins = valid.map { PortfolioCalculator.normalizeCoin(it.coin) }.distinct().sorted()
        val timelines = coins.associateWith { holdingsTimeline(it, valid) }

        // Coins, die im Zeitraum etwas hielten
        val startMillis = dayEndMillis(start - 1) + 1
        val held = coins.filter { coin -> heldWithin(timelines.getValue(coin), startMillis) }
        val skipped = held.filter { !isStable(it) && closes[it].isNullOrEmpty() }
        val included = held.filter { it !in skipped }
        if (included.isEmpty()) {
            return PortfolioHistorySeries(emptyList(), skipped, null, null, false)
        }

        // Je Coin die Kerzen aufsteigend, für «letzter bekannter Schluss»
        val sortedCloses = included.filter { !isStable(it) }.associateWith { coin ->
            closes.getValue(coin).entries.filter { it.value > 0.0 && it.value.isFinite() }
                .sortedBy { it.key }.map { it.key to it.value }
        }

        val points = ArrayList<PortfolioHistoryPoint>((todayEpochDay - start + 1).toInt().coerceAtLeast(1))
        for (day in start..todayEpochDay) {
            val isToday = day == todayEpochDay
            val end = dayEndMillis(day)
            var total = 0.0
            for (coin in included) {
                val timeline = timelines.getValue(coin)
                val amount = if (isToday) timeline.lastOrNull()?.second ?: 0.0 else holdingsAt(timeline, end)
                if (amount <= 0.0) continue
                val price = when {
                    isStable(coin) -> 1.0
                    isToday -> livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() }
                        ?: closeOnOrBefore(sortedCloses.getValue(coin), day)
                    else -> closeOnOrBefore(sortedCloses.getValue(coin), day)
                } ?: continue
                total += amount * price
            }
            points += PortfolioHistoryPoint(day, total * rate)
        }

        val first = points.first().value
        val last = points.last().value
        val change = if (points.size >= 2) last - first else null
        val percent = if (change != null && first > PortfolioCalculator.EPS) change / first * 100.0 else null
        val tradesInRange = valid.any { it.time > dayEndMillis(start) }
        return PortfolioHistorySeries(points, skipped, change, percent, tradesInRange)
    }

    /** Bestand > 0 irgendwann ab [fromMillis] (Bestand davor oder eine spätere Transaktion mit Bestand). */
    private fun heldWithin(timeline: List<Pair<Long, Double>>, fromMillis: Long): Boolean {
        if (holdingsAt(timeline, fromMillis - 1) > 0.0) return true
        return timeline.any { (time, holdings) -> time >= fromMillis && holdings > 0.0 }
    }

    /** Schluss am Tag [day] oder der letzte davor; null vor der ersten Kerze. */
    internal fun closeOnOrBefore(sorted: List<Pair<Long, Double>>, day: Long): Double? {
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
}
