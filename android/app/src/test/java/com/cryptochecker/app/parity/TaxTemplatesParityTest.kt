package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.TaxEventRules
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxLimitStatus
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMatch
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryNote
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Gemeinsame Testfälle `tax_templates.json` (iOS: `TaxTemplatesTests.swift`). */
class TaxTemplatesParityTest {

    private val data = ParityJson.load("tax_templates.json")
    private val zone = ZoneId.of(data["zone"] as String)

    private fun dec(v: Any?): BigDecimal? = (v as String?)?.let { BigDecimal(it) }

    /** Vergleich auf 8 Nachkommastellen (Teilungen runden auf 34 gültige Stellen). */
    private fun r8(v: BigDecimal?): String? = v?.setScale(8, RoundingMode.HALF_UP)?.toPlainString()

    private fun parseTrades(raw: Any?): List<PortfolioTrade> = raw.list().map { it.obj() }.map { t ->
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = PortfolioTxType.fromName(t["type"] as String)!!,
            amount = BigDecimal(t["amount"] as String),
            priceUsdt = dec(t["price"]),
            time = t["time"].num()!!.toLong(),
            fee = dec(t["fee"]),
            feeUnit = (t["feeUnit"] as String?)?.let { PortfolioFeeUnit.fromName(it) },
            toCoin = t["toCoin"] as String?,
            toAmount = dec(t["toAmount"]),
            swapValueUsdt = dec(t["swapValue"]),
            feeCoin = t["feeCoin"] as String?,
            feeCoinAmount = dec(t["feeCoinAmount"]),
            feeCoinPrice = dec(t["feeCoinPrice"]),
            exchange = t["exchange"] as String?,
            transferFrom = t["transferFrom"] as String?,
            transferTo = t["transferTo"] as String?,
            giftToSpouse = t["giftToSpouse"] == true,
            acquiredTime = t["acquiredTime"].num()?.toLong(),
            // Build 47: Gebühr-Zeile nur mit Import-Herkunft
            imported = t["importKey"] != null,
            // Zins (Lending): Notiz wie aus dem CSV-Import ([PortfolioTxEntity.toTrade])
            interestIncome = t["type"] == "REWARD" && TaxEngines.interestReward(t["note"] as String?),
        )
    }

    /** Devisenkurse «Tag → USD→Währung» als Tageskurs-Tabelle des Exports (Tage seit 1970). */
    private fun rates(raw: Any?): Map<Long, BigDecimal>? =
        raw?.obj()?.entries?.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }

    private fun priceAt(raw: Any?): ((String, Long) -> BigDecimal?)? {
        val prices = (raw ?: return null).obj().mapValues { BigDecimal(it.value as String) }
        return { coin, _ -> prices[coin] }
    }

    @Test
    fun periodsLabelsSuggestions() {
        data["periodOf"].list().map { it.list() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c[0] as String)
            assertEquals("periodOf $c", c[2].num()!!.toInt(), tpl.periodOf(LocalDate.parse(c[1] as String)))
        }
        data["period"].list().map { it.list() }.forEach { c ->
            val p = TaxTemplate.valueOf(c[0] as String).period(c[1].num()!!.toInt())
            assertEquals("start $c", LocalDate.parse(c[2] as String), p.start)
            assertEquals("end $c", LocalDate.parse(c[3] as String), p.end)
        }
        data["labels"].list().map { it.list() }.forEach { c ->
            assertEquals("label $c", c[2], TaxTemplate.valueOf(c[0] as String).label(c[1].num()!!.toInt()))
        }
        data["suggest"].list().map { it.list() }.forEach { c ->
            assertEquals("suggest $c", TaxTemplate.valueOf(c[1] as String), TaxTemplate.suggest(c[0] as String))
        }
        data["methodFor"].list().map { it.list() }.forEach { c ->
            val got = TaxTemplate.valueOf(c[0] as String).methodFor(CostMethod.fromName(c[1] as String))
            assertEquals("methodFor $c", TaxMethod.valueOf(c[2] as String), got)
        }
        // Vorlage verlangt eine andere Methode als die App-Einstellung: Hinweis in Kopfzeile, Dialog und Einstellungen
        data["methodOverridden"].list().map { it.list() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c[0] as String)
            val app = CostMethod.fromName(c[1] as String)
            assertEquals("methodOverridden $c", c[2] as Boolean, tpl.methodOverridden(app))
            // Überschrieben genau dann, wenn der Export eine andere Methode rechnet
            assertEquals("methodOverridden/methodFor $c", tpl.methodOverridden(app), tpl.methodFor(app) != TaxMethod.of(app))
        }
        // Methode je Steuerjahr (US ab 2025 nur FIFO je Wallet, Rev. Proc. 2024-28)
        data["methodForYear"].list().map { it.list() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c[0] as String)
            val app = CostMethod.fromName(c[1] as String)
            val year = c[2].num()!!.toInt()
            assertEquals("methodForYear $c", TaxMethod.valueOf(c[3] as String), tpl.methodFor(app, year))
            assertEquals("methodOverriddenYear $c", c[4] as Boolean, tpl.methodOverridden(app, year))
        }
        data["ukExempt"].list().map { it.list() }.forEach { c ->
            assertEquals("ukExempt $c", dec(c[1]), TaxTemplate.ukAnnualExemptGbp(c[0].num()!!.toInt()))
        }
        // Build 56: Regeln je Vorlage (Schenkung, Schenkung an Ehe-/Lebenspartner, Netzwerkgebühr); Partner-Merker (Build 58: Feld)
        data["eventRules"].list().map { it.list() }.forEach { c ->
            val expected = TaxEventRules(c[1] as Boolean, c[2] as Boolean, c[3] as Boolean, c[4] as Boolean)
            assertEquals("eventRules $c", expected, TaxTemplate.valueOf(c[0] as String).eventRules)
        }
        data["spouseFlag"].list().map { it.list() }.forEach { c ->
            val trade = PortfolioTrade(1, "BTC", PortfolioTxType.fromName(c[0] as String)!!, BigDecimal.ONE, null, 0L, giftToSpouse = c[1] as Boolean)
            assertEquals("spouseFlag $c", c[2] as Boolean, trade.isGiftToSpouse)
        }
        assertEquals(TaxTemplate.NEUTRAL, TaxTemplate.fromName(null))
        assertEquals(TaxTemplate.NEUTRAL, TaxTemplate.fromName("XX"))
        assertEquals(TaxTemplate.UK, TaxTemplate.fromName("UK"))
    }

    @Test
    fun engines() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 7)
        cases.forEach { c ->
            val name = c["name"] as String
            val method = TaxMethod.valueOf(c["method"] as String)
            // CA: Superficial Loss in der Steuerwährung (Kurse je Tag wie im Export)
            val fx = rates(c["fx"])?.let { r -> { t: Long -> TaxExport.rateOn(r, Instant.ofEpochMilli(t).atZone(zone).toLocalDate(), "CAD") } }
            // Schenkung und Netzwerkgebühr nach der Vorlage («template», z. B. AU/US mit FIFO), sonst nach der Rechnung
            val rules = (c["template"] as String?)?.let { TaxTemplate.valueOf(it).eventRules } ?: TaxEventRules.of(method)
            val got = TaxEngines.disposals(parseTrades(c["trades"]), method, zone, priceAt(c["prices"]), fx, rules)
            val expected = c["disposals"].list().map { it.obj() }
            assertEquals("$name: Anzahl ${got.map { it.coin to it.amount }}", expected.size, got.size)
            expected.zip(got).forEachIndexed { i, (e, d) ->
                val label = "$name #$i"
                assertEquals(label, e["coin"], d.coin)
                assertEquals(label, LocalDate.parse(e["sold"] as String), Instant.ofEpochMilli(d.sellTime).atZone(zone).toLocalDate())
                val acquired = (d.acquiredTime ?: d.matchedTime)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }
                assertEquals("$label acquired", e["acquired"], acquired)
                assertEquals("$label amount", r8(dec(e["amount"])), r8(d.amount))
                assertEquals("$label cost", r8(dec(e["cost"])), r8(d.costUsdt))
                assertEquals("$label proceeds", r8(dec(e["proceeds"])), r8(d.proceedsUsdt))
                assertEquals("$label gain", r8(dec(e["gain"])), r8(d.gainUsdt))
                assertEquals("$label match", (e["match"] as String?)?.let { TaxMatch.valueOf(it) }, d.match)
                assertEquals("$label adjustment", r8(dec(e["adjustment"])), r8(d.adjustmentUsdt))
                assertEquals("$label oldHolding", e["oldHolding"], d.oldHolding)
                assertEquals("$label portfolioValue", r8(dec(e["portfolioValue"])), r8(d.portfolioValueUsdt))
                assertEquals("$label gross", r8(dec(e["gross"])), r8(d.grossProceedsUsdt))
                (e["costIncomplete"] as Boolean?)?.let { assertEquals("$label costIncomplete", it, d.costIncomplete) }
                (e["wallet"] as String?)?.let { assertEquals("$label wallet", it, d.wallet) }
                // Build 47: Verschenkt/bezahlt und geschätzter Wert (Tagesschluss)
                (e["gift"] as Boolean?)?.let { assertEquals("$label gift", it, d.gift) }
                (e["payment"] as Boolean?)?.let { assertEquals("$label payment", it, d.payment) }
                (e["valueEstimated"] as Boolean?)?.let { assertEquals("$label valueEstimated", it, d.valueEstimated) }
                // Build 56: Schenkung an Ehe-/Lebenspartner (UK s. 58, CA 73(1))
                (e["spouseGift"] as Boolean?)?.let { assertEquals("$label spouseGift", it, d.spouseGift) }
                // Herkunftsteile summieren sich exakt zum Einstand
                if (d.costParts.isNotEmpty()) {
                    assertEquals("$label parts", 0, d.costParts.fold(BigDecimal.ZERO) { a, p -> a + p.usdt }.compareTo(d.costUsdt))
                }
            }
            // FR: Abgänge ohne Verkauf (Kürzung der Erwerbspreis-Summe)
            c["outflows"]?.let { raw ->
                val outs = TaxEngines.outflows(parseTrades(c["trades"]), method, priceAt(c["prices"]))
                val exp = raw.list().map { it.obj() }
                assertEquals("$name outflows", exp.size, outs.size)
                exp.zip(outs).forEach { (e, o) ->
                    assertEquals("$name outflow coin", e["coin"], o.coin)
                    assertEquals("$name outflow amount", r8(dec(e["amount"])), r8(o.amount))
                    assertEquals("$name outflow value", r8(dec(e["value"])), r8(o.valueUsdt))
                    assertEquals("$name outflow reduction", r8(dec(e["reduction"])), r8(o.ptaReductionUsdt))
                }
            }
            // Gebühr in Krypto als eigene Veräusserung (UK Netzwerkgebühr, FR Gebühr-Abtretung): Vermerk «Gebühr» im Export
            c["feeRows"]?.let { raw ->
                val fr = raw.list()
                val rows = TaxExport.gainRows(
                    parseTrades(c["trades"]), method, TaxTemplate.NEUTRAL.period(fr[0].num()!!.toInt()), zone, "USD", null,
                    priceAt(c["prices"]), rules = rules,
                )
                assertEquals("$name feeRows", fr[1].num()!!.toInt(), rows.count { it.feeRow })
            }
        }
    }

    /** US-Übergang 1.1.2025 (Rev. Proc. 2024-28): universelles FIFO bis 31.12.2024, Restposten den Wallets zugeordnet. */
    @Test
    fun usTransition() {
        data["walletFrom"].list().map { it.list() }.forEach { c ->
            val got = TaxTemplate.valueOf(c[0] as String).walletFrom(CostMethod.fromName(c[1] as String), c[2].num()!!.toInt())
            assertEquals("walletFrom $c", (c[3] as String?)?.let { LocalDate.parse(it) }, got)
        }
        val u = data["usTransition"].obj()
        val tpl = TaxTemplate.valueOf(u["template"] as String)
        val app = CostMethod.fromName(u["app"] as String)
        val year = u["year"].num()!!.toInt()
        val trades = parseTrades(u["trades"])
        val walletFrom = tpl.walletFrom(app, year)
        val rows = TaxExport.gainRows(trades, tpl.methodFor(app, year), tpl.period(year), zone, "USD", null, walletFrom = walletFrom)
        val expected = u["disposals"].list().map { it.obj() }
        assertEquals("Anzahl", expected.size, rows.size)
        expected.zip(rows).forEach { (e, r) ->
            assertEquals(e["coin"], r.coin)
            assertEquals(LocalDate.parse(e["sold"] as String), r.soldDate)
            assertEquals(LocalDate.parse(e["acquired"] as String), r.acquiredDate)
            assertEquals(r8(dec(e["amount"])), r8(r.amount))
            assertEquals(r8(dec(e["cost"])), r8(r.costUsdt))
            assertEquals(r8(dec(e["proceeds"])), r8(r.proceedsUsdt))
            assertEquals(r8(dec(e["gain"])), r8(r.gainUsdt))
            assertEquals(e["wallet"], r.wallet)
        }
        // Ohne Übergang (FIFO je Wallet über die ganze Geschichte): der Fehler aus Review 13
        val plain = TaxExport.gainRows(trades, TaxMethod.FIFO_WALLET, tpl.period(year), zone, "USD", null)
        assertEquals(r8(dec(u["withoutTransitionGain"])), r8(plain.single().gainUsdt))
        // Jahre davor unverändert universell
        val y2023 = TaxExport.gainRows(trades, tpl.methodFor(app, 2023), tpl.period(2023), zone, "USD", null)
        assertEquals(r8(dec(u["year2023Gain"])), r8(y2023.single().gainUsdt))
        // Übersicht rechnet mit demselben Übergang
        val s = TaxExport.yearSummary(trades, tpl, tpl.methodFor(app, year), year, zone, "USD", null, walletFrom = walletFrom)
        assertEquals(walletFrom, s.walletFrom)
    }

    /** Netzwerkgebühr frisst den letzten Teil mit Kaufpreis: Einstand vermerkt, für jede Vorlage ([TaxExport.lostFeeCostsIn]). */
    @Test
    fun lostFeeCosts() {
        val lf = data["lostFee"].obj()
        val trades = parseTrades(lf["trades"])
        val period = TaxTemplate.NEUTRAL.period(lf["year"].num()!!.toInt())
        lf["expected"].obj().forEach { (m, v) ->
            val got = TaxExport.lostFeeCostsIn(trades, TaxMethod.valueOf(m), period, zone)
            if (v == null) assertTrue("$m lost", got.isEmpty())
            else assertEquals("$m lost", listOf(r8(dec(v))), got.map { r8(it.costUsdt) })
        }
    }

    /** Jahresübersicht-CSV: Tatsachen zuerst, Schlüsse (Grenzen, Sätze) nur im Orientierungs-Block mit Vorsatz. */
    @Test
    fun summaryCsvOrientationBlock() {
        data["summaries"].list().map { it.obj() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c["template"] as String)
            val method = TaxMethod.valueOf(c["method"] as String)
            val s = TaxExport.yearSummary(
                parseTrades(c["trades"]), tpl, method, c["year"].num()!!.toInt(), zone, (c["currency"] as String?) ?: "USD",
                rates(c["rates"]), priceAt(c["prices"]),
            )
            val texts = TaxSummaryTexts(
                title = "T", disclaimer = "D", item = "Item", usdt = "USDT", target = "USD", note = "Note", orientation = "O",
                labels = TaxLine.entries.associateWith { it.name }, below = "below", reached = "reached", unknown = "unknown",
                incomplete = "incomplete", noFx = "nofx", orientationHeading = "Orientation - no tax advice, no assessment",
                orientationItem = "Orientation: %s", loss = "loss",
                notes = mapOf(
                    TaxSummaryNote.AT_REWARD_INFO to "atinfo", TaxSummaryNote.CH_EXCHANGE_VALUE to "chvalue",
                    TaxSummaryNote.CH_FIFO_INFO to "chfifo", TaxSummaryNote.DE_WITHDRAWAL_FEE to "dewfee",
                    TaxSummaryNote.AT_WITHDRAWAL_FEE to "atwfee", TaxSummaryNote.AT_INTEREST_INCOME to "atinterest",
                    TaxSummaryNote.WITHDRAWAL_FEE_INFO to "wfeeinfo", TaxSummaryNote.TRANSFER_FEE_INFO to "tfeeinfo",
                    TaxSummaryNote.AT_TRANSITION_2021 to "attransition",
                ),
            )
            val lines = TaxExport.summaryCsv(s, texts, listOf("H")).removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
            // Zusatz-Vermerke und Status «Verlust» stehen in der Hinweis-Spalte ihres Postens
            s.lines.forEach { l ->
                val text = l.note?.let { texts.notes[it] } ?: "loss".takeIf { l.status == TaxLimitStatus.LOSS }
                if (text != null) assertTrue("$tpl ${l.line} $text", lines.any { it.contains(l.line.name) && it.endsWith(";$text") })
            }
            val head = lines.indexOf("Orientation - no tax advice, no assessment")
            val oriented = s.lines.filter { it.line.orientation }
            if (oriented.isEmpty()) {
                assertEquals("$tpl kein Block", -1, head)
                return@forEach
            }
            assertTrue("$tpl Block", head > 0)
            // Vor dem Block nur Tatsachen, danach nur Orientierung — jede mit Vorsatz
            lines.subList(0, head).forEach { l -> assertTrue("$tpl Tatsache $l", oriented.none { l.startsWith(it.line.name + ";") }) }
            val after = lines.subList(head + 1, lines.size)
            assertEquals("$tpl Orientierung", oriented.map { "Orientation: ${it.line.name}" }, after.map { it.substringBefore(';') })
        }
    }

    @Test
    fun summaries() {
        data["summaries"].list().map { it.obj() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c["template"] as String)
            val method = TaxMethod.valueOf(c["method"] as String)
            val year = c["year"].num()!!.toInt()
            val s = TaxExport.yearSummary(
                parseTrades(c["trades"]), tpl, method, year, zone, (c["currency"] as String?) ?: "USD", rates(c["rates"]),
                priceAt(c["prices"]),
            )
            val expected = c["lines"].list().map { it.list() }
            assertEquals("$tpl lines", expected.map { it[0] }, s.lines.map { it.line.name })
            expected.zip(s.lines).forEach { (e, l) ->
                assertEquals("$tpl ${l.line} usdt", r8(dec(e[1])), r8(l.usdt))
                assertEquals("$tpl ${l.line} target", r8(dec(e[2])), r8(l.target))
                assertEquals("$tpl ${l.line} status", (e[3] as String?)?.let { TaxLimitStatus.valueOf(it) }, l.status)
                // Zusatz-Vermerk (optional, 5. Feld): AT Einkünfte nur Information, CH Wert per 31.12. = Börsenkurs × USD/CHF
                assertEquals("$tpl ${l.line} note", (e.getOrNull(4) as String?)?.let { TaxSummaryNote.valueOf(it) }, l.note)
            }
            // Posten «unvollständig» (optional): z. B. FR Gewinn aus unvollständiger Erwerbspreis-Summe
            c["incompleteLines"]?.let { raw ->
                assertEquals("$tpl incomplete", raw.list().map { it as String }, s.lines.filter { it.incomplete }.map { it.line.name })
            }
            assertTrue(s.fxAvailable)
            assertEquals(tpl.period(year), s.period)
        }
        // Orientierungs-Posten sind markiert, Tatsachen nicht
        assertTrue(TaxLine.DE_PRIVATE_LIMIT.orientation)
        assertTrue(!TaxLine.LONG_GAINS.orientation)
        // AT: Zinsen sind eine Tatsache (Einkunft beim Zufluss), der Sondersteuersatz auf netto + Zinsen ein Schluss
        assertTrue(!TaxLine.AT_INTEREST.orientation)
        assertTrue(TaxLine.AT_RATE.orientation)
    }
}
