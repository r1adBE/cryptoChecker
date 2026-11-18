package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Jahresübersicht ([TaxYearSummaryBuilder]): Die Posten der Gewinne und Verluste entstehen in `build()`, nicht in einem
 * Initialisierer mitten in der Liste der Eigenschaften — sonst hinge die Rechnung an deren Reihenfolge (eine verschobene
 * Eigenschaft ergäbe ohne Compiler-Fehler leere Werte). Das Ergebnis ist dasselbe: Posten der Gewinne zuerst.
 */
class TaxYearSummaryBuilderOrderTest {

    /** Reihenfolge der Posten wie bisher: Gewinne/Verluste/Netto zuerst, danach Erlöse und Einkünfte. */
    @Test
    fun gainLinesComeFirst() {
        val day = 86_400_000L
        val t0 = 1_736_510_400_000L // 10.01.2025
        val trades = listOf(
            PortfolioTrade(
                id = 1,
                coin = "BTC",
                type = PortfolioTxType.BUY,
                amount = BigDecimal.ONE,
                priceUsdt = BigDecimal(1000),
                time = t0
            ),
            PortfolioTrade(
                id = 2,
                coin = "BTC",
                type = PortfolioTxType.SELL,
                amount = BigDecimal.ONE,
                priceUsdt = BigDecimal(1500),
                time =
                t0 + 30 * day
            ),
        )
        val summary = TaxExport.yearSummary(trades, TaxTemplate.CH, TaxMethod.FIFO, 2025, ZoneId.of("UTC"), "USD", null)
        assertEquals(
            listOf(TaxLine.GAINS, TaxLine.LOSSES, TaxLine.NET, TaxLine.PROCEEDS, TaxLine.INCOME),
            summary.lines.take(5).map {
                it.line
            }
        )
        val net = summary.lines.first { it.line == TaxLine.NET }
        assertEquals(0, BigDecimal(500).compareTo(net.usdt!!))
        assertEquals(0, BigDecimal(500).compareTo(summary.lines.first { it.line == TaxLine.GAINS }.usdt!!))
    }
}
