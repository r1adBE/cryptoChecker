package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «7 T» im Gesamtwert-Kopf: Veränderung des heutigen Bestands gegen den Tagesschluss am Stichtag. */
class PortfolioPeriodChangeTest {

    private val today = 20_000L
    private val base = today - PortfolioPeriodChange.DAYS

    /** Tagesschlüsse von [from] bis heute mit [close] je Tag (heute zuletzt [last]). */
    private fun closes(from: Long, close: Double, last: Double = close): Map<Long, Double> =
        (from..today).associateWith { if (it == today) last else close }

    @Test
    fun `current holdings against the close on the base day`() {
        val change = PortfolioPeriodChange.change(
            holdings = mapOf("BTC" to 2.0, "ETH" to 10.0),
            current = mapOf("BTC" to 110.0, "ETH" to 9.0),
            closes = mapOf("BTC" to closes(today - 30, 100.0, 108.0), "ETH" to closes(today - 30, 10.0)),
            baseDay = base,
        )!!
        // then = 2·100 + 10·10 = 300; now = 2·110 + 10·9 = 310
        assertEquals(10.0, change.amountUsd, 1e-9)
        assertEquals(10.0 / 300.0 * 100.0, change.percent!!, 1e-9)
    }

    @Test
    fun `gap on the base day uses the last close before it`() {
        val series = mapOf(base - 2 to 50.0, base + 1 to 70.0, today to 80.0)
        val change = PortfolioPeriodChange.change(mapOf("SOL" to 1.0), mapOf("SOL" to 80.0), mapOf("SOL" to series), base)!!
        assertEquals(30.0, change.amountUsd, 1e-9)
        assertEquals(60.0, change.percent!!, 1e-9)
    }

    @Test
    fun `stablecoins - usdt flat, other stables close or 1`() {
        val change = PortfolioPeriodChange.change(
            holdings = mapOf("USDT" to 100.0, "USDC" to 50.0),
            current = mapOf("USDT" to 1.0, "USDC" to 1.0),
            closes = emptyMap(),
            baseDay = base,
        )!!
        assertEquals(0.0, change.amountUsd, 1e-9)
        assertEquals(0.0, change.percent!!, 1e-9)
    }

    @Test
    fun `stablecoin without close counts as 1 only while live price is near 1`() {
        // 100'000 USDC, aktuell 0.999, kein Schluss: nicht −100 aus einem nie gesehenen Kurs 1, sondern «—»
        assertNull(
            PortfolioPeriodChange.change(mapOf("USDC" to 100_000.0), mapOf("USDC" to 0.999), emptyMap(), base)
        )
        // Abweichung ≤ 0.1 %: weiterhin 1
        val near = PortfolioPeriodChange.change(mapOf("DAI" to 1_000.0), mapOf("DAI" to 1.0005), emptyMap(), base)!!
        assertEquals(0.5, near.amountUsd, 1e-9)
        // Reihe beginnt erst nach dem Stichtag: gleiche Regel
        assertNull(
            PortfolioPeriodChange.change(
                mapOf("USDC" to 10.0), mapOf("USDC" to 0.99), mapOf("USDC" to closes(today - 3, 0.99)), base,
            )
        )
        // USDT gilt immer 1 (keine Kerzen nötig)
        val usdt = PortfolioPeriodChange.change(mapOf("USDT" to 10.0), mapOf("USDT" to 1.0), emptyMap(), base)!!
        assertEquals(0.0, usdt.amountUsd, 1e-9)
        // Helfer direkt
        assertEquals(1.0, PortfolioStables.fallbackWithoutClose("USDT", 0.9)!!, 1e-9)
        assertEquals(1.0, PortfolioStables.fallbackWithoutClose("USDC", 1.0)!!, 1e-9)
        assertNull(PortfolioStables.fallbackWithoutClose("USDC", 0.998))
        assertNull(PortfolioStables.fallbackWithoutClose("USDC", Double.NaN))
        assertNull(PortfolioStables.fallbackWithoutClose("BTC", 1.0))
    }

