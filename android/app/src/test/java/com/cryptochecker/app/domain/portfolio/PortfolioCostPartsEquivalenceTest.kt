package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.performance.PortfolioPerformanceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import kotlin.random.Random

/**
 * Durchschnittsmethode ([average]) und Steuer-Pool ([TaxPool]) mit [PortfolioCostPartPool] gegen die frühere Rechnung
 * ([averageReference], [TaxPoolReference]: Liste der Herkunftsteile, Teil für Teil neu geschrieben) auf zufälligen
 * Ledgern mit Seed (Käufe mit Gebühren in USDT/im Coin, Teilverkäufe, Tausch, Belohnungen ohne Preis, Umbuchungen mit
 * Netzwerkgebühr, Auszahlungen) und den Leistungstest-Daten: alle Zahlen gleich, Herkunftsteile gleich bis auf die
 * Rundung der 34. Stelle, ihre Summen exakt. iOS: PortfolioCostPartsEquivalenceTests.swift.
 */
class PortfolioCostPartsEquivalenceTest {

    private fun same(label: String, expected: BigDecimal, actual: BigDecimal) =
        assertEquals("$label: $expected / $actual", 0, expected.compareTo(actual))

    private fun assertParts(label: String, expected: List<PortfolioCostPart>, actual: List<PortfolioCostPart>) {
        assertEquals("$label Anzahl", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals("$label Teil $i Zeitpunkt", e.time, a.time)
            assertTrue("$label Teil $i ${e.usdt} / ${a.usdt}", (e.usdt - a.usdt).abs() <= TOLERANCE)
        }
        if (expected.isNotEmpty()) same("$label Summe", expected.sumOf { it.usdt }, actual.sumOf { it.usdt })
    }

    private fun compareAverage(label: String, trades: List<PortfolioTrade>) {
        for ((coin, events) in PortfolioLedger.eventsByCoin(trades)) {
            val l = "$label $coin"
            val expected = PortfolioCalculator.averageReference(coin, events)
            val actual = PortfolioCalculator.average(coin, events)
            same("$l priced", expected.priced, actual.priced)
            same("$l unpriced", expected.unpriced, actual.unpriced)
            same("$l cost", expected.cost, actual.cost)
            same("$l realized", expected.realized, actual.realized)
            same("$l unknownSold", expected.unknownSold, actual.unknownSold)
            same("$l soldTotal", expected.soldTotal, actual.soldTotal)
            same("$l withdrawn", expected.withdrawn, actual.withdrawn)
            assertEquals("$l oversold", expected.oversold, actual.oversold)
            assertEquals("$l feeValueMissing", expected.feeValueMissing, actual.feeValueMissing)
            assertEquals("$l realizedFeeMissing", expected.realizedFeeMissing, actual.realizedFeeMissing)
            assertEquals("$l withdrawals", expected.withdrawals, actual.withdrawals)
            assertEquals("$l lostFeeCosts", expected.lostFeeCosts, actual.lostFeeCosts)
            assertEquals("$l unassignedFees", expected.unassignedFees, actual.unassignedFees)
            assertEquals("$l disposals", expected.disposals.size, actual.disposals.size)
            expected.disposals.zip(actual.disposals).forEachIndexed { i, (e, a) ->
                assertEquals("$l Veräusserung $i", e.copy(costParts = emptyList()), a.copy(costParts = emptyList()))
                assertParts("$l Veräusserung $i", e.costParts, a.costParts)
            }
            assertParts("$l offen", expected.openParts, actual.openParts)
            if (actual.openParts.isNotEmpty()) same("$l Summe offen", actual.cost, actual.openParts.sumOf { it.usdt })
        }
    }

    @Test
    fun averageMatchesFormerCalculationOnRandomLedgers() {
        for (seed in 1..30) compareAverage("Seed $seed", randomLedger(seed, 400))
    }

    @Test
    fun averageMatchesFormerCalculationOnPerformanceData() {
        compareAverage("Leistungsdaten", PortfolioPerformanceData.trades(2_500))
    }

