package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressBooked
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.AddressCutoff
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
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
 * Wallet bei erfassten Umbuchungen von/zu der Adress-Wallet, Stichtag/CH-Vermögen wie die Karte («cutoff», [AddressCutoff]). Gegenstück: `Tests/AddressCostLotsParityTests.swift`.
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
            lastUpdated = o["lastUpdated"].num()?.toLong() ?: 0L,
            observations = o["observations"]?.list()?.map { it.obj() }?.map { AddressObservation(it["t"].num()!!.toLong(), dec(it["b"])!!) }.orEmpty(),
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
            // Reihe ohne das schon Gebuchte ([AddressBooked]): letzte Stufe = Adress-Teil der Karte; «Heute» rechnet mit
            // eigenem Bestand samt Posten + dieser Stufe = Bestand der Karte
            val booked = AddressBalanceHistory.latestByCoin(AddressBooked.histories(addresses, real))
            val expectedBooked = c["booked"].obj()
            assertEquals("$name booked coins", expectedBooked.keys, booked.keys)
            expectedBooked.forEach { (coin, v) -> near("$name booked $coin", v, booked[coin]) }
            c["methods"].obj().forEach { (m, coins) ->
                val method = CostMethod.valueOf(m)
                coins.obj().forEach { (coin, spec) ->
                    val x = spec.obj()
                    val p = PortfolioCalculator.position(coin, all, null, method)
                    near("$name/$m today = card", p.holdingsExact.toPlainString(), p.ledgerHoldingsExact + (booked[coin] ?: BigDecimal.ZERO))
                    near("$name/$m holdings", x["holdings"], p.holdingsExact)
                    near("$name/$m address", x["address"], p.addressHoldingsExact)
                    if ("cost" in x) near("$name/$m cost", x["cost"], p.costBasisExact)
                    if ("realized" in x) near("$name/$m realized", x["realized"], p.realizedExact)
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
            c["cutoff"]?.obj()?.let { x -> checkCutoff(name, x, addresses, real, c["methods"].obj(), all) }
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

    /**
     * Stichtag/CH-Vermögen ([AddressCutoff]): Posten, Adress-Teil ohne Einstand, Hinweis; Bestand je Methode wie erwartet
     * und — Stichtag nach allen Buchungen und Abrufen — je Coin dieselbe Menge wie die Karte (Bestand + Adress-Teil).
     */
    private fun checkCutoff(
        name: String,
        x: Map<String, Any?>,
        addresses: List<WatchedAddress>,
        real: List<PortfolioTrade>,
        methods: Map<String, Any?>,
        all: List<PortfolioTrade>,
    ) {
        val time = x["time"].num()!!.toLong()
        val view = AddressCutoff.at(addresses, real, time)
        assertEquals("$name cutoff lots", x["lots"].num()!!.toInt(), view.lots.size)
        assertEquals("$name cutoff note", x["note"], view.lotsAfterObservation)
        val address = x["address"].obj()
        assertEquals("$name cutoff address coins", address.keys, view.part.balances.keys)
        address.forEach { (coin, v) -> near("$name cutoff address $coin", v, view.part.balances[coin]) }
        val expected = x["holdings"].obj()
        for (m in methods.keys.ifEmpty { setOf("FIFO") }) {
            val method = CostMethod.valueOf(m)
            val held = AddressCutoff.holdingsAt(real, view, time, method).associate { it.coin to it.amount }
            assertEquals("$name/$m cutoff coins", expected.keys, held.keys)
            expected.forEach { (coin, v) -> near("$name/$m cutoff $coin", v, held[coin]) }
            methods[m]?.obj()?.keys?.forEach { coin ->
                val card = PortfolioCalculator.position(coin, all, null, method).holdingsExact
                val file = (held[coin] ?: BigDecimal.ZERO) + (view.part.balances[coin] ?: BigDecimal.ZERO)
                near("$name/$m cutoff = card $coin", card.toPlainString(), file)
            }
        }
    }

    /**
     * 7 T und Wertverlauf mit derselben Bestandsregel wie die Karte ([AddressBooked]): Posten ab ihrer Zeit, Reihe ohne das
     * schon Gebuchte — eine erfasste Umbuchung zur Adresse zählt einmal, ein Verkauf von der Adress-Wallet verbraucht den
     * Posten; der letzte Punkt ist der Wert der Karte.
     */
    @Test
    fun periodsUseTheCardHoldings() {
        val p = data["periods"].obj()
        val today = p["today"].num()!!.toLong()
        val current = p["current"].obj().mapValues { it.value.num()!! }
        val closes = p["closes"].obj().mapValues { (_, days) -> days.obj().entries.associate { it.key.toLong() to it.value.num()!! } }
        for (raw in p["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val method = CostMethod.valueOf(c["method"] as String)
            val addresses = c["addresses"].list().map(::address)
            val real = c["trades"].list().map(::trade)
            val all = AddressBalance.withAddresses(real, addresses, today)
            val histories = AddressBooked.histories(addresses, real)
            val e = c["expected"].obj()
            val entries = AddressBalanceHistory.trades(histories)
            val expectedEntries = e["addressTrades"].list().map { it.obj() }
            assertEquals("$name entries", expectedEntries.size, entries.size)
            expectedEntries.zip(entries).forEach { (x, t) ->
                assertEquals("$name entry time", x["time"].num()!!.toLong(), t.time)
                assertEquals("$name entry type", x["type"], t.type.name)
                near("$name entry amount", x["amount"], t.amount)
            }
            near("$name card", e["holdings"], PortfolioCalculator.position("BTC", all, null, method).holdingsExact)
            val gain = PortfolioPeriodChange.gain(all, current, closes, today - PortfolioPeriodChange.DAYS, histories, method)
            nearDouble("$name gain", e["gain"].num(), gain?.amountUsd)
            nearDouble("$name percent", e["percent"].num(), gain?.percent)
            val history = PortfolioHistory.build(all, closes, current, PortfolioHistoryRange.WEEK, today, addresses = histories, method = method)
            nearDouble("$name first point", e["firstPoint"].num(), history.points.firstOrNull()?.value)
            nearDouble("$name last point", e["lastPoint"].num(), history.points.lastOrNull()?.value)
            nearDouble("$name history pnl", e["gain"].num(), history.pnlChange)
        }
    }

    private fun nearDouble(label: String, expected: Double?, actual: Double?) {
        assertTrue("$label: $actual ≠ $expected", expected != null && actual != null && kotlin.math.abs(expected - actual) < 1e-6)
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
