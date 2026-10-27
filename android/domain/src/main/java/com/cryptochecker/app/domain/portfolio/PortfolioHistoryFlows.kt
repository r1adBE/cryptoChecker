// Tagessummen und Zuflüsse des Wertverlaufs (Teil von [PortfolioHistory.build]): Summe der Coins je Tag, Zuflüsse für
// Gewinn/Verlust und Bestand im Zeitraum. Erweiterungsfunktionen, der Ablauf bleibt in `PortfolioHistory`.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistory.DayCell

/** Punkte je Tag als Summe der [coins] (× [rate]); Eröffnung/Hoch/Tief nur, wenn ein Coin eine Kerze hatte. */
fun PortfolioHistory.sumCells(
    coins: List<String>,
    cells: Map<String, Array<DayCell?>>,
    start: Long,
    dayCount: Int,
    rate: Double,
): List<PortfolioHistoryPoint> {
    val out = ArrayList<PortfolioHistoryPoint>(dayCount)
    for (index in 0 until dayCount) {
        // Exakt summiert, als Double erst für den Punkt (Anzeige, Chart)
        var total = BigDecimal.ZERO
        var totalOpen = BigDecimal.ZERO
        var totalHigh = BigDecimal.ZERO
        var totalLow = BigDecimal.ZERO
        var withRange = false
        for (coin in coins) {
            val cell = cells[coin]?.get(index) ?: continue
            total += cell.value
            totalOpen += cell.open
            totalHigh += cell.high
            totalLow += cell.low
            if (cell.withRange) withRange = true
        }
        val day = start + index
        out += if (withRange) {
            PortfolioHistoryPoint(day, total.toDouble() * rate, totalOpen.toDouble() * rate, totalHigh.toDouble() * rate, totalLow.toDouble() * rate)
        } else {
            PortfolioHistoryPoint(day, total.toDouble() * rate)
        }
    }
    return out
}

/**
 * Zuflüsse eines Coins aus seinen Schritten [events] ([PortfolioLedger.eventsByCoin]) für den Gewinn/Verlust
 * ([PortfolioLedger.flow]) in der Einheit des Verlaufs; null = einer unbekannt (Coin fehlt, «ohne …»). Im Zeitraum
 * die nach dem Ende von [start] (davor zählt der Wert am ersten Tag); «Seit 1. Kauf» ([sinceFirst]) alle, frühere
 * auf [start] gelegt. Auszahlungen ohne Preis und Adress-Änderungen zum Marktkurs ihres (UTC-)Tags ([market]).
 */
fun PortfolioHistory.pnlFlows(
    coin: String,
    events: List<PortfolioLedgerEvent>,
    start: Long,
    todayEpochDay: Long,
    dayEndMillis: (Long) -> Long,
    rate: Double,
    sinceFirst: Boolean,
    market: (String, Long) -> Double?,
    method: CostMethod,
): List<PortfolioCashFlow>? {
    val rangeEnd = dayEndMillis(start)
    val out = ArrayList<PortfolioCashFlow>()
    val book = PortfolioHoldingsBook(method)
    for (e in events) {
        if (sinceFirst || e.trade.time > rangeEnd) {
            val day = localDay(e.trade.time, start, todayEpochDay, dayEndMillis)
            val price = if (PortfolioLedger.needsMarketPrice(e)) market(coin, day) else null
            val feePrice = if (PortfolioLedger.needsFeeMarketPrice(e)) e.trade.thirdFeeCoin?.let { market(it, day) } else null
            val amount = PortfolioLedger.flow(e, book.capFor(e), price, feePrice, book.method) ?: return null
            if (amount != 0.0 && amount.isFinite()) out += PortfolioCashFlow(day, amount * rate)
        }
        book.apply(e)
    }
    return out
}

/**
 * Zuflüsse der [coins] (Schritte je Coin: [events], [PortfolioLedger.eventsByCoin]) nach dem Ende von [start]
 * (gleiche Regel wie [PortfolioPeriodChange.gain]: ein Verkauf ohne Bestand zählt nicht); null, sobald einem davon
 * der Preis fehlt. Tag = UTC-Tag der Transaktion, höchstens [todayEpochDay].
 */
fun PortfolioHistory.cashFlows(
    events: Map<String, List<PortfolioLedgerEvent>>,
    coins: List<String>,
    start: Long,
    todayEpochDay: Long,
    dayEndMillis: (Long) -> Long,
    rate: Double,
    market: (String, Long) -> Double?,
    method: CostMethod,
): List<PortfolioCashFlow>? {
    val rangeEnd = dayEndMillis(start)
    val out = ArrayList<PortfolioCashFlow>()
    for (coin in coins) {
        val book = PortfolioHoldingsBook(method)
        for (e in events[coin].orEmpty()) {
            if (e.trade.time > rangeEnd) {
                // Gleiche Zuflüsse wie «7 T» ([PortfolioLedger.flow]); unbekannt → keine Zuflüsse
                val day = localDay(e.trade.time, start, todayEpochDay, dayEndMillis)
                val price = if (PortfolioLedger.needsMarketPrice(e)) market(coin, day) else null
                val feePrice = if (PortfolioLedger.needsFeeMarketPrice(e)) e.trade.thirdFeeCoin?.let { market(it, day) } else null
                val amount = PortfolioLedger.flow(e, book.capFor(e), price, feePrice, book.method) ?: return null
                if (amount != 0.0 && amount.isFinite()) {
                    out += PortfolioCashFlow(day, amount * rate)
                }
            }
            book.apply(e)
        }
    }
    return out.sortedBy { it.epochDay }
}

/** Tag (UTC, [dayEndMillis]) von [millis] zwischen [from] und [to] (kleinster Tag, dessen Ende nicht davor liegt). */
fun PortfolioHistory.localDay(millis: Long, from: Long, to: Long, dayEndMillis: (Long) -> Long): Long {
    var lo = from
    var hi = to
    while (lo < hi) {
        val mid = lo + (hi - lo) / 2
        if (dayEndMillis(mid) < millis) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Bestand > 0 irgendwann ab [fromMillis] (Bestand davor oder eine spätere Transaktion mit Bestand). */
fun PortfolioHistory.heldWithin(timeline: List<Pair<Long, Double>>, fromMillis: Long): Boolean {
    if (holdingsAt(timeline, fromMillis - 1) > 0.0) return true
    return timeline.any { (time, holdings) -> time >= fromMillis && holdings > 0.0 }
}
