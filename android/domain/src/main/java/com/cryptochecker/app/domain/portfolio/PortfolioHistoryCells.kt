package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * Teile von [PortfolioHistory.build] (Audit 16.11.2026, A-02: die Funktion war 166 Zeilen lang): Kerzen mit
 * Identitätsprüfung, Werte je Coin und Tag, Zuflüsse für Gewinn/Verlust. Verhalten unverändert.
 */

/**
 * Je gehaltenem Coin die Kerzen aufsteigend, für «letzter bekannter Schluss» (USDT braucht keine). Identitätsprüfung:
 * liegt der jüngste Schluss um mehr als Faktor 3 neben dem aktuellen Kurs, gehört die Reihe wohl zu einem anderen
 * Token und zählt nicht (grosse echte Tagesbewegungen bleiben drin; der heutige Punkt nimmt den aktuellen Kurs).
 * Heute gehaltener Coin ohne aktuellen Kurs: Token nicht bestätigt → fehlt (strenge Prüfung); ohne heutigen Bestand
 * gibt es keinen Kurs zum Vergleichen, die Reihe bleibt.
 */
internal fun PortfolioHistory.verifiedCloses(
    held: List<String>,
    timelines: Map<String, List<Pair<Long, Double>>>,
    closes: Map<String, Map<Long, Double>>,
    livePrices: Map<String, Double>,
): Map<String, List<Pair<Long, Double>>> = held.filter { PortfolioStables.needsQuote(it) }.associateWith { coin ->
    val live = livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() }
    val heldToday = (timelines.getValue(coin).lastOrNull()?.second ?: 0.0) > 0.0
    closes[coin].orEmpty().entries.filter { it.value > 0.0 && it.value.isFinite() }
        .sortedBy { it.key }.map { it.key to it.value }
        .takeIf { (live == null && !heldToday) || PricePlausibility.closesMatchLive(it, live) }
        .orEmpty()
}

/**
 * Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief (null = nicht gehalten oder ohne Kurs) und die Coins, denen an
 * einem gehaltenen Tag der Kurs fehlte.
 */
internal fun PortfolioHistory.dayCells(
    included: List<String>,
    exactTimelines: Map<String, List<Pair<Long, BigDecimal>>>,
    sortedCloses: Map<String, List<Pair<Long, Double>>>,
    livePrices: Map<String, Double>,
    ranges: Map<String, Map<Long, PortfolioDayRange>>,
    start: Long,
    dayCount: Int,
    todayEpochDay: Long,
): Pair<HashMap<String, Array<PortfolioHistory.DayCell?>>, HashSet<String>> {
    val cells = HashMap<String, Array<PortfolioHistory.DayCell?>>()
    val unpriced = HashSet<String>()
    for (coin in included) {
        val timeline = exactTimelines.getValue(coin)
        val series = sortedCloses[coin].orEmpty()
        val row = arrayOfNulls<PortfolioHistory.DayCell>(dayCount)
        for (index in 0 until dayCount) {
            val day = start + index
            val isToday = day == todayEpochDay
            val amount = if (isToday) timeline.lastOrNull()?.second ?: BigDecimal.ZERO else holdingsAtExact(timeline, utcDayEnd(day))
            if (amount.signum() <= 0) continue
            val market = when {
                isToday -> livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() } ?: closeOnOrBefore(series, day)
                else -> closeOnOrBefore(series, day)
            }
            // Stablecoin-Regel: USDT 1, andere Stablecoins Kurs des Tags, sonst kein Wert
            val price = PortfolioStables.price(coin, market)
            if (price == null) {
                unpriced += coin
                continue
            }
            // Kerze genau dieses Tags (nicht fortgeschrieben); Hoch/Tief schliessen den Tageswert ein
            val dayRange = if (PortfolioStables.needsQuote(coin) && series.isNotEmpty()) {
                ranges[coin]?.get(day)?.takeIf { it.valid }
            } else null
            // Menge × Kurs exakt (Kurs über seine kürzeste Darstellung); Double erst in der Summe je Tag ([sumCells])
            fun times(p: Double): BigDecimal = amount.multiply(BigDecimal.valueOf(p), PortfolioDecimal.MC)
            row[index] = if (dayRange != null) {
                PortfolioHistory.DayCell(
                    times(price), times(dayRange.open), times(max(dayRange.high, price)), times(min(dayRange.low, price)), true,
                )
            } else {
                val v = times(price)
                PortfolioHistory.DayCell(v, v, v, v, false)
            }
        }
        cells[coin] = row
    }
    return cells to unpriced
}

/** Gewinn/Verlust-Teile: Coins mit Kurs an jedem gehaltenen Tag und bekannten Zuflüssen, übersprungene, ihre Zuflüsse. */
internal data class PnlParts(val coins: List<String>, val skipped: List<String>, val flows: List<PortfolioCashFlow>)

internal fun PortfolioHistory.pnlParts(
    drawn: List<String>,
    events: Map<String, List<PortfolioLedgerEvent>>,
    from: Long,
    todayEpochDay: Long,
    dayEndMillis: (Long) -> Long,
    rate: Double,
    sinceFirst: Boolean,
    market: (String, Long) -> Double?,
    method: CostMethod,
    entries: Map<String, PortfolioCashFlow>,
): PnlParts {
    val pnlFlows = ArrayList<PortfolioCashFlow>()
    val pnlSkipped = ArrayList<String>()
    val pnlCoins = ArrayList<String>()
    for (coin in drawn) {
        val coinFlows = pnlFlows(
            coin, events.getValue(coin), from, todayEpochDay, dayEndMillis, rate, sinceFirst, market, method,
            entries[coin]?.takeIf { sinceFirst || it.epochDay > from },
        )
        if (coinFlows == null) {
            pnlSkipped += coin
        } else {
            pnlCoins += coin
            pnlFlows += coinFlows
        }
    }
    return PnlParts(pnlCoins, pnlSkipped, pnlFlows)
}

/**
 * Marktkurs eines Tags (Auszahlung/Belohnung ohne Preis, Adress-Änderung, Gebühr im dritten Coin ohne Preis): heute der
 * aktuelle Kurs, sonst der Schluss. Ein Gebühr-Coin ohne Bestand im Zeitraum: seine Kerzen mit derselben
 * Identitätsprüfung. Teil von [PortfolioHistory.build].
 */
internal fun PortfolioHistory.marketAt(
    sortedCloses: Map<String, List<Pair<Long, Double>>>,
    closes: Map<String, Map<Long, Double>>,
    livePrices: Map<String, Double>,
    todayEpochDay: Long,
): (String, Long) -> Double? = { coin, day ->
    val live = livePrices[coin]?.takeIf { it > 0.0 && it.isFinite() }
    val series = sortedCloses[coin] ?: closes[coin].orEmpty().entries.filter { it.value > 0.0 && it.value.isFinite() }
        .sortedBy { it.key }.map { it.key to it.value }
        .takeIf { live == null || PricePlausibility.closesMatchLive(it, live) }.orEmpty()
    PortfolioStables.price(coin, if (day == todayEpochDay && live != null) live else closeOnOrBefore(series, day))
}