    @Test
    fun `a coin without start price is left out, the others still count`() {
        val holdings = mapOf("BTC" to 1.0, "NEW" to 5.0)
        val current = mapOf("BTC" to 100.0, "NEW" to 2.0)
        // Kerzen eines Coins fehlen ganz (Abruf fehlgeschlagen): nur BTC zählt, NEW steht in skipped
        val missing = PortfolioPeriodChange.change(holdings, current, mapOf("BTC" to closes(today - 30, 90.0)), base)!!
        assertEquals(10.0, missing.amountUsd, 1e-9)
        assertEquals(listOf("NEW"), missing.skipped)
        // Reihe beginnt erst nach dem Stichtag (frisch gelistet): ebenso
        val late = PortfolioPeriodChange.change(
            holdings, current,
            mapOf("BTC" to closes(today - 30, 90.0), "NEW" to closes(today - 3, 2.0)),
            base,
        )!!
        assertEquals(10.0, late.amountUsd, 1e-9)
        assertEquals(listOf("NEW"), late.skipped)
        // Jüngster Schluss passt nicht zum aktuellen Kurs (anderer Token) und sonst nichts: unbekannt
        assertNull(
            PortfolioPeriodChange.change(mapOf("BTC" to 1.0), mapOf("BTC" to 100.0), mapOf("BTC" to closes(today - 30, 10.0)), base)
        )
        // Kein Bestand
        assertNull(PortfolioPeriodChange.change(emptyMap(), current, emptyMap(), base))
    }

    @Test
    fun `coins without current price are left out like in the total`() {
        val change = PortfolioPeriodChange.change(
            holdings = mapOf("BTC" to 1.0, "XYZ" to 3.0),
            current = mapOf("BTC" to 120.0),
            closes = mapOf("BTC" to closes(today - 30, 100.0, 118.0)),
            baseDay = base,
        )!!
        assertEquals(20.0, change.amountUsd, 1e-9)
        assertEquals(20.0, change.percent!!, 1e-9)
    }

    @Test
    fun `rewards in the window are reported as money put in`() {
        val base = 20_000L
        val closes = mapOf("ETH" to (base - 2..base + 7).associate { it to 100.0 })
        val trades = listOf(
            PortfolioTrade(1, "ETH", PortfolioTxType.BUY, 1.0, 100.0, base * 86_400_000L),
            PortfolioTrade(2, "ETH", PortfolioTxType.REWARD, 0.1, 110.0, (base + 3) * 86_400_000L),
        )
        val g = PortfolioPeriodChange.gain(trades, mapOf("ETH" to 120.0), closes, base)!!
        // Wert 1.1 × 120 = 132 − Anfang 100 − Belohnung 11 = 21; Belohnungen +11 extra genannt
        assertEquals(21.0, g.amountUsd, 1e-9)
        assertEquals(11.0, g.rewardsUsd, 1e-9)
    }

    @Test
    fun `market price at a time - close of its utc day, after the last close the live price`() {
        val day = 86_400_000L
        val btc = mapOf("BTC" to mapOf(today - 2 to 100.0, today - 1 to 110.0))
        // Mitten im Tag: Schluss dieses Tags; Kleinschreibung wie eingegeben
        assertEquals(100.0, PortfolioPeriodChange.marketPriceAt("btc", 112.0, btc, (today - 2) * day + day / 2)!!, 1e-9)
        // Heute (nach dem letzten Schluss): aktueller Kurs
        assertEquals(112.0, PortfolioPeriodChange.marketPriceAt("BTC", 112.0, btc, today * day + 1)!!, 1e-9)
        // Vor dem ersten Schluss bzw. ohne Schlusskurse: unbekannt
        assertNull(PortfolioPeriodChange.marketPriceAt("BTC", 112.0, btc, (today - 5) * day))
        assertNull(PortfolioPeriodChange.marketPriceAt("ETH", 3.0, btc, today * day))
        // USDT zählt 1
        assertEquals(1.0, PortfolioPeriodChange.marketPriceAt("USDT", null, emptyMap(), today * day)!!, 1e-9)
    }
}
