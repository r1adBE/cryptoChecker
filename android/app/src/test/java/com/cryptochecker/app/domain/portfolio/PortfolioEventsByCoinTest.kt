package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.performance.PortfolioPerformanceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Schritte aller Coins in einem Durchgang ([PortfolioLedger.eventsByCoin]) ergeben dasselbe wie die Rechnung je Coin
 * ([PortfolioLedger.events]): «Gewinn 7 T/1 J» ([PortfolioPeriodParts.parts] mit allen Coins gegen die Summe der
 * Einzelaufrufe) und Wertverlauf ([PortfolioHistory.cashFlows], [PortfolioHistory.pnlFlows]) — mit den Daten der
 * Leistungstests, jeder Einstandsmethode, roh und mit Tagesschluss bewertet. iOS: PortfolioEventsByCoinTests.swift.
 */
class PortfolioEventsByCoinTest {

    private val raw = PortfolioPerformanceData.trades(1_500)

    /** Ohne Wert erfasste Vorgänge zum Tagesschluss wie in der App (mehr bekannte Zuflüsse). */
    private val valued = PortfolioLedger.valueRewardsAtDayClose(
        PortfolioLedger.valueAtDayClose(raw, PortfolioPerformanceData::priceAt), PortfolioPerformanceData::priceAt,
    )

    private val day = 86_400_000L
    private val dayEnd: (Long) -> Long = { d -> (d + 1) * day - 1 }
    private val today = (PortfolioPerformanceData.END_MS - 1) / day
    private val market: (String, Long) -> Double? = { coin, d -> PortfolioPerformanceData.priceAtDouble(coin, d * day) }

    private fun close(label: String, expected: Double, actual: Double) =
        assertEquals(label, expected, actual, 1e-9 * max(1.0, abs(expected)))

    @Test
    fun eventsByCoinEqualsEventsPerCoin() {
        for (trades in listOf(raw, valued)) {
            val byCoin = PortfolioLedger.eventsByCoin(trades)
            val coins = PortfolioLedger.coins(trades)
            assertEquals(coins, byCoin.keys.toList())
            coins.forEach { coin -> assertEquals(coin, PortfolioLedger.events(coin, trades), byCoin.getValue(coin)) }
        }
    }

    @Test
    fun periodPartsAllCoinsMatchTheSumPerCoin() {
        val current = PortfolioPerformanceData.prices()
        val coins = PortfolioLedger.coins(raw)
        var counted = 0
        for (method in CostMethod.entries) {
            val end = PortfolioPerformanceData.END_MS
            for (windowStart in listOf(end - 7 * day, end - 365 * day)) {
                fun parts(only: String?) = PortfolioPeriodParts.parts(
                    raw, current, windowStart, only = only, method = method,
                    marketPrice = { coin, live, time -> live ?: PortfolioPerformanceData.priceAtDouble(coin, time) },
                    rates = PortfolioPeriodParts.Rates(0.9, 0.95) { 0.92 },
                    startPrice = { coin, _ -> PortfolioPerformanceData.priceAtDouble(coin, windowStart) },
                )
                val all = parts(null)
                val each = coins.map { parts(it) }
                val label = "$method $windowStart"
                close("$label gain", each.sumOf { it.gain }, all.gain)
                close("$label start", each.sumOf { it.start }, all.start)
                close("$label buys", each.sumOf { it.buys }, all.buys)
                close("$label held", each.sumOf { it.held }, all.held)
                close("$label rewards", each.sumOf { it.rewards }, all.rewards)
                close("$label skippedHeld", each.sumOf { it.skippedHeld }, all.skippedHeld)
                assertEquals("$label counted", each.sumOf { it.counted }, all.counted)
                assertEquals("$label windowTrades", each.sumOf { it.windowTrades }, all.windowTrades)
                assertEquals("$label skipped", each.flatMap { it.skipped }, all.skipped.toList())
                assertEquals("$label countedCoins", each.flatMap { it.countedCoins }.toSet(), all.countedCoins.toSet())
                counted += all.counted
            }
        }
        assertTrue("gezählte Coins: $counted", counted > 0)
    }

    @Test
    fun historyFlowsMatchTheFlowsPerCoin() {
        var flows = 0
        for (trades in listOf(raw, valued)) {
            val byCoin = PortfolioLedger.eventsByCoin(trades)
            val coins = PortfolioLedger.coins(trades)
            for (method in CostMethod.entries) {
                for (start in listOf(today - 7, today - 365, PortfolioPerformanceData.START_MS / day)) {
                    val label = "$method $start"
                    // Alle Coins in einem Aufruf gegen je Coin einzeln (Schritte je Coin neu gesammelt)
                    val all = PortfolioHistory.cashFlows(byCoin, coins, start, today, dayEnd, 0.9, market, method)
                    val perCoin = coins.map { coin ->
                        val single = mapOf(coin to PortfolioLedger.events(coin, trades))
                        PortfolioHistory.cashFlows(single, listOf(coin), start, today, dayEnd, 0.9, market, method)
                    }
                    val expected = if (perCoin.any { it == null }) null
                    else perCoin.flatMap { it!! }.sortedBy { it.epochDay }
                    assertEquals("$label cashFlows", expected, all)
                    for (sinceFirst in listOf(false, true)) {
                        coins.forEach { coin ->
                            val got = PortfolioHistory.pnlFlows(
                                coin, byCoin.getValue(coin), start, today, dayEnd, 0.9, sinceFirst, market, method,
                            )
                            val reference = PortfolioHistory.pnlFlows(
                                coin, PortfolioLedger.events(coin, trades), start, today, dayEnd, 0.9, sinceFirst,
                                market, method,
                            )
                            assertEquals("$label $coin pnlFlows", reference, got)
                            flows += got?.size ?: 0
                        }
                    }
                }
            }
        }
        assertTrue("Zuflüsse: $flows", flows > 0)
    }

    @Test
    fun valuedTradesKeepTheirCoins() {
        // Bewertung zum Tagesschluss ändert höchstens Preise, nicht die Coins (Voraussetzung der Vergleiche oben)
        assertEquals(PortfolioLedger.coins(raw), PortfolioLedger.coins(valued))
        assertEquals(raw.size, valued.size)
    }
}
