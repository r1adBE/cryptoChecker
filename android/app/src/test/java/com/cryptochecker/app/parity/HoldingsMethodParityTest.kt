package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioLedger
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Bestand je Einstandsmethode überall gleich (`testdata/parity/holdings_method.json`): Karte, Stichtag, «7 T» und
 * Wertverlauf. Gegenstück: `Tests/HoldingsMethodParityTests.swift`.
 */
class HoldingsMethodParityTest {

    private val data = ParityJson.load("holdings_method.json")

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(),
            coin = o["coin"] as String,
            type = PortfolioTxType.valueOf(o["type"] as String),
            amount = BigDecimal(o["amount"] as String),
            priceUsdt = (o["price"] as String?)?.let(::BigDecimal),
            time = o["time"].num()!!.toLong(),
            fee = (o["fee"] as String?)?.let(::BigDecimal),
            feeUnit = (o["feeUnit"] as String?)?.let { PortfolioFeeUnit.valueOf(it) },
            feeCoin = o["feeCoin"] as String?,
            feeCoinAmount = (o["feeCoinAmount"] as String?)?.let(::BigDecimal),
            feeCoinPrice = (o["feeCoinPrice"] as String?)?.let(::BigDecimal),
            exchange = o["exchange"] as String?,
            transferFrom = o["transferFrom"] as String?,
            transferTo = o["transferTo"] as String?,
            toCoin = o["toCoin"] as String?,
            toAmount = (o["toAmount"] as String?)?.let(::BigDecimal),
            swapValueUsdt = (o["swapValue"] as String?)?.let(::BigDecimal),
            // Build 47: Gebühr-Zeile nur mit Import-Herkunft
            imported = o["importKey"] != null,
        )
    }

    /** Karte: eigener Bestand je offenem Coin nach [method]. */
    private fun card(trades: List<PortfolioTrade>, method: CostMethod): Map<String, BigDecimal> =
        PortfolioLedger.coins(trades).associateWith { PortfolioCalculator.position(it, trades, null, method).ledgerHoldingsExact }
            .filterValues { it.signum() > 0 }

    private fun assertHoldings(label: String, expected: Map<String, Any?>, actual: Map<String, BigDecimal>) {
        assertEquals("$label coins", expected.keys.sorted(), actual.keys.sorted())
        for ((coin, text) in expected) {
            assertEquals("$label $coin", 0, BigDecimal(text as String).compareTo(actual.getValue(coin)))
        }
    }

    /** Exakte Beträge bis auf Rundung der 34. Stelle (Swift rechnet mit anderer Genauigkeit). */
    private fun assertNear(label: String, expected: Any?, actual: BigDecimal) {
        val diff = (BigDecimal(expected as String) - actual).abs()
        assertEquals("$label $actual", true, diff < BigDecimal("1e-20"))
    }

    @Test
    fun sameHoldingsEverywhere() {
        for (raw in data["cases"].list()) {
            val c = raw.obj()
            val name = c["name"] as String
            val trades = c["trades"].list().map(::trade)
            c["methods"]?.obj()?.forEach { (m, spec) ->
                val method = CostMethod.valueOf(m)
                val s = spec.obj()
                val label = "$name/$m"
                val expected = s["holdings"].obj()
                assertHoldings("$label card", expected, card(trades, method))
                assertHoldings("$label holdingsAt", expected, PortfolioCalculator.holdingsAt(trades, method = method))
                assertHoldings(
                    "$label cutoff", expected,
                    CutoffExport.holdingsAt(trades, Long.MAX_VALUE, method).associate { it.coin to it.amount },
                )
                val today = c["today"].num()!!.toLong()
                val closes = c["closes"].obj().mapValues { (_, v) -> v.obj().entries.associate { it.key.toLong() to it.value.num()!! } }
                val live = c["live"].obj().mapValues { it.value.num()!! }
                val gain = PortfolioPeriodChange.gain(trades, live, closes, today - PortfolioPeriodChange.DAYS, method = method)
                assertNotNull("$label gain", gain)
                assertEquals("$label weekGain", s["weekGain"].num()!!, gain!!.amountUsd, 1e-9)
                val series = PortfolioHistory.build(trades, closes, live, PortfolioHistoryRange.WEEK, today, method = method)
                assertEquals("$label historyLast", s["historyLast"].num()!!, series.points.last().value, 1e-9)
                assertEquals("$label historyPnl", s["historyPnl"].num()!!, series.pnlChange!!, 1e-9)
                // Gebühr-Zeilen zum Tagesschluss (gleicher Kurs wie der Abfluss im Verlauf)
                val feePrice: (String, Long) -> BigDecimal? = { coin, time ->
                    PortfolioPeriodChange.marketPriceAt(coin, live[coin], closes, time)?.let { PortfolioDecimal.of(it) }
                }
                val valued = PortfolioLedger.valueFeeDisposals(trades, feePrice)
                // Steuer-Export und «realisiert» mit derselben Aufteilung des Erlöses wie «7 T»/Wertverlauf
                s["disposals"]?.obj()?.forEach { (coin, d) ->
                    val x = d.obj()
                    val list = PortfolioCalculator.disposals(valued, method).filter { it.coin == coin }
                    val position = PortfolioCalculator.ledger(coin, valued, null, method).position
                    // Karte bewertet Gebühr-Zeilen selbst (withdrawalPrice) — gleiche Zahl
                    val card = PortfolioCalculator.summarize(trades, live, method) { c2, t2 -> PortfolioPeriodChange.marketPriceAt(c2, live[c2], closes, t2) }
                    val cardRealized = (card.open + card.closed).first { it.coin == coin }.realizedExact
                    assertNear("$label $coin card realized", x["realized"], cardRealized)
                    // Coin-Ansicht bewertet Gebühr-Zeilen ebenso (ledger feePrice) — gleiche Zahl
                    val detail = PortfolioCalculator.ledger(coin, trades, live[coin], method) { c2, t2 ->
                        PortfolioPeriodChange.marketPriceAt(c2, live[c2], closes, t2)
                    }
                    assertNear("$label $coin detail realized", x["realized"], detail.position.realizedExact)
                    assertNear("$label $coin proceeds", x["proceeds"], list.filter { !it.uncovered }.sumOf { it.proceedsUsdt!! })
                    assertNear("$label $coin uncovered", x["uncoveredProceeds"], list.filter { it.uncovered }.sumOf { it.proceedsUsdt!! })
                    assertNear("$label $coin realized", x["realized"], position.realizedExact)
                    assertEquals("$label $coin incomplete", x["incomplete"] as Boolean, position.realizedIncomplete)
                }
                // Gebühr als Veräusserung ohne Erlös: Vermerk «Gebühr» im Steuer-Export
                s["feeRows"]?.num()?.let { n ->
                    val year = java.time.Instant.ofEpochMilli(trades.maxOf { it.time }).atZone(java.time.ZoneOffset.UTC).year
                    val rows = TaxExport.gainRows(
                        trades, TaxMethod.of(method), TaxTemplate.NEUTRAL.period(year), java.time.ZoneOffset.UTC, "USD", null, feePrice,
                    )
                    assertEquals("$label feeRows", n.toInt(), rows.count { it.feeRow })
                }
            }
            c["cutoffs"]?.list()?.forEach { cut ->
                val o = cut.obj()
                val time = o["cutoff"].num()?.toLong() ?: Long.MAX_VALUE
                o["methods"].obj().forEach { (m, expected) ->
                    val method = CostMethod.valueOf(m)
                    val label = "$name/$m@$time"
                    val until = trades.filter { it.time <= time }
                    assertHoldings("$label card", expected.obj(), card(until, method))
                    assertHoldings(
                        "$label cutoff", expected.obj(),
                        CutoffExport.holdingsAt(trades, time, method).associate { it.coin to it.amount },
                    )
                }
            }
        }
    }

    @Test
    fun realizedIncompleteFromDisposals() {
        for (raw in data["realized"].list()) {
            val c = raw.obj()
            val trades = c["trades"].list().map(::trade)
            c["methods"].obj().forEach { (m, spec) ->
                val method = CostMethod.valueOf(m)
                val s = spec.obj()
                val label = "${c["name"]}/$m"
                val ledger = PortfolioCalculator.ledger(c["coin"] as String, trades, null, method)
                assertEquals("$label incomplete", s["incomplete"] as Boolean, ledger.position.realizedIncomplete)
                // Gleiche Daten wie der Steuer-Export («unvollständig» bei fehlendem Einstand/Erlös)
                val taxIncomplete = PortfolioCalculator.disposals(trades, method).any { it.costUsdt == null || it.proceedsUsdt == null }
                assertEquals("$label tax", taxIncomplete, ledger.position.realizedIncomplete)
                assertEquals("$label realized", 0, BigDecimal(s["realized"] as String).compareTo(ledger.position.realizedExact))
            }
        }
    }
}
