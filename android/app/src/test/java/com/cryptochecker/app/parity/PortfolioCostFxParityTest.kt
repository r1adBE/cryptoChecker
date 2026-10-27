package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostFx
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Gemeinsame Testfälle `cost_fx.json` (iOS: `PortfolioCostFxTests`): «Seit Kauf» und Einstand der Gesamtkarte in
 * der Anzeigewährung zum Kurs der Erwerbstage ([PortfolioCostFx]) — FIFO, FIFO je Wallet und Ø, fehlender Devisenkurs,
 * Kurs bis 7 Tage davor, Kauf heute.
 */
class PortfolioCostFxParityTest {

    private val data = ParityJson.load("cost_fx.json")
    private val currency = data["currency"] as String
    private val today = data["todayEpochDay"].num()!!.toLong()
    private val currentRate = BigDecimal(data["currentRate"] as String)

    private fun decMap(raw: Any?): Map<Long, BigDecimal> =
        raw.obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }

    private fun trades(raw: Any?): List<PortfolioTrade> = raw.list().map { it.obj() }.map { t ->
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = PortfolioTxType.fromName(t["type"] as String)!!,
            amount = BigDecimal(t["amount"] as String),
            priceUsdt = (t["price"] as String?)?.let { BigDecimal(it) },
            time = t["time"].num()!!.toLong(),
        )
    }

    private fun exact(label: String, expected: Any?, actual: BigDecimal?) {
        if (expected == null) assertNull(label, actual)
        else assertEquals("$label $actual", 0, BigDecimal(expected as String).compareTo(actual!!))
    }

    @Test
    fun cases() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 3)
        cases.forEach { c ->
            val name = c["name"] as String
            val prices = c["prices"].obj().mapValues { it.value.num()!! }
            val summary = PortfolioCalculator.summarize(trades(c["trades"]), prices, CostMethod.fromName(c["method"] as String))
            val e = c["expected"].obj()
            assertEquals("$name firstDay", e["firstDay"].num()?.toLong(), PortfolioCostFx.firstDay(summary))
            val f = PortfolioCostFx.figures(summary, currency, currentRate, today, decMap(c["rates"]), decMap(c["usdtUsd"]))
            exact("$name unrealized", e["unrealized"], f.unrealized)
            exact("$name invested", e["invested"], f.invested)
            val percent = e["percent"].num()
            if (percent == null) assertNull("$name percent", f.unrealizedPercent)
            else assertEquals("$name percent", percent, f.unrealizedPercent!!, 1e-9)
            assertEquals("$name unrealizedFxMissing", e["unrealizedFxMissing"], f.unrealizedFxMissing)
            assertEquals("$name investedFxMissing", e["investedFxMissing"], f.investedFxMissing)
            assertEquals("$name fxMissingCoins", e["fxMissingCoins"].list(), f.fxMissingCoins)
            assertEquals("$name usdtApproximate", e["usdtApproximate"], f.usdtApproximate)
        }
    }

    /** Teile des Einstands gehen exakt auf den Einstand des Teils mit Kaufpreis auf (alle Methoden). */
    @Test
    fun partsSumToPricedCost() {
        data["cases"].list().map { it.obj() }.forEach { c ->
            val prices = c["prices"].obj().mapValues { it.value.num()!! }
            val summary = PortfolioCalculator.summarize(trades(c["trades"]), prices, CostMethod.fromName(c["method"] as String))
            summary.open.forEach { p ->
                val cost = p.pricedCostExact ?: return@forEach
                val sum = p.pricedCostParts.fold(BigDecimal.ZERO) { a, part -> a + part.usdt }
                assertEquals("${c["name"]} ${p.coin} parts", 0, cost.compareTo(sum))
            }
        }
    }
}
