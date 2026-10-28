package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.performance.PerformanceTiming.TRANSACTIONS
import com.cryptochecker.app.performance.PortfolioPerformanceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Schnelle Wege liefern dieselben Zahlen wie die Referenz-Wege:
 *  - Ø ohne Herkunftsteile ([PortfolioCalculator.summarize] `withParts = false`): alles gleich ausser den leeren Teilen;
 *  - Schritte je Coin einmal gesammelt ([PortfolioLedger.eventsByCoin], [PortfolioCalculator.ledgers]): gleich wie je
 *    Coin über alle Transaktionen ([PortfolioLedger.events], [PortfolioCalculator.ledger]) — Bücher, Steuer-Vermerke,
 *    Bestandsverlauf;
 *  - [PortfolioSummaryCache]: gleiche Eingaben nur einmal gerechnet, andere neu.
 * Daten: das grosse Testportfolio ([PortfolioPerformanceData], alle Arten, Gebühren in allen Formen) und ein kleiner Fall
 * mit Gebühr-Vermerken.
 */
class PortfolioLinearPathsTest {

    private val trades = PortfolioPerformanceData.trades(TRANSACTIONS.small)
    private val prices = PortfolioPerformanceData.prices()
    private val zone = ZoneOffset.UTC

    /** Netzwerkgebühr verbraucht den Posten mit Kaufpreis (Einstand vermerkt), Gebühr USDT auf Bestand ohne Kaufpreis. */
    private val feeNotes = listOf(
        trade(1, "BTC", PortfolioTxType.BUY, "1", "100", T0),
        trade(2, "BTC", PortfolioTxType.TRANSFER, "1", null, T0 + DAY, fee = "1", unit = PortfolioFeeUnit.COIN),
        trade(3, "ETH", PortfolioTxType.DEPOSIT, "2", null, T0),
        trade(4, "ETH", PortfolioTxType.TRANSFER, "1", null, T0 + DAY, fee = "5", unit = PortfolioFeeUnit.QUOTE),
        trade(5, "BNB", PortfolioTxType.BUY, "1", "300", T0),
        trade(6, "SOL", PortfolioTxType.BUY, "10", "10", T0 + DAY).copy(
            feeCoin = "BNB", feeCoinAmount = BigDecimal("0.1"), feeCoinPrice = BigDecimal("300"),
        ),
    )

    @Test
    fun averageWithoutPartsSameNumbers() {
        val with = PortfolioCalculator.summarize(trades, prices, CostMethod.AVERAGE, PortfolioPerformanceData::priceAtDouble)
        val without = PortfolioCalculator.summarize(
            trades, prices, CostMethod.AVERAGE, PortfolioPerformanceData::priceAtDouble, withParts = false,
        )
        assertTrue("Testdaten führen Herkunftsteile", with.open.any { it.pricedCostParts.isNotEmpty() })
        assertTrue(without.open.all { it.pricedCostParts.isEmpty() })
        assertEquals(with.noParts(), without)
        // Bücher: Veräusserungen, Auszahlungen, Vermerke gleich; nur die Teile fehlen
        val books = PortfolioCalculator.ledgers(trades, CostMethod.AVERAGE)
        val leanBooks = PortfolioCalculator.ledgers(trades, CostMethod.AVERAGE, withParts = false)
        assertTrue(books.any { b -> b.disposals.any { it.costParts.isNotEmpty() } })
        assertEquals(books.map { it.noParts() }, leanBooks)
        // FIFO führt Teile je Posten ohnehin: identisch
        for (m in listOf(CostMethod.FIFO, CostMethod.FIFO_WALLET)) {
            val lean = PortfolioCalculator.summarize(trades, prices, m, withParts = false)
            assertEquals(m.name, PortfolioCalculator.summarize(trades, prices, m), lean)
        }
    }

    @Test
    fun eventsByCoinSameAsEventsPerCoin() {
        for (data in listOf(trades, feeNotes)) {
            val byCoin = PortfolioLedger.eventsByCoin(data)
            assertEquals(PortfolioLedger.coins(data), byCoin.keys.toList())
            for ((coin, events) in byCoin) assertEquals(coin, PortfolioLedger.events(coin, data), events)
        }
    }

    @Test
    fun ledgersSameAsLedgerPerCoin() {
        for (data in listOf(trades, feeNotes)) {
            for (m in CostMethod.entries) {
                val old = PortfolioLedger.coins(data).map { PortfolioCalculator.ledger(it, data, null, m) }
                assertEquals(m.name, old, PortfolioCalculator.ledgers(data, m))
            }
        }
    }

