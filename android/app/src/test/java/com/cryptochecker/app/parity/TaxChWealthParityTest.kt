package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalancesAt
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCloses
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryNote
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.chWealth
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** Gemeinsame Testfälle `tax_ch_wealth.json` — CH-Vermögenswert per 31.12. (iOS: `TaxChWealthTests.swift`). */
class TaxChWealthParityTest {

    private val data = ParityJson.load("tax_ch_wealth.json")
    private val zone = ZoneId.of(data["zone"] as String)
    private val date = LocalDate.parse(data["date"] as String)

    private fun dec(v: Any?): BigDecimal? = (v as String?)?.let { BigDecimal(it) }

    /** Vergleich auf 8 Nachkommastellen (Produkte runden auf 34 gültige Stellen). */
    private fun r8(v: BigDecimal?): String? = v?.setScale(8, RoundingMode.HALF_UP)?.toPlainString()

    private fun trades(raw: Any?): List<PortfolioTrade> = raw.list().map { it.obj() }.map { t ->
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = PortfolioTxType.fromName(t["type"] as String)!!,
            amount = BigDecimal(t["amount"] as String),
            priceUsdt = dec(t["price"]),
            time = t["time"].num()!!.toLong(),
            fee = dec(t["fee"]),
            feeUnit = (t["feeUnit"] as String?)?.let { PortfolioFeeUnit.fromName(it) },
            exchange = t["exchange"] as String?,
            transferFrom = t["transferFrom"] as String?,
            transferTo = t["transferTo"] as String?,
        )
    }

    private fun closes(c: Map<String, Any?>): CutoffCloses? {
        val raw = c["closes"] ?: return null
        return CutoffCloses(
            prices = raw.obj().mapValues { BigDecimal(it.value as String) },
            unchecked = (c["unchecked"] as List<*>?)?.map { it as String }?.toSet().orEmpty(),
        )
    }

    private fun addresses(c: Map<String, Any?>): AddressBalancesAt? = (c["addresses"] as Map<*, *>?)?.let { a ->
        AddressBalancesAt(
            (a["balances"] as Map<*, *>).entries.associate { it.key as String to BigDecimal(it.value as String) },
            (a["unobserved"] as List<*>).map { it as String }.toSet(),
        )
    }

    @Test
    fun wealth() {
        data["cases"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val e = c["expected"].obj()
            c["methods"].list().forEach { m ->
                val method = CostMethod.valueOf(m as String)
                val w = TaxExport.chWealth(trades(c["trades"]), method, date, zone, closes(c)!!, dec(c["rate"]), addresses(c))
                val rows = e["rows"].list().map { it.list() }
                assertEquals("$name $m coins", rows.map { it[0] }, w.rows.map { it.coin })
                rows.zip(w.rows).forEach { (x, r) ->
                    assertEquals("$name $m ${r.coin} amount", r8(dec(x[1])), r8(r.amount))
                    assertEquals("$name $m ${r.coin} usdt", r8(dec(x[2])), r8(r.valueUsdt))
                    assertEquals("$name $m ${r.coin} target", r8(dec(x[3])), r8(r.valueTarget))
                    assertEquals("$name $m ${r.coin} missing", x[4], r.missing)
                    // Nur ein Teil fehlt: bekannter Teil gerechnet, «unvollständig» (wie die Bestandsdatei); fehlt = false
                    assertEquals("$name $m ${r.coin} incomplete", x.getOrNull(5) ?: false, r.incomplete)
                }
                assertEquals("$name $m total usdt", r8(dec(e["totalUsdt"])), r8(w.totalUsdt))
                assertEquals("$name $m total target", r8(dec(e["totalTarget"])), r8(w.totalTarget))
                assertEquals("$name $m incomplete", e["incomplete"], w.incomplete)
                assertEquals("$name $m noFx", e["noFx"], w.noFx)
                // Summe = Summe der gerundeten Zeilen, wie sie in der Datei stehen (Nachrechnen geht auf)
                assertEquals("$name $m money", e["moneyTotal"], w.totalTarget?.let { CutoffExport.money(it) })
                val sumOfRows = w.rows.mapNotNull { it.valueTarget }.fold(BigDecimal.ZERO) { a, v -> a + BigDecimal(CutoffExport.money(v)) }
                if (w.totalTarget != null) assertEquals("$name $m sum of rows", CutoffExport.money(sumOfRows), CutoffExport.money(w.totalTarget!!))
            }
        }
    }

    private val texts = TaxSummaryTexts(
        title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "CHF", note = "Note", orientation = "O",
        labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
        incomplete = "incomplete", noFx = "nofx", orientationHeading = "Orientation", orientationItem = "Orientation: %s",
        notes = mapOf(TaxSummaryNote.CH_WEALTH_ORIENTATION to "chwealth"),
        wealthCoin = "{coin} – {amount}", wealthMissing = "nc", wealthNoPrice = "noprice", wealthUnchecked = "unchecked",
        wealthAddressUnobserved = "unobserved",
    )

    @Test
    fun summaries() {
        data["summaries"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val rates = c["rates"].obj().entries.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }
            val s = TaxExport.yearSummary(
                trades(c["trades"]), TaxTemplate.CH, TaxMethod.valueOf(c["method"] as String), c["year"].num()!!.toInt(), zone,
                c["currency"] as String, rates, today = LocalDate.parse(c["today"] as String), yearEndCloses = closes(c),
            )
            val line = s.lines.single { it.line == TaxLine.CH_WEALTH_VALUE }
            val x = c["line"].list()
            assertEquals("$name usdt", r8(dec(x[0])), r8(line.usdt))
            assertEquals("$name target", r8(dec(x[1])), r8(line.target))
            assertEquals("$name incomplete", x[2], line.incomplete)
            assertEquals("$name noFx", x[3], line.noFx)
            assertEquals("$name notCalculated", x[4], line.notCalculated)
            assertEquals("$name note", TaxSummaryNote.CH_WEALTH_ORIENTATION, line.note)
            if (line.notCalculated) assertNull("$name wealth", s.wealth)
            // Tatsachen-Posten (eigener Vermerk «Orientierung» im Text) vor dem Orientierungs-Block, Kapitalgewinne bleiben
            // «nicht gerechnet» (CH_PRIVATE ohne Betrag)
            assertTrue("$name vor Orientierung", s.lines.indexOf(line) < s.lines.indexOfFirst { it.line.orientation })
            val private = s.lines.single { it.line == TaxLine.CH_PRIVATE }
            assertNull(private.usdt)
            assertNull(private.target)
            val csv = TaxExport.summaryCsv(s, texts, listOf("H")).removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
            val at = csv.indexOfFirst { it.startsWith("CH_WEALTH_VALUE;") }
            val expected = c["csv"].list().map { it as String }
            assertTrue("$name CSV-Posten", at >= 0)
            assertEquals("$name CSV", expected, csv.subList(at, minOf(csv.size, at + expected.size)))
            // Danach kein weiterer Coin mehr, sondern der nächste Posten
            csv.getOrNull(at + expected.size)?.let { next ->
                assertTrue("$name CSV danach $next", TaxLine.entries.any { next.startsWith(it.name + ";") } || next == "Orientation")
            }
            // Adressen mit Einstand: Hinweiszeile nur mit CH-Vermögen und nur bei Bedarf; sonst dieselbe Datei
            val lotsTexts = texts.copy(addressLots = "lots")
            val withLots = TaxExport.summaryCsv(s, lotsTexts, listOf("H"), addressLots = true).split("\r\n")
            val without = TaxExport.summaryCsv(s, lotsTexts, listOf("H")).split("\r\n")
            assertEquals("$name lots", if (s.wealth != null) 1 else 0, withLots.count { it == "lots" })
            assertTrue("$name ohne lots", "lots" !in without)
            assertEquals("$name sonst gleich", without, withLots.filter { it != "lots" })
        }
    }
}
