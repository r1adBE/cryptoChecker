package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.performance.PerformanceTiming.TRANSACTIONS
import com.cryptochecker.app.performance.PortfolioPerformanceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Herkunftsteile der FIFO-Posten erst beim Auslesen ([PortfolioFifoParts]): Die Gebühr USDT einer Umbuchung steht
 * einmal im Gebühren-Strom der Warteschlange; Veräusserungen und offener Einstand lesen ihre Teile daraus. Gleich wie
 * iOS (`PortfolioFifoPartsTests`):
 *  - jede Veräusserung: Summe der Teile = Einstand (exakt), kein Teil nach dem Abgang;
 *  - offene Posten: Summe der Teile = Einstand mit Kaufpreis (exakt);
 *  - Umbuchung auf zwei Posten mit Kaufpreis und einen ohne: Anteil nach Menge, der Anteil ohne Kaufpreis vermerkt.
 */
class PortfolioFifoPartsTest {

    private val trades = PortfolioPerformanceData.trades(TRANSACTIONS.small)

    private fun sum(parts: List<PortfolioCostPart>): BigDecimal = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.usdt }

    @Test
    fun partsAddUpToCostOnLargePortfolio() {
        for (method in listOf(CostMethod.FIFO, CostMethod.FIFO_WALLET)) {
            val books = PortfolioCalculator.ledgers(trades, method)
            var checked = 0
            for (book in books) {
                for (d in book.disposals) {
                    val cost = d.costUsdt ?: continue
                    if (d.costParts.isEmpty()) continue
                    assertEquals("${method.name} ${d.coin} ${d.sellTxId}", 0, cost.compareTo(sum(d.costParts)))
                    assertTrue(d.costParts.all { it.time <= d.sellTime })
                    checked++
                }
                val open = book.position.pricedCostParts
                val cost = book.position.pricedCostExact
                if (open.isNotEmpty() && cost != null) assertEquals("${method.name} ${book.position.coin} open", 0, cost.compareTo(sum(open)))
            }
            assertTrue("${method.name}: Veräusserungen mit Teilen geprüft", checked > 100)
        }
    }

    @Test
    fun transferFeeSharedByAmountAcrossQueue() {
        val t0 = 1_700_000_000_000L
        val day = 86_400_000L
        val list = listOf(
            trade(1, "BUY", "1", "100", t0),
            trade(2, "DEPOSIT", "2", null, t0 + day),
            trade(3, "BUY", "1", "200", t0 + 2 * day),
            // Gebühr 8 USDT auf 4 Coins: je Coin 2 — Posten 1 und 3 je 2, Posten 2 (ohne Kaufpreis) 4 vermerkt
            trade(4, "TRANSFER", "1", null, t0 + 3 * day, fee = "8"),
            trade(5, "SELL", "1", "500", t0 + 4 * day),
        )
        val book = PortfolioCalculator.ledgers(list, CostMethod.FIFO).single()
        val sale = book.disposals.single()
        assertEquals(0, BigDecimal("102").compareTo(sale.costUsdt))
        assertEquals(listOf(t0, t0 + 3 * day), sale.costParts.map { it.time })
        assertEquals(0, BigDecimal("100").compareTo(sale.costParts[0].usdt))
        assertEquals(0, BigDecimal("2").compareTo(sale.costParts[1].usdt))
        assertEquals(0, BigDecimal("4").compareTo(book.unassignedFees.single().usdt))
        // Offen: Posten 3 mit 200 + 2 (und Posten 2 ohne Kaufpreis)
        assertEquals(0, BigDecimal("202").compareTo(book.position.pricedCostExact))
        assertEquals(listOf(t0 + 2 * day, t0 + 3 * day), book.position.pricedCostParts.map { it.time })
    }

    private fun trade(id: Long, type: String, amount: String, price: String?, time: Long, fee: String? = null) = PortfolioTrade(
        id = id, coin = "BTC", type = PortfolioTxType.valueOf(type), amount = BigDecimal(amount),
        priceUsdt = price?.let(::BigDecimal), time = time,
        fee = fee?.let(::BigDecimal), feeUnit = if (fee != null) PortfolioFeeUnit.QUOTE else null,
    )
}