    @Test
    fun taxPoolMatchesFormerPool() {
        for (seed in 1..30) {
            val rnd = Random(seed)
            val zone = if (seed % 3 == 0) null else ZoneId.of("UTC")
            val pool = TaxPool(zone)
            val former = TaxPoolReference(zone)
            var time = 1_650_000_000_000L
            var carried: Pair<TaxTaken, TaxTaken>? = null
            repeat(500) { step ->
                val l = "Seed $seed Schritt $step"
                time += rnd.nextLong(HOUR, 2 * DAY)
                val qty = amount(rnd, 6)
                when (rnd.nextInt(10)) {
                    0, 1, 2 -> {
                        val cost = if (rnd.nextInt(6) == 0) null else amount(rnd, 4)
                        pool.add(qty, cost, time)
                        former.add(qty, cost, time)
                    }
                    3, 4, 5 -> {
                        val expected = former.take(qty.min(former.held.multiply(BigDecimal("0.6"))))
                        val actual = pool.take(qty.min(pool.held.multiply(BigDecimal("0.6"))))
                        same("$l removed", expected.removed, actual.removed)
                        same("$l removedPriced", expected.removedPriced, actual.removedPriced)
                        same("$l cost", expected.cost, actual.cost)
                        assertParts(l, expected.parts, actual.parts)
                        carried = expected to actual
                    }
                    6 -> carried?.let { (e, a) ->
                        // Fortgeführter Einstand mit Herkunft (Umbuchung, UK-Tageszugang) — Teile aus einem früheren Abgang
                        former.addCarried(e.removedPriced, e.parts)
                        pool.addCarried(a.removedPriced, a.parts)
                    }
                    7 -> same("$l Netzwerkgebühr", former.dropQty(qty.min(former.held)), pool.dropQty(qty.min(pool.held)))
                    8 -> {
                        val fee = amount(rnd, 2)
                        val expected = former.addFee(time, fee, qty, BigDecimal.ZERO)
                        same("$l Gebühr", expected, pool.addFee(time, fee, qty, BigDecimal.ZERO))
                    }
                    else -> {
                        // Tages-Pool übernehmen (UK): Zugänge eines früheren Tags
                        val day = time - rnd.nextLong(0, 10 * DAY)
                        val otherFormer = TaxPoolReference()
                        val other = TaxPool()
                        repeat(1 + rnd.nextInt(3)) {
                            val q = amount(rnd, 6)
                            val c = amount(rnd, 4)
                            otherFormer.add(q, c, day)
                            other.add(q, c, day)
                        }
                        former.absorb(otherFormer)
                        pool.absorb(other)
                    }
                }
                same("$l priced", former.priced, pool.priced)
                same("$l unpriced", former.unpriced, pool.unpriced)
                same("$l Einstand", former.cost, pool.cost)
                if (step % 50 == 0) assertParts("$l offen", former.parts, pool.parts)
            }
            assertParts("Seed $seed offen", former.parts, pool.parts)
        }
    }

    /** Menge/Betrag mit bis zu [scale] Nachkommastellen (> 0). */
    private fun amount(rnd: Random, scale: Int): BigDecimal = BigDecimal.valueOf(1L + rnd.nextLong(10_000_000L), scale)

    /** Zufälliges Ledger mit Seed: zwei Coins, alle Abgangsarten, Gebühren in USDT und im Coin. */
    private fun randomLedger(seed: Int, count: Int): List<PortfolioTrade> {
        val rnd = Random(seed)
        val out = ArrayList<PortfolioTrade>(count)
        var time = 1_600_000_000_000L
        for (i in 1..count) {
            time += rnd.nextLong(HOUR, 3 * DAY)
            val coin = if (rnd.nextInt(3) == 0) "ETH" else "BTC"
            val price = amount(rnd, 2)
            val qty = amount(rnd, 8)
            val id = i.toLong()
            out += when (rnd.nextInt(20)) {
                in 0..6 -> PortfolioTrade(id, coin, PortfolioTxType.BUY, qty, price, time)
                    .let { if (rnd.nextBoolean()) it.copy(fee = amount(rnd, 2), feeUnit = PortfolioFeeUnit.QUOTE) else it }
                7 -> PortfolioTrade(id, coin, PortfolioTxType.BUY, qty, price, time,
                    fee = qty.divide(BigDecimal(1_000)), feeUnit = PortfolioFeeUnit.COIN)
                8 -> PortfolioTrade(id, coin, PortfolioTxType.REWARD, qty, price.takeIf { rnd.nextBoolean() }, time)
                in 9..12 -> PortfolioTrade(id, coin, PortfolioTxType.SELL, qty.divide(BigDecimal(3), 8, RoundingMode.DOWN),
                    price, time, fee = amount(rnd, 2), feeUnit = PortfolioFeeUnit.QUOTE)
                13 -> PortfolioTrade(id, coin, PortfolioTxType.SWAP, qty.divide(BigDecimal(4)), null, time,
                    toCoin = if (coin == "BTC") "ETH" else "BTC", toAmount = qty, swapValueUsdt = price)
                14 -> PortfolioTrade(id, coin, PortfolioTxType.WITHDRAWAL, qty.divide(BigDecimal(5)), price, time,
                    fee = BigDecimal.ONE, feeUnit = PortfolioFeeUnit.QUOTE)
                15 -> PortfolioTrade(id, coin, PortfolioTxType.TRANSFER, qty.divide(BigDecimal(2)), null, time,
                    fee = qty.divide(BigDecimal(2_000)), feeUnit = PortfolioFeeUnit.COIN,
                    transferFrom = "A", transferTo = "B")
                16 -> PortfolioTrade(id, coin, PortfolioTxType.TRANSFER, qty, null, time,
                    fee = BigDecimal.ONE, feeUnit = PortfolioFeeUnit.QUOTE, transferFrom = "B", transferTo = "A")
                17 -> PortfolioTrade(id, coin, PortfolioTxType.DEPOSIT, qty, price.takeIf { rnd.nextBoolean() }, time)
                18 -> PortfolioTrade(id, coin, PortfolioTxType.PAYMENT, qty.divide(BigDecimal(10)), price, time)
                else -> PortfolioTrade(id, coin, PortfolioTxType.SELL, qty.multiply(BigDecimal(5)), price, time)
            }
        }
        return out
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** Teile weichen nur in der Rundung ab (gemessen ≈ 10⁻²⁶ bei Beträgen bis 10⁸ USDT). */
        val TOLERANCE = BigDecimal("1E-18")
    }
}
