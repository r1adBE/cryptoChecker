package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CutoffExportTest {

    private var nextId = 1L
    private val zurich = ZoneId.of("Europe/Zurich")
    private val d = 1e-9

    private fun buy(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.BUY, amount, price, time)

    private fun sell(coin: String, amount: Double, price: Double?, time: Long) =
        PortfolioTrade(nextId++, coin, PortfolioTxType.SELL, amount, price, time)

    private fun at(date: String, hour: Int, minute: Int = 0) =
        LocalDate.parse(date).atTime(hour, minute).atZone(zurich).toInstant().toEpochMilli()

    private val texts = CutoffCsvTexts(
        coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
        rate = "Kurs USD→CHF", valueTarget = "Wert CHF", note = "Hinweis", total = "Total",
        noPrice = "kein Kurs", noFx = "kein Devisenkurs", incomplete = "unvollständig",
        commentFormat = "# Stichtag %1\$s, Währung %2\$s, Devisenkurs vom %3\$s",
    )

    @Test
    fun endOfDayIsLastMillisecondInZone() {
        val end = CutoffExport.endOfDayMillis(LocalDate.of(2025, 12, 31), zurich)
        assertEquals(at("2026-01-01", 0) - 1, end)
    }

    @Test
    fun defaultDateIsLastNewYearsEve() {
        assertEquals(LocalDate.of(2025, 12, 31), CutoffExport.defaultDate(LocalDate.of(2026, 10, 4)))
        assertEquals(LocalDate.of(2025, 12, 31), CutoffExport.defaultDate(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun holdingsOnlyCountTradesUntilEndOfDay() {
        val cutoff = CutoffExport.endOfDayMillis(LocalDate.of(2025, 12, 31), zurich)
        val trades = listOf(
            buy("btc", 1.0, 100.0, at("2025-03-01", 12)),
            sell("BTC", 0.25, 200.0, at("2025-12-31", 23, 59)),   // zählt noch
            buy("BTC", 5.0, 300.0, at("2026-01-01", 0, 1)),       // nach dem Stichtag
            buy("ETH", 2.0, null, at("2025-06-01", 12)),
            sell("ETH", 2.0, 10.0, at("2025-07-01", 12)),         // ganz verkauft → fällt weg
            buy("ADA", 10.0, 0.5, at("2026-02-01", 12)),          // erst später gekauft
            buy("USDT", 50.0, 1.0, at("2025-01-01", 12)),
        )
        val h = CutoffExport.holdingsAt(trades, cutoff)
        assertEquals(listOf("BTC", "USDT"), h.map { it.coin })
        assertEquals(0.75, h[0].amount, d)
        assertEquals(50.0, h[1].amount, d)
    }

    @Test
    fun rowsUseStableOneAndMarkMissingPrice() {
        val holdings = listOf(
            CutoffHolding("BTC", 0.5),
            CutoffHolding("FOO", 3.0),
            CutoffHolding("USDC", 10.0),
        )
        val rows = CutoffExport.rows(holdings, mapOf("BTC" to 90000.0), 0.8)
        assertEquals(45000.0, rows[0].valueUsdt!!, d)
        assertEquals(36000.0, rows[0].valueTarget!!, d)
        assertTrue(rows[1].noPrice)
        assertNull(rows[1].valueUsdt)
        assertNull(rows[1].valueTarget)
        assertEquals(1.0, rows[2].priceUsdt!!, d)
        assertEquals(8.0, rows[2].valueTarget!!, d)
    }

    @Test
    fun csvLayout() {
        val rows = CutoffExport.rows(
            listOf(CutoffHolding("BTC", 0.5), CutoffHolding("FOO", 3.0)),
            mapOf("BTC" to 87654.321),
            0.79123,
        )
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", "2025-12-31", rows, texts)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("# Stichtag 2025-12-31, Währung CHF, Devisenkurs vom 2025-12-31", lines[0])
        assertEquals("Coin;Menge;Kurs USDT;Wert USDT;Kurs USD→CHF;Wert CHF;Hinweis", lines[1])
        assertEquals("BTC;0.5;87654.321;43827.16;0.79123;34677.36;", lines[2])
        assertEquals("FOO;3;;;0.79123;;kein Kurs", lines[3])
        assertEquals("Total;;;43827.16;0.79123;34677.36;unvollständig", lines[4])
        assertEquals("", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun csvWithoutFxRate() {
        val rows = CutoffExport.rows(listOf(CutoffHolding("ETH", 2.0)), mapOf("ETH" to 3000.0), null)
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "CHF", null, rows, texts)
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("# Stichtag 2025-12-31, Währung CHF, Devisenkurs vom —", lines[0])
        assertEquals("ETH;2;3000;6000.00;;;kein Devisenkurs", lines[2])
        assertEquals("Total;;;6000.00;;;kein Devisenkurs", lines[3])
    }

    @Test
    fun emptyHoldingsGiveZeroTotal() {
        val csv = CutoffExport.csv(LocalDate.of(2025, 12, 31), "USD", null, emptyList(), texts)
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("Total;;;0.00;;;", lines[2])
    }

    @Test
    fun numberFormats() {
        assertEquals("0.0000123", CutoffExport.price(0.0000123))
        assertEquals("0.00", CutoffExport.money(-0.001))
        assertEquals("1234567.89", CutoffExport.money(1234567.891))
        assertEquals("12", CutoffExport.amount(12.0))
        // Kleinstkurse mit gültigen Stellen statt «0», kaufmännisch gerundet wie iOS
        assertEquals("0.00000000003", CutoffExport.price(3e-11))
        assertEquals("1.01", CutoffExport.money(1.005))
        assertEquals("", CutoffExport.money(Double.NaN))
        assertEquals("\"a;b\"", CutoffExport.escape("a;b"))
        assertEquals("\"x\"\"y\"", CutoffExport.escape("x\"y"))
    }
}
