package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cryptochecker.app.domain.alarm.ThresholdParser
import java.math.BigDecimal

/**
 * Exakte Buchhaltung (BigDecimal, 1 Satoshi = 0.00000001 BTC), Gebühren und Gewinn je
 * Zeitraum mit Käufen/Verkäufen. Wie `PortfolioLedgerTests` (iOS).
 */
class PortfolioLedgerTest {

    private fun dec(s: String) = BigDecimal(s)

    private fun trade(
        id: Long, coin: String, type: PortfolioTxType, amount: String, price: String?, time: Long,
        fee: String? = null, unit: PortfolioFeeUnit? = null,
    ) = PortfolioTrade(id, coin, type, dec(amount), price?.let(::dec), time, fee?.let(::dec), unit)

    // ---------------- exakt ----------------

    @Test
    fun `unrealized gain is exact decimal`() {
        // 0.1 + 0.2 BTC zu 0.1 und 0.2: Double hätte 0.30000000000000004 — exakt bleibt der Gewinn exakt
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "0.1", "0.1", 1),
                trade(2, "BTC", PortfolioTxType.BUY, "0.2", "0.2", 2),
            ),
            0.3,
        )
        assertEquals(0, dec("0.09").compareTo(p.valueExact))
        assertEquals(0, dec("0.04").compareTo(p.unrealizedExact))
        assertEquals(0.04, p.unrealized!!, 0.0)
        val s = PortfolioCalculator.summarize(
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "0.1", "0.1", 1),
                trade(2, "BTC", PortfolioTxType.BUY, "0.2", "0.2", 2),
                trade(3, "ETH", PortfolioTxType.BUY, "1", "0.7", 3),
            ),
            mapOf("BTC" to 0.3, "ETH" to 0.8),
        )
        // 0.04 + 0.1 exakt summiert
        assertEquals(0.14, s.unrealized!!, 0.0)
    }

    @Test
    fun `average cost names the part with a buy price`() {
        val p = PortfolioCalculator.position(
            "ETH",
            listOf(
                trade(1, "ETH", PortfolioTxType.BUY, "0.5", "100", 1),
                trade(2, "ETH", PortfolioTxType.BUY, "1.5", null, 2),
            ),
            150.0,
        )
        assertTrue(p.priceMissing)
        assertTrue(p.avgCostPartial)
        assertEquals(0, dec("0.5").compareTo(p.pricedHoldingsExact))
        // «Seit Kauf» des Teils mit Kaufpreis: 0.5 × 150 − 50
        assertEquals(0, dec("25").compareTo(p.unrealizedExact))
        assertEquals(0, dec("1.5").compareTo(p.unpricedHoldingsExact))
        // Ganzer Bestand mit Kaufpreis: kein Zusatz
        val full = PortfolioCalculator.position("ETH", listOf(trade(1, "ETH", PortfolioTxType.BUY, "2", "100", 1)), 150.0)
        assertFalse(full.avgCostPartial)
        assertEquals(0, dec("2").compareTo(full.pricedHoldingsExact))
        // Kein Kauf mit Preis: kein Ø, kein Zusatz
        val none = PortfolioCalculator.position("ETH", listOf(trade(1, "ETH", PortfolioTxType.BUY, "2", null, 1)), 150.0)
        assertFalse(none.avgCostPartial)
    }

    @Test
    fun `divisions round to 34 significant digits`() {
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "3", "10", 1, "1", PortfolioFeeUnit.QUOTE),
                trade(2, "BTC", PortfolioTxType.SELL, "1", "20", 2),
            ),
            15.0,
        )
        assertEquals("20.66666666666666666666666666666667", p.costBasisExact!!.toPlainString())
        assertEquals("9.66666666666666666666666666666667", p.realizedExact.toPlainString())
    }

    @Test
    fun `one satoshi stays one satoshi`() {
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "1", "60000", 1),
                trade(2, "BTC", PortfolioTxType.SELL, "0.99999999", "61000", 2),
            ),
            61_000.0,
        )
        assertEquals(0, dec("0.00000001").compareTo(p.holdingsExact))
        assertTrue(p.isOpen)
        assertEquals("0.00000001", PortfolioDecimal.text(p.holdingsExact))
    }

    @Test
    fun `tenths add up exactly and close the position`() {
        val p = PortfolioCalculator.position(
            "ETH",
            listOf(
                trade(1, "ETH", PortfolioTxType.BUY, "0.1", "2000", 1),
                trade(2, "ETH", PortfolioTxType.BUY, "0.2", "2000", 2),
                trade(3, "ETH", PortfolioTxType.SELL, "0.3", "2100", 3),
            ),
            2_100.0,
        )
        assertEquals(0, BigDecimal.ZERO.compareTo(p.holdingsExact))
        assertFalse(p.isOpen)
        assertFalse(p.oversold)
        // (2100 − 2000) × 0.3 = 30 exakt
        assertEquals(0, dec("30").compareTo(p.realizedExact))
    }

    @Test
    fun `eighteen decimals are kept`() {
        val p = PortfolioCalculator.position(
            "ETH", listOf(trade(1, "ETH", PortfolioTxType.BUY, "0.000000000000000001", "2000", 1)), 2_000.0,
        )
        assertEquals("0.000000000000000001", PortfolioDecimal.text(p.holdingsExact))
    }

    @Test
    fun `double constructor uses shortest representation`() {
        val t = PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 0.1, 0.3, 1)
        assertEquals(0, dec("0.1").compareTo(t.amount))
        assertEquals(0, dec("0.3").compareTo(t.priceUsdt!!))
        assertNull(PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, Double.NaN, 1).priceUsdt)
    }

    @Test
    fun `typed amounts are read exactly`() {
        val sep = '.'
        assertEquals("0.00000001", PortfolioDecimal.text(ThresholdParser.parseDecimal("0.00000001", sep)!!))
        assertEquals("0.00000001", PortfolioDecimal.text(ThresholdParser.parseDecimal("0,00000001", ',')!!))
        assertEquals("1234.56789012", PortfolioDecimal.text(ThresholdParser.parseDecimal("1’234.56789012", sep)!!))
        assertEquals("21000000.00000001", PortfolioDecimal.text(ThresholdParser.parseDecimal("21'000'000.00000001", sep)!!))
        assertEquals(0, BigDecimal.ZERO.compareTo(ThresholdParser.parseDecimalAllowingZero("0,00", ',')!!))
        assertNull(ThresholdParser.parseDecimal("1e-8", sep))
        assertNull(ThresholdParser.parseDecimal("-1", sep))
    }

    @Test
    fun `absurd magnitudes are invalid`() {
        // Manipulierte Sicherung: sonst OutOfMemoryError bei toPlainString/setScale
        assertNull(PortfolioDecimal.parse("1E300000000"))
        assertNull(PortfolioDecimal.parse("-1E300000000"))
        assertNull(PortfolioDecimal.parse("1E-300000000"))
        assertNull(PortfolioDecimal.parse("1E30"))
        assertNull(PortfolioDecimal.parse("1" + "0".repeat(30)))
        assertNull(PortfolioDecimal.parse("1E-41"))
        // Grenzen: 30 Stellen vor dem Komma, 40 Nachkommastellen (Nullen am Ende zählen nicht)
        assertEquals("9".repeat(30), PortfolioDecimal.parse("9".repeat(30))!!.toPlainString())
        assertEquals(0, dec("1E-40").compareTo(PortfolioDecimal.parse("1E-40")!!))
        assertEquals(0, dec("2.5").compareTo(PortfolioDecimal.parse("2.5" + "0".repeat(60))!!))
        assertEquals(0, dec("0.00000001").compareTo(PortfolioDecimal.parse("1.0E-8")!!))
        assertNull(ThresholdParser.parseDecimal("1" + "0".repeat(30), '.'))
    }

    @Test
    fun `today gain removes a buy made today`() {
        val start = 1_000_000L
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, start - 10),
            PortfolioTrade(2, "BTC", PortfolioTxType.BUY, 1.0, 110.0, start + 10),
        )
        // Kursänderung des heutigen Bestands (2 BTC, 100 → 110): +20; gekauft wurde 1 BTC erst zu 110
        val adjusted = PortfolioPeriodChange.todayAdjusted(
            TodayChange(20.0, 10.0), trades, mapOf("BTC" to 110.0), mapOf("BTC" to 10.0), start,
        )!!
        assertEquals(10.0, adjusted.amountUsd, 1e-9)
    }

    @Test
    fun `today gain leaves out a coin with an unpriced buy today like 7 D`() {
        val start = 1_000_000L
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, start - 10),
            PortfolioTrade(2, "ETH", PortfolioTxType.BUY, 2.0, 200.0, start - 10),
            // übernommener Bestand ohne Preis: Zufluss unbekannt → ETH fehlt ganz
            PortfolioTrade(3, "ETH", PortfolioTxType.BUY, 1.0, null, start + 10),
        )
        val current = mapOf("BTC" to 110.0, "ETH" to 220.0)
        val coinChanges = mapOf("BTC" to 10.0, "ETH" to 10.0)
        val raw = PortfolioPeriodChange.todayFromCoinChanges(mapOf("BTC" to 1.0, "ETH" to 3.0), current, coinChanges)!!
        assertEquals(70.0, raw.amountUsd, 1e-9)
        val adjusted = PortfolioPeriodChange.todayAdjusted(raw, trades, current, coinChanges, start)!!
        // nur BTC zählt: +10 auf 100
        assertEquals(10.0, adjusted.amountUsd, 1e-9)
        assertEquals(10.0, adjusted.percent!!, 1e-9)
        assertEquals(listOf("ETH"), adjusted.skipped)
    }

    @Test
    fun `today gain without any counted coin is unknown`() {
        val start = 1_000_000L
        val trades = listOf(PortfolioTrade(1, "ETH", PortfolioTxType.BUY, 1.0, null, start + 10))
        val adjusted = PortfolioPeriodChange.todayAdjusted(
            TodayChange(5.0, 2.0), trades, mapOf("ETH" to 220.0), mapOf("ETH" to 10.0), start,
        )
        assertNull(adjusted)
    }

    @Test
    fun `today gain counts a coin bought entirely today without change`() {
        val start = 1_000_000L
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, start - 10),
            PortfolioTrade(2, "NEW", PortfolioTxType.BUY, 10.0, 1.0, start + 10),
        )
        val current = mapOf("BTC" to 110.0, "NEW" to 1.5)
        val coinChanges = mapOf("BTC" to 10.0)
        val raw = PortfolioPeriodChange.todayFromCoinChanges(mapOf("BTC" to 1.0, "NEW" to 10.0), current, coinChanges)!!
        assertEquals(listOf("NEW"), raw.skipped)
        val adjusted = PortfolioPeriodChange.todayAdjusted(raw, trades, current, coinChanges, start)!!
        // BTC +10, NEW 15 − 10 = +5; Basis 100 + 10
        assertEquals(15.0, adjusted.amountUsd, 1e-9)
        assertEquals(15.0 / 110.0 * 100.0, adjusted.percent!!, 1e-9)
        assertEquals(emptyList<String>(), adjusted.skipped)
    }

    // ---------------- Gebühren ----------------

    @Test
    fun `quote fee on buy raises cost basis`() {
        val p = PortfolioCalculator.position(
            "BTC", listOf(trade(1, "BTC", PortfolioTxType.BUY, "0.5", "60000", 1, "12.5", PortfolioFeeUnit.QUOTE)), 60_000.0,
        )
        assertEquals(0, dec("30012.5").compareTo(p.costBasisExact!!))
        assertEquals(60_025.0, p.avgCost!!, 1e-9)
        assertEquals(-12.5, p.unrealized!!, 1e-9)
    }

    @Test
    fun `coin fee on buy reduces holdings, not cost`() {
        val p = PortfolioCalculator.position(
            "BTC", listOf(trade(1, "BTC", PortfolioTxType.BUY, "1", "100", 1, "0.001", PortfolioFeeUnit.COIN)), 100.0,
        )
        assertEquals(0, dec("0.999").compareTo(p.holdingsExact))
        assertEquals(0, dec("100").compareTo(p.costBasisExact!!))
    }

    @Test
    fun `quote fee on sell reduces realized`() {
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "1", "100", 1),
                trade(2, "BTC", PortfolioTxType.SELL, "1", "150", 2, "2", PortfolioFeeUnit.QUOTE),
            ),
            150.0,
        )
        assertEquals(0, dec("48").compareTo(p.realizedExact))
    }

    @Test
    fun `coin fee on sell removes amount plus fee`() {
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "2", "100", 1),
                trade(2, "BTC", PortfolioTxType.SELL, "1", "150", 2, "0.1", PortfolioFeeUnit.COIN),
            ),
            150.0,
        )
        assertEquals(0, dec("0.9").compareTo(p.holdingsExact))
        // Erlös 150 − Einstand der abgegebenen 1.1 (110) = 40
        assertEquals(0, dec("40").compareTo(p.realizedExact))
    }

    // ---------------- Gewinn im Zeitraum ----------------

    private val today = 20_000L
    private val base = today - PortfolioPeriodChange.DAYS
    private val dayMs = 86_400_000L

    private fun flat(close: Double) = (today - 30..today).associateWith { close }

    @Test
    fun `buy inside the window is not counted as gain`() {
        val before = (base - 3) * dayMs
        val inside = (base + 2) * dayMs
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, before),
            PortfolioTrade(2, "BTC", PortfolioTxType.BUY, 1.0, 100.0, inside),
        )
        val gain = PortfolioPeriodChange.gain(trades, mapOf("BTC" to 100.0), mapOf("BTC" to flat(100.0)), base)!!
        assertEquals(0.0, gain.amountUsd, 1e-9)
        // Reine Kursänderung des heutigen Bestands ist ebenfalls 0, wäre bei «Wert jetzt − Wert damals» aber +100
    }

    @Test
    fun `sell inside the window is not a loss`() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 2.0, 100.0, (base - 3) * dayMs),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, 1.0, 120.0, (base + 2) * dayMs),
        )
        val gain = PortfolioPeriodChange.gain(trades, mapOf("BTC" to 120.0), mapOf("BTC" to flat(100.0)), base)!!
        // Anfang 2 × 100 = 200; Ende 1 × 120 + Verkauf 120 = 240 → +40
        assertEquals(40.0, gain.amountUsd, 1e-9)
        assertEquals(20.0, gain.percent!!, 1e-9)
    }

    @Test
    fun `fee in the window counts as cost`() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, (base + 2) * dayMs, fee = 1.0, feeUnit = PortfolioFeeUnit.QUOTE),
        )
        val gain = PortfolioPeriodChange.gain(trades, mapOf("BTC" to 100.0), mapOf("BTC" to flat(100.0)), base)!!
        assertEquals(-1.0, gain.amountUsd, 1e-9)
    }

    @Test
    fun `fee of a sell with nothing left does not count, like realized`() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, (base - 3) * dayMs),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, 1.0, 120.0, (base + 2) * dayMs),
            // Nichts mehr da: weder Verkauf noch Gebühr zählen (realisiert ignoriert ihn ebenso)
            PortfolioTrade(3, "BTC", PortfolioTxType.SELL, 0.5, 120.0, (base + 3) * dayMs, fee = 5.0, feeUnit = PortfolioFeeUnit.QUOTE),
        )
        val gain = PortfolioPeriodChange.gain(trades, mapOf("BTC" to 120.0), mapOf("BTC" to flat(100.0)), base)!!
        assertEquals(20.0, gain.amountUsd, 1e-9)
    }

    @Test
    fun `coin fee beyond the holdings marks the sell as too large`() {
        val p = PortfolioCalculator.position(
            "BTC",
            listOf(
                trade(1, "BTC", PortfolioTxType.BUY, "1", "100", 1),
                trade(2, "BTC", PortfolioTxType.SELL, "1", "120", 2, fee = "0.01", unit = PortfolioFeeUnit.COIN),
            ),
            120.0,
        )
        assertTrue(p.oversold)
    }

    @Test
    fun `no history for a held coin gives unknown`() {
        val trades = listOf(PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, (base - 3) * dayMs))
        assertNull(PortfolioPeriodChange.gain(trades, mapOf("BTC" to 100.0), emptyMap(), base))
    }

    @Test
    fun `one coin without history does not erase the period for the others`() {
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, 1.0, 100.0, (base - 3) * dayMs),
            PortfolioTrade(2, "NEWCOIN", PortfolioTxType.BUY, 10.0, 1.0, (base - 3) * dayMs),
        )
        val gain = PortfolioPeriodChange.gain(
            trades, mapOf("BTC" to 110.0, "NEWCOIN" to 1.5), mapOf("BTC" to flat(100.0)), base,
        )!!
        // Nur BTC: 100 → 110
        assertEquals(10.0, gain.amountUsd, 1e-9)
        assertEquals(10.0, gain.percent!!, 1e-9)
        assertEquals(listOf("NEWCOIN"), gain.skipped)
    }

    @Test
    fun `period holdings are summed exactly`() {
        // 0.1 + 0.2 als Double wäre 0.30000000000000004; verkauft 0.3 → exakt 0, kein Rest-Bestand
        val trades = listOf(
            trade(1, "BTC", PortfolioTxType.BUY, "0.1", "100", (base - 3) * dayMs),
            trade(2, "BTC", PortfolioTxType.BUY, "0.2", "100", (base - 2) * dayMs),
            trade(3, "BTC", PortfolioTxType.SELL, "0.3", "100", (base + 2) * dayMs),
        )
        val gain = PortfolioPeriodChange.gain(trades, mapOf("BTC" to 100.0), mapOf("BTC" to flat(100.0)), base)!!
        assertEquals(0.0, gain.amountUsd, 1e-12)
    }

    // ---------------- Heute = eine Zahl für Karte, Widget und Alarm ----------------

    @Test
    fun `today leaves out a coin without change instead of diluting the percent`() {
        // BTC +10 % (90 → 99), ALT ohne Vergleichskurs: nicht flach mitgezählt (verwässert), sondern draussen
        val change = PortfolioPeriodChange.todayFromCoinChanges(
            holdings = mapOf("BTC" to 1.0, "ALT" to 100.0),
            current = mapOf("BTC" to 99.0, "ALT" to 0.2),
            coinChanges = mapOf("BTC" to 10.0),
        )!!
        assertEquals(9.0, change.amountUsd, 1e-9)
        assertEquals(10.0, change.percent!!, 1e-9)
        assertEquals(listOf("ALT"), change.skipped)
    }

    @Test
    fun `today counts usdt as one to one`() {
        val change = PortfolioPeriodChange.todayFromCoinChanges(
            holdings = mapOf("BTC" to 1.0, "USDT" to 100.0),
            current = mapOf("BTC" to 110.0, "USDT" to 1.0),
            coinChanges = mapOf("BTC" to 10.0),
        )!!
        assertEquals(10.0, change.amountUsd, 1e-9)
        assertEquals(5.0, change.percent!!, 1e-9)
    }

    // ---------------- Frische je Coin ----------------

    @Test
    fun `one old coin does not stop recording while most of the value is fresh`() {
        val now = 10_000_000L
        val staleAfter = 900_000L
        val values = mapOf("BTC" to 900.0, "ALT" to 100.0)
        val times = mapOf("BTC" to now - 1_000, "ALT" to now - 3_600_000)
        assertEquals(0.9, PortfolioPriceFallback.freshShare(values, times, now, staleAfter)!!, 1e-9)
        assertTrue(PortfolioPriceFallback.mayRecordShare(values, times, now, staleAfter, anyPrice = true))
        // Umgekehrt: der grosse Coin ist alt → keine Aufnahme
        val oldBig = mapOf("BTC" to now - 3_600_000, "ALT" to now - 1_000)
        assertFalse(PortfolioPriceFallback.mayRecordShare(values, oldBig, now, staleAfter, anyPrice = true))
        // USDT gilt immer als frisch
        assertEquals(1.0, PortfolioPriceFallback.freshShare(mapOf("USDT" to 50.0), emptyMap(), now, staleAfter)!!, 1e-9)
    }

    // ---------------- gleiche Zeit ----------------

    @Test
    fun `same time books inflows before outflows then id`() {
        // Export «neueste zuerst»: Verkauf hat die kleinere Id als der Kauf derselben Sekunde
        val trades = listOf(
            trade(1, "BTC", PortfolioTxType.SELL, "0.5", "60000", 100),
            trade(2, "BTC", PortfolioTxType.BUY, "1", "59000", 100),
            PortfolioTrade(3, "ETH", PortfolioTxType.SWAP, dec("1"), null, 100, toCoin = "BTC", toAmount = dec("0.05")),
            trade(4, "BTC", PortfolioTxType.WITHDRAWAL, "0.01", null, 100),
            trade(5, "BTC", PortfolioTxType.DEPOSIT, "0.2", null, 50),
        )
        val ids = PortfolioLedger.events("BTC", trades).map { it.trade.id }
        assertEquals(listOf(5L, 2L, 3L, 1L, 4L), ids)
        assertEquals(ids, PortfolioLedger.eventsByCoin(trades).getValue("BTC").map { it.trade.id })
        // Bestand: 0.2 + 1 + 0.05 − 0.5 − 0.01 (der Verkauf wird nicht auf 0.2 gekappt)
        val holdings = PortfolioLedger.events("BTC", trades).fold(BigDecimal.ZERO) { h, e -> PortfolioLedger.holdingsAfter(h, e) }
        assertEquals(0, dec("0.74").compareTo(holdings))
    }
}
