package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Einstand für Adressguthaben und Einträge ohne Einstand (`testdata/parity/address_cost_lots.json`): Abdeckung der Posten,
 * Adress-Teil ohne Einstand, Rechner-Einträge, Karte je Einstandsmethode, Stichtag-Bestand (CH-Vermögenswert ohne
 * Posten), FR-Erwerbspreis-Summe samt Vermerk «Adressen ohne Einstand», Steuer-Export (Info-Zeilen, Jahre), Bestand je
 * Wallet bei erfassten Umbuchungen von/zu der Adress-Wallet. Gegenstück: `Tests/AddressCostLotsParityTests.swift`.
 */
class AddressCostLotsParityTest {

    private val data = ParityJson.load("address_cost_lots.json")

    private fun dec(raw: Any?): BigDecimal? = (raw as String?)?.let(::BigDecimal)

    private fun lot(raw: Any?): AddressCostLot {
        val o = raw.obj()
        return AddressCostLot(
            id = o["id"].num()!!.toLong(), coin = o["coin"] as String, time = o["t"].num()!!.toLong(),
            amount = dec(o["amount"])!!, priceUsdt = dec(o["price"]), costUsdt = dec(o["cost"]),
        )
    }

    private fun address(raw: Any?): WatchedAddress {
        val o = raw.obj()
        return WatchedAddress(
            id = o["id"].num()!!.toLong(), chain = AddressChain.valueOf(o["chain"] as String), address = o["address"] as String,
            label = o["label"] as String?, lastBalance = o["lastBalance"] as String?,
            countInPortfolio = o["countInPortfolio"] as Boolean? ?: true,
            costLots = o["costLots"]?.list()?.map(::lot).orEmpty(),
        )
    }

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(), coin = o["coin"] as String, type = PortfolioTxType.valueOf(o["type"] as String),
            amount = dec(o["amount"])!!, priceUsdt = dec(o["price"]), time = o["time"].num()!!.toLong(),
            exchange = o["exchange"] as String?, interestIncome = o["interest"] as Boolean? ?: false,
            fee = dec(o["fee"]), feeUnit = (o["feeUnit"] as String?)?.let(PortfolioFeeUnit::valueOf),
            transferFrom = o["transferFrom"] as String?, transferTo = o["transferTo"] as String?,
        )
    }

    private fun near(label: String, expected: Any?, actual: BigDecimal?) {
        if (expected == null) {
            assertNull(label, actual)
            return
        }
        val diff = (BigDecimal(expected as String) - actual!!).abs()
        assertTrue("$label: $actual ≠ $expected", diff < BigDecimal("1e-20"))
    }

    @Test
    fun lotsFeedTheCalculators() {
        val today = data["today"].num()!!.toLong()
        for (raw in data["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val addresses = c["addresses"].list().map(::address)
            val real = c["trades"].list().map(::trade)
            for (cov in c["coverage"].list()) {
                val x = cov.obj()
                val a = addresses.first { it.id == x["address"].num()!!.toLong() }
                val got = AddressCostLots.coverage(a, real, addresses).first { it.coin == x["coin"] }
                near("$name lotted", x["lotted"], got.lotted)
                near("$name excess", x["excess"], got.excess)
                near("$name unlotted", x["unlotted"], got.unlotted)
            }
            val all = AddressBalance.withAddresses(real, addresses, today)
            val (_, part) = AddressBalance.split(all)
            val expectedPart = c["addressPart"].obj()
            assertEquals("$name address coins", expectedPart.keys, part.keys)
            expectedPart.forEach { (coin, v) -> near("$name address $coin", v, part[coin]) }
            val lots = all.filter { AddressCostLots.isLotTrade(it.id) }
            val expectedLots = c["lotTrades"].list().map { it.obj() }
            assertEquals("$name lot trades", expectedLots.size, lots.size)
            expectedLots.zip(lots).forEach { (x, t) ->
                assertEquals("$name lot id", x["id"].num()!!.toLong(), t.id)
                assertEquals("$name lot type", PortfolioTxType.BUY, t.type)
                assertEquals("$name lot time", x["time"].num()!!.toLong(), t.time)
                assertEquals("$name lot wallet", x["exchange"], t.exchange)
                near("$name lot amount", x["amount"], t.amount)
                near("$name lot price", x["price"], t.priceUsdt)
            }
            // Stichtag/CH-Vermögenswert: weder Posten noch Adress-Teil (die Adresse zählt dort über ihre Beobachtungen)
            val held = PortfolioCalculator.holdingsAt(all)
            val expectedHeld = c["holdingsAt"].obj()
            assertEquals("$name holdingsAt coins", expectedHeld.keys, held.keys)
            expectedHeld.forEach { (coin, v) -> near("$name holdingsAt $coin", v, held[coin]) }
            c["methods"].obj().forEach { (m, coins) ->
                val method = CostMethod.valueOf(m)
                coins.obj().forEach { (coin, spec) ->
                    val x = spec.obj()
                    val p = PortfolioCalculator.position(coin, all, null, method)
                    near("$name/$m holdings", x["holdings"], p.holdingsExact)
                    near("$name/$m address", x["address"], p.addressHoldingsExact)
                    near("$name/$m cost", x["cost"], p.costBasisExact)
                    near("$name/$m realized", x["realized"], p.realizedExact)
                }
            }
            // FIFO je Wallet: Bestand je Wallet (Adress-Wallet samt erfasster Umbuchungen), kein Fehlbestand
            c["wallets"]?.obj()?.forEach { (coin, wallets) ->
                val got = PortfolioCalculator.openLots(coin, all, CostMethod.FIFO_WALLET)
                    .groupBy { it.wallet }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { s, x -> s + x.amount } }
                val expected = wallets.obj()
                assertEquals("$name wallets $coin", expected.keys, got.keys)
                expected.forEach { (w, v) -> near("$name wallet $w", v, got[w]) }
            }
            c["shortfalls"]?.let { assertEquals("$name shortfalls", it.num()!!.toInt(), PortfolioCalculator.walletShortfalls(all).size) }
            c["fr"]?.obj()?.let { fr ->
                val price = BigDecimal(fr["price"] as String)
                val rows = TaxEngines.disposals(all, TaxMethod.FR_PTA, ZoneOffset.UTC, { _, _ -> price })
                val d = rows.single()
                near("$name fr cost", fr["cost"], d.costUsdt)
                assertEquals("$name fr address note", fr["addressValueIncluded"] as Boolean, d.addressValueIncluded)
            }
        }
    }

    /** Steuer-Export rechnet mit den Posten wie die Veräusserungen: keine Gebühr «ohne Kaufpreis», Jahr nur mit Posten. */
    @Test
    fun taxExportSeesLots() {
        val c = data["taxExport"].obj()
        val zone = ZoneOffset.UTC
        val real = c["trades"].list().map(::trade)
        val all = AddressBalance.withAddresses(real, c["addresses"].list().map(::address), data["today"].num()!!.toLong())
        val ints = { key: String -> c[key].list().map { it.num()!!.toInt() } }
        assertEquals("years", ints("years"), TaxExport.years(all, zone))
        assertEquals("years without addresses", ints("yearsWithoutAddresses"), TaxExport.years(real, zone))
        val year = c["infoYear"].num()!!.toInt()
        for (m in c["methods"].list()) {
            val method = CostMethod.valueOf(m as String)
            val unassigned = TaxExport.unassignedFeesIn(all, method, year, zone)
            assertEquals("$m unassigned", c["unassignedFees"].num()!!.toInt(), unassigned.size)
            val lost = TaxExport.lostFeeCostsIn(all, method, year, zone)
            assertEquals("$m lost fee cost", c["lostFeeCosts"].num()!!.toInt(), lost.size)
        }
    }

    @Test
    fun gapsAndSuggestions() {
        val g = data["gaps"].obj()
        val trades = g["trades"].list().map(::trade)
        g["expected"].obj().forEach { (template, list) ->
            val got = PortfolioCostGaps.gaps(trades, TaxTemplate.valueOf(template))
            assertEquals("$template ids", list.list().map { it.obj()["id"].num()!!.toLong() }, got.map { it.trade.id })
            assertEquals("$template kinds", list.list().map { it.obj()["kind"] }, got.map { it.kind.name })
        }
        val gaps = PortfolioCostGaps.gaps(trades, TaxTemplate.CH)
        val acknowledged = g["acknowledged"].list().map { it.num()!!.toLong() }.toSet()
        assertEquals(g["pending"].list().map { it.num()!!.toLong() }, PortfolioCostGaps.pending(gaps, acknowledged).map { it.trade.id })
        assertEquals(g["pruned"].list().map { it.num()!!.toLong() }.toSet(), PortfolioCostGaps.prune(acknowledged, gaps))
        for (s in g["suggestions"].list()) {
            val x = s.obj()
            val amount = BigDecimal(x["amount"] as String)
            val close = dec(x["close"])
            near("lot suggestion", x["expected"], AddressCostLots.suggestion(amount, close))
            val t = PortfolioTrade(1, "BTC", PortfolioTxType.DEPOSIT, amount, null, 1L)
            near("gap suggestion", x["expected"], PortfolioCostGaps.suggestion(t, close))
        }
        for (s in g["priceFromTotal"].list()) {
            val x = s.obj()
            near("priceFromTotal", x["expected"], PortfolioCostGaps.priceFromTotal(dec(x["total"]), BigDecimal(x["amount"] as String)))
        }
    }
}