    @Test
    fun feeNotesSameAsBefore() {
        val rulesSets = listOf(TaxEventRules.NONE, TaxTemplate.UK.eventRules, TaxTemplate.CA.eventRules)
        for (data in listOf(trades, feeNotes)) {
            for (m in CostMethod.entries) {
                for (rules in rulesSets) {
                    val books = referenceBooks(data, rules, m)
                    for (year in listOf(2020, 2023, 2024, 2025)) {
                        val period = TaxExport.calendar(year)
                        val method = TaxMethod.of(m)
                        fun inPeriod(time: Long) = TaxExport.localDate(time, zone) in period
                        val lost = books.flatMap { it.lostFeeCosts }.filter { inPeriod(it.time) }
                            .sortedWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId }.thenBy { it.coin })
                        val unassigned = books.flatMap { it.unassignedFees }.filter { inPeriod(it.time) }
                            .sortedWith(compareBy<PortfolioUnassignedFee> { it.time }.thenBy { it.txId }.thenBy { it.coin })
                        assertEquals("$m $year", lost, TaxExport.lostFeeCostsIn(data, method, period, zone, rules))
                        assertEquals("$m $year", unassigned, TaxExport.unassignedFeesIn(data, method, period, zone, rules))
                    }
                }
            }
        }
        // Der kleine Fall hat beide Vermerke (Ø)
        assertTrue(TaxExport.lostFeeCostsIn(feeNotes, CostMethod.AVERAGE, 2024, zone).isNotEmpty())
        assertTrue(TaxExport.unassignedFeesIn(feeNotes, CostMethod.AVERAGE, 2024, zone).isNotEmpty())
    }

    @Test
    fun holdingsTimelineFromCollectedEvents() {
        val byCoin = PortfolioLedger.eventsByCoin(trades)
        for (m in CostMethod.entries) {
            for ((coin, events) in byCoin) {
                val old = PortfolioHistory.holdingsTimelineExact(coin, trades, m)
                assertEquals("$coin $m", old, PortfolioHistory.holdingsTimelineOf(events, m))
            }
        }
    }

    @Test
    fun summaryCacheHitsAndMisses() {
        var computed = 0
        val cache = PortfolioSummaryCache { t, p, m, parts ->
            computed++
            PortfolioCalculator.summarize(t, p, m, withParts = parts)
        }
        val first = cache.summarize(trades, prices, CostMethod.FIFO)
        // Gleiche Eingaben (auch als neue, gleiche Listen): kein zweites Mal
        assertSame(first, cache.summarize(trades.toList(), prices.toMap(), CostMethod.FIFO))
        assertEquals(1, computed)
        assertEquals(PortfolioCalculator.summarize(trades, prices, CostMethod.FIFO), first)
        // Andere Kurse, Methode, Teile oder Transaktionen: neu gerechnet
        val otherPrices = prices + ("BTC" to (prices.getValue("BTC") * 2))
        assertNotSame(first, cache.summarize(trades, otherPrices, CostMethod.FIFO))
        assertEquals(2, computed)
        cache.summarize(trades, otherPrices, CostMethod.AVERAGE)
        assertEquals(3, computed)
        cache.summarize(trades, otherPrices, CostMethod.AVERAGE, withParts = true)
        assertEquals(4, computed)
        val fewer = cache.summarize(trades.dropLast(1), otherPrices, CostMethod.AVERAGE, withParts = true)
        assertEquals(5, computed)
        assertEquals(PortfolioCalculator.summarize(trades.dropLast(1), otherPrices, CostMethod.AVERAGE), fewer)
        // Nur der letzte Eintrag bleibt: die erste Eingabe rechnet wieder
        cache.summarize(trades, prices, CostMethod.FIFO)
        assertEquals(6, computed)
    }

    /** Referenz der Steuer-Vermerke: je Coin [PortfolioCalculator.ledger] über alle Transaktionen. */
    private fun referenceBooks(data: List<PortfolioTrade>, rules: TaxEventRules, method: CostMethod): List<CoinLedger> {
        val real = data.filter { !AddressBalance.isAddressPart(it.id) && it.amount.signum() > 0 }
        val applied = TaxEngines.applyRules(real, rules, null)
        return PortfolioLedger.coins(applied).map { PortfolioCalculator.ledger(it, applied, null, method) }
    }

    private fun CoinPosition.noParts() = copy(pricedCostParts = emptyList())

    private fun PortfolioSummary.noParts() = copy(open = open.map { it.noParts() }, closed = closed.map { it.noParts() })

    private fun CoinLedger.noParts() =
        copy(position = position.noParts(), disposals = disposals.map { it.copy(costParts = emptyList()) })

    private companion object {
        const val T0 = 1_717_200_000_000L // 1. Juni 2024
        const val DAY = 86_400_000L

        fun trade(
            id: Long,
            coin: String,
            type: PortfolioTxType,
            amount: String,
            price: String?,
            time: Long,
            fee: String? = null,
            unit: PortfolioFeeUnit? = null,
        ) = PortfolioTrade(
            id = id, coin = coin, type = type, amount = BigDecimal(amount), priceUsdt = price?.let { BigDecimal(it) },
            time = time, fee = fee?.let { BigDecimal(it) }, feeUnit = unit,
        )
    }
}
