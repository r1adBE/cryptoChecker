package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.AddressCutoff
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCloses
import com.cryptochecker.app.domain.portfolio.CutoffCsvTexts
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.chWealth
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stichtag-CSV und CH-Vermögenswert mit beobachteten Adressen wie die Karte ([AddressCutoff]): `cutoff_export.json`
 * «addressCases» (ganze Datei ab dem Kommentar) und `tax_ch_wealth.json` «watchedCases» (Werte je Coin, Vermerk an der
 * Summe, Hinweiszeile der Jahresübersicht). Gegenstück: `Tests/AddressCutoffParityTests.swift`.
 */
class AddressCutoffParityTest {

    private fun dec(raw: Any?): BigDecimal? = (raw as String?)?.let(::BigDecimal)

    private fun r8(v: BigDecimal?): String? = v?.setScale(8, RoundingMode.HALF_UP)?.toPlainString()

    private fun address(raw: Any?): WatchedAddress {
        val o = raw.obj()
        return WatchedAddress(
            id = o["id"].num()!!.toLong(), chain = AddressChain.valueOf(o["chain"] as String), address = o["address"] as String,
            label = o["label"] as String?, lastBalance = o["lastBalance"] as String?,
            lastUpdated = o["lastUpdated"].num()?.toLong() ?: 0L,
            observations = o["observations"]?.list()?.map { it.obj() }?.map { AddressObservation(it["t"].num()!!.toLong(), dec(it["b"])!!) }.orEmpty(),
            costLots = o["costLots"]?.list()?.map { it.obj() }?.map {
                AddressCostLot(it["id"].num()!!.toLong(), it["coin"] as String, it["t"].num()!!.toLong(), dec(it["amount"])!!, dec(it["price"]))
            }.orEmpty(),
        )
    }

    private fun trade(raw: Any?): PortfolioTrade {
        val o = raw.obj()
        return PortfolioTrade(
            id = o["id"].num()!!.toLong(), coin = o["coin"] as String, type = PortfolioTxType.valueOf(o["type"] as String),
            amount = dec(o["amount"])!!, priceUsdt = dec(o["price"]), time = o["time"].num()!!.toLong(),
            exchange = o["exchange"] as String?, transferFrom = o["transferFrom"] as String?, transferTo = o["transferTo"] as String?,
        )
    }

    private val csvTexts = CutoffCsvTexts(
        coin = "Coin", amount = "Menge", priceUsdt = "Kurs USDT", valueUsdt = "Wert USDT",
        rate = "Kurs", valueTarget = "Wert", note = "Hinweis", total = "Total",
        noPrice = "kein Kurs", noFx = "kein Devisenkurs", unchecked = "ungeprüft", incomplete = "unvollständig",
        commentFormat = "# %1\$s %2\$s %3\$s", fromAddress = "aus Adresse", addressUnobserved = "nicht beobachtet",
        addressLots = "Posten nach Beobachtung",
    )

    @Test
    fun cutoffCsvCountsAddressesLikeTheCard() {
        val data = ParityJson.load("cutoff_export.json")
        val cases = data["addressCases"].list().map { it.obj() }
        for (c in cases) {
            val name = c["name"] as String
            val date = LocalDate.parse(c["date"] as String)
            val cutoff = CutoffExport.endOfDayMillis(date, ZoneId.of(c["zone"] as String))
            val trades = c["trades"].list().map(::trade)
            val view = AddressCutoff.at(c["addresses"].list().map(::address), trades, cutoff)
            val holdings = AddressCutoff.holdingsAt(trades, view, cutoff, CostMethod.valueOf(c["method"] as String))
            val prices = c["prices"].obj().mapValues { BigDecimal(it.value as String) }
            val fx = dec(c["fxRate"])
            val rows = CutoffExport.rows(holdings, prices, fx) + CutoffExport.addressRows(view.part, prices, fx)
            val lines = CutoffExport.csv(date, "CHF", null, rows, csvTexts, addressLots = view.lotsAfterObservation)
                .removePrefix("﻿").split("\r\n")
            val expected = c["expected"].list().map { it as String }
            assertEquals(name, expected, lines.subList(1, 1 + expected.size))
            assertEquals("$name Ende", listOf(""), lines.drop(1 + expected.size))
        }
        assertTrue(cases.size >= 3)
    }

    private val summaryTexts = TaxSummaryTexts(
        title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "CHF", note = "Note", orientation = "O",
        labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
        incomplete = "incomplete", noFx = "nofx", addressLots = "lots",
    )

    @Test
    fun chWealthCountsAddressesLikeTheCard() {
        val data = ParityJson.load("tax_ch_wealth.json")
        val zone = ZoneId.of(data["zone"] as String)
        val date = LocalDate.parse(data["date"] as String)
        val cutoff = CutoffExport.endOfDayMillis(date, zone)
        for (c in data["watchedCases"].list().map { it.obj() }) {
            val name = c["name"] as String
            val e = c["expected"].obj()
            val trades = c["trades"].list().map(::trade)
            val view = AddressCutoff.at(c["watched"].list().map(::address), trades, cutoff)
            val closes = CutoffCloses(prices = c["closes"].obj().mapValues { BigDecimal(it.value as String) })
            for (m in c["methods"].list()) {
                val w = TaxExport.chWealth(trades, CostMethod.valueOf(m as String), date, zone, closes, dec(c["rate"]), view)
                val rows = e["rows"].list().map { it.list() }
                assertEquals("$name $m coins", rows.map { it[0] }, w.rows.map { it.coin })
                rows.zip(w.rows).forEach { (x, r) ->
                    assertEquals("$name $m amount", r8(dec(x[1])), r8(r.amount))
                    assertEquals("$name $m usdt", r8(dec(x[2])), r8(r.valueUsdt))
                    assertEquals("$name $m target", r8(dec(x[3])), r8(r.valueTarget))
                    assertEquals("$name $m missing", x[4], r.missing)
                }
                assertEquals("$name $m total usdt", r8(dec(e["totalUsdt"])), r8(w.totalUsdt))
                assertEquals("$name $m money", e["moneyTotal"], w.totalTarget?.let { CutoffExport.money(it) })
                assertEquals("$name $m incomplete", e["incomplete"], w.incomplete)
                assertEquals("$name $m note", e["addressNote"], w.addressNote)
            }
            // Jahresübersicht: Vermerk aus dem Vermögenswert, Hinweiszeile nur bei Bedarf
            val s = TaxExport.yearSummary(
                trades, TaxTemplate.CH, TaxMethod.FIFO, date.year, zone, "CHF",
                mapOf(date.toEpochDay() to BigDecimal(c["rate"] as String)), today = date.plusMonths(6),
                yearEndCloses = closes, yearEndAddresses = view,
            )
            assertEquals("$name summary note", e["addressNote"], s.wealth?.addressNote)
            val line = s.lines.single { it.line == TaxLine.CH_WEALTH_VALUE }
            assertEquals("$name summary usdt", r8(dec(e["totalUsdt"])), r8(line.usdt))
            val csv = TaxExport.summaryCsv(s, summaryTexts, listOf("H")).split("\r\n")
            assertEquals("$name summary lots line", if (e["addressNote"] == true) 1 else 0, csv.count { it == "lots" })
        }
    }
}
