package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

/**
 * Jahresübersicht mit USDT = 1 an einem Tag ohne USDT/USD-Schluss (z. B. Kauf vor dem Fenster der Quellen): nie still —
 * [TaxYearSummary.usdtApproximate] und eine eigene Zeile in der CSV. iOS: `TaxSummaryUsdtTests`.
 */
class TaxSummaryUsdtTest {

    private val zone = ZoneId.of("UTC")
    private val buyDay = 20213L // 5.5.2025
    private val sellDay = 20270L // 1.7.2025
    private val trades = listOf(
        PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal.ONE, BigDecimal("10000"), 1_746_446_400_000L),
        PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal.ONE, BigDecimal("12000"), 1_751_371_200_000L),
    )
    private val rates = mapOf(buyDay to BigDecimal("0.83"), sellDay to BigDecimal("0.80"))
    private val texts = TaxSummaryTexts(
        title = "T", disclaimer = "D", item = "I", usdt = "USDT", target = "CHF", note = "N", orientation = "O",
        labels = emptyMap(), below = "below", reached = "reached", unknown = "unknown", incomplete = "inc", noFx = "nofx",
        usdtAssumed = "USDT dollar rate missing",
    )

    private fun summary(usdtUsd: Map<Long, BigDecimal>) =
        TaxExport.yearSummary(trades, TaxTemplate.NEUTRAL, TaxMethod.FIFO, 2025, zone, "CHF", rates, usdtUsd = usdtUsd)

    @Test
    fun allDaysCoveredStaysQuiet() {
        val s = summary(mapOf(buyDay to BigDecimal("0.97"), sellDay to BigDecimal("0.98")))
        assertFalse(s.usdtApproximate)
        val lines = TaxExport.summaryCsv(s, texts, emptyList()).removePrefix("﻿").split("\r\n")
        assertFalse(lines.contains("USDT dollar rate missing"))
    }

    @Test
    fun missingDayIsMarked() {
        // Kauftag ohne Schluss (vor dem Fenster): Einstand in CHF mit USDT = 1
        val s = summary(mapOf(sellDay to BigDecimal("0.98")))
        assertTrue(s.usdtApproximate)
        val lines = TaxExport.summaryCsv(s, texts, emptyList()).removePrefix("﻿").split("\r\n")
        // Nach Titel und Hinweis, vor der Spaltenzeile
        assertEquals(listOf("T", "D", "USDT dollar rate missing", "I;USDT;CHF;N"), lines.take(4))
    }

    @Test
    fun usdtCurrencyNeedsNoNote() {
        // Ohne USDT-Umrechnung (usdtUsd null) gibt es nichts anzunehmen
        val s = TaxExport.yearSummary(trades, TaxTemplate.NEUTRAL, TaxMethod.FIFO, 2025, zone, "CHF", rates)
        assertFalse(s.usdtApproximate)
    }
}
