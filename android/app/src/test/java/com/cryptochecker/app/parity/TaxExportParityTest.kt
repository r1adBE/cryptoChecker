package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCsvFormat
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.TaxCsvTexts
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.gainRows
import com.cryptochecker.app.domain.portfolio.gainsCsv
import com.cryptochecker.app.domain.portfolio.incomeCsv
import com.cryptochecker.app.domain.portfolio.incomeRows
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Gemeinsame Testfälle `tax_export.json` (iOS: `TaxExportTests.swift`). */
class TaxExportParityTest {

    private val data = ParityJson.load("tax_export.json")
    private val zone = ZoneId.of(data["zone"] as String)
    private val year = data["year"].num()!!.toInt()
    private val currency = data["currency"] as String
    private val rates = data["rates"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }

    private fun dec(v: Any?): BigDecimal? = (v as String?)?.let { BigDecimal(it) }

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
            feeCoin = t["feeCoin"] as String?,
            feeCoinAmount = dec(t["feeCoinAmount"]),
            feeCoinPrice = dec(t["feeCoinPrice"]),
            acquiredTime = t["acquiredTime"].num()?.toLong(),
            exchange = t["exchange"] as String?,
            transferFrom = t["transferFrom"] as String?,
            transferTo = t["transferTo"] as String?,
            toCoin = t["toCoin"] as String?,
            toAmount = dec(t["toAmount"]),
            swapValueUsdt = dec(t["swapValue"]),
            interestIncome = (t["interest"] as Boolean?) ?: false,
        )
    }

    private val trades = parseTrades(data["trades"])

    private val texts = data["texts"].obj().let { x ->
        fun s(k: String) = x[k] as String
        TaxCsvTexts(
            coin = s("coin"), acquired = s("acquired"), sold = s("sold"), holdingDays = s("holdingDays"),
            longTerm = s("longTerm"), amount = s("amount"), costUsdt = s("costUsdt"), proceedsUsdt = s("proceedsUsdt"),
            gainUsdt = s("gainUsdt"), costTarget = s("costTarget"), proceedsTarget = s("proceedsTarget"),
            gainTarget = s("gainTarget"), date = s("date"), valueUsdt = s("valueUsdt"), valueTarget = s("valueTarget"),
            note = s("note"), total = s("total"), yes = s("yes"), no = s("no"), costMissing = s("costMissing"),
            proceedsMissing = s("proceedsMissing"), feeLeg = s("feeLeg"), feeValueMissing = s("feeValueMissing"),
            avgCostRates = s("avgCostRates"), oversold = s("oversold"), proceedsWithoutCost = s("proceedsWithoutCost"), timeZone = s("timeZone"), valueMissing = s("valueMissing"), noFx = s("noFx"),
            incomplete = s("incomplete"),
            swap = s("swap"), wallet = s("wallet"), walletUnspecified = s("walletUnspecified"),
        )
    }

    private val header = data["header"].list().map { it as String }

    private fun lines(csv: String): List<String> {
        assertTrue("BOM", csv.startsWith("\uFEFF"))
        val all = csv.removePrefix("\uFEFF").split("\r\n")
        assertEquals("Zeilenende", "", all.last())
        return all.dropLast(1)
    }

    @Test
    fun yearsAndRules() {
        assertEquals(data["years"].list().map { it.num()!!.toInt() }, TaxExport.years(trades, zone))
        val d = data["defaultYear"].obj()
        assertEquals(d["expected"].num()!!.toInt(), TaxExport.defaultYear(TaxExport.years(trades, zone), LocalDate.parse(d["today"] as String)))
        assertEquals(null, TaxExport.defaultYear(emptyList(), LocalDate.of(2026, 1, 1)))
        data["longTerm"].list().map { it.list() }.forEach { c ->
            assertEquals("longTerm $c", c[2], TaxExport.isLongTerm(LocalDate.parse(c[0] as String), LocalDate.parse(c[1] as String)))
        }
        data["rateOn"].list().map { it.list() }.forEach { c ->
            val rate = TaxExport.rateOn(rates, LocalDate.parse(c[0] as String), currency)
            assertEquals("rateOn $c", dec(c[1]), rate)
        }
        assertEquals(BigDecimal.ONE, TaxExport.rateOn(null, LocalDate.of(2025, 1, 1), "USD"))
        assertEquals(null, TaxExport.rateOn(null, LocalDate.of(2025, 1, 1), "CHF"))
        data["fxFrom"].obj().forEach { (method, expected) ->
            assertEquals("fxFrom $method", LocalDate.parse(expected as String), TaxExport.fxFrom(trades, CostMethod.fromName(method), year, zone))
        }
    }

    /**
     * USDT zum Marktkurs (Owner-Entscheid Okt. 2026): Umrechnung × USDT/USD-Tagesschluss; ohne Schluss USDT = 1 als
     * Näherung, je Zeile vermerkt. Wie iOS.
     */
    @Test
    fun usdtMarket() {
        val m = data["usdtMarket"].obj()
        val usdt = m["usdtUsd"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }
        m["rateOn"].list().map { it.list() }.forEach { c ->
            val date = LocalDate.parse(c[0] as String)
            val rate = TaxExport.rateOn(rates, date, c[1] as String, usdt)
            val expected = dec(c[2])
            if (expected == null) assertNull("rateOn $c", rate) else assertEquals("rateOn $c", 0, expected.compareTo(rate!!))
            assertEquals("usdtApproximateOn $c", c[3], TaxExport.usdtApproximateOn(usdt, date))
        }
        val txs = parseTrades(m["trades"])
        val method = TaxMethod.of(CostMethod.FIFO)
        val period = TaxTemplate.NEUTRAL.period(2025)
        for ((key, usdtUsd) in listOf("gains" to usdt, "gainsPar" to null)) {
            val rows = TaxExport.gainRows(txs, method, period, zone, currency, rates, usdtUsd = usdtUsd)
            val expected = m[key].list().map { it.obj() }
            assertEquals("$key rows", expected.size, rows.size)
            expected.zip(rows).forEach { (e, r) ->
                assertEquals("$key coin", e["coin"], r.coin)
                assertEquals("$key cost ${r.costTarget}", 0, dec(e["costTarget"])!!.compareTo(r.costTarget!!))
                assertEquals("$key proceeds ${r.proceedsTarget}", 0, dec(e["proceedsTarget"])!!.compareTo(r.proceedsTarget!!))
                assertEquals("$key gain ${r.gainTarget}", 0, dec(e["gainTarget"])!!.compareTo(r.gainTarget!!))
                assertEquals("$key usdtApproximate", e["usdtApproximate"], r.usdtApproximate)
            }
        }
        for ((key, usdtUsd) in listOf("income" to usdt, "incomePar" to null)) {
            val rows = TaxExport.incomeRows(txs, period, zone, currency, rates, usdtUsd = usdtUsd)
            val expected = m[key].list().map { it.obj() }
            assertEquals("$key rows", expected.size, rows.size)
            expected.zip(rows).forEach { (e, r) ->
                assertEquals("$key date", LocalDate.parse(e["date"] as String), r.date)
                assertEquals("$key value ${r.valueTarget}", 0, dec(e["valueTarget"])!!.compareTo(r.valueTarget!!))
                assertEquals("$key usdtApproximate", e["usdtApproximate"], r.usdtApproximate)
            }
        }
        // Vermerk je Zeile: nie still mit USDT = 1 gerechnet
        val marked = texts.copy(usdtAssumed = m["usdtAssumed"] as String)
        val csv = lines(TaxExport.incomeCsv(TaxExport.incomeRows(txs, period, zone, currency, rates, usdtUsd = usdt), marked, header))
        m["incomeLines"].list().map { it as String }.forEach { assertTrue("income line $it in $csv", it in csv) }
    }

    /**
     * Build 78: Einkünfte-CSV AT — Zins (beim Zufluss steuerpflichtig) und Staking (nur Information) je Zeile vermerkt,
     * zwei Summen mit je eigenem «unvollständig»; ohne die AT-Texte (alle anderen Vorlagen) unverändert eine Summe. Wie iOS.
     */
    @Test
    fun atIncomeSplitsInterestAndStaking() {
        val a = data["atIncome"].obj()
        val txs = parseTrades(a["trades"])
        val period = TaxTemplate.NEUTRAL.period(2025)
        val rows = TaxExport.incomeRows(txs, period, zone, currency, rates)
        val at = a["atTexts"].obj().let { x ->
            texts.copy(
                incomeInterest = x["incomeInterest"] as String, incomeStaking = x["incomeStaking"] as String,
                totalInterest = x["totalInterest"] as String, totalStaking = x["totalStaking"] as String,
            )
        }
        assertEquals("AT", a["at"].list(), lines(TaxExport.incomeCsv(rows, at, header)))
        assertEquals("neutral", a["neutral"].list(), lines(TaxExport.incomeCsv(rows, texts, header)))
    }

    @Test
    fun csvCases() {
        val cases = data["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 2)
        cases.forEach { c ->
            val method = CostMethod.fromName(c["method"] as String)
            val format = CutoffCsvFormat((c["separator"] as String).single(), (c["decimal"] as String).single())
            val gains = TaxExport.gainsCsv(TaxExport.gainRows(trades, method, year, zone, currency, rates), texts, header, format)
            assertEquals("gains $method", c["gains"].list(), lines(gains))
            val income = TaxExport.incomeCsv(TaxExport.incomeRows(trades, year, zone, currency, rates), texts, header, format)
            assertEquals("income $method", c["income"].list(), lines(income))
        }
    }

    /**
     * Build 56/57: Gebühr in USDT/Fiat einer Auszahlung bzw. Umbuchung bei UK, US, CA, AU, JP nur Information (Vermerk,
     * nicht in der Summe; UK HMRC CRYPTO22150), DE und Neutral unverändert. Wie iOS.
     */
    @Test
    fun withdrawalFeeInfoIsNotInTotals() {
        val w = data["withdrawalFeeInfo"].obj()
        val z = ZoneId.of(w["zone"] as String)
        val y = w["year"].num()!!.toInt()
        val wTrades = parseTrades(w["trades"])
        val wTexts = texts.copy(withdrawalFee = "fee", withdrawalFeeInfo = "feeinfo", transferFeeInfo = "feeinfo")
        w["cases"].list().map { it.obj() }.forEach { c ->
            val tpl = TaxTemplate.valueOf(c["template"] as String)
            val method = TaxMethod.valueOf(c["method"] as String)
            // Kalenderjahr für alle (UK-Steuerjahr ab 6. April enthielte die Fälle nicht)
            val rows = TaxExport.gainRows(wTrades, method, TaxTemplate.NEUTRAL.period(y), z, "USD", null, rules = tpl.eventRules)
            val info = c["infoRows"].num()!!.toInt()
            assertEquals("$tpl infoRows", info, rows.count { it.feeInfoOnly })
            val csv = lines(TaxExport.gainsCsv(rows, wTexts, emptyList()))
            val total = csv.single { it.startsWith(wTexts.total + ";") }.split(";")
            assertEquals("$tpl total", c["totalGain"], total[8])
            assertEquals("$tpl note", info, csv.count { it.endsWith(";feeinfo") })
        }
    }

    /**
     * Build 58: FIFO-Posten tragen ihren Einstand in Teilen mit Datum — CH: Kauf 2018 5000 USDT (Kurs 1.00) + Umbuchungsgebühr
     * 2024 100 USDT (Kurs 0.85) = 5085 CHF (FIFO, FIFO je Wallet und Ø gleich); Umbuchungsgebühr auf Bestand ohne Kaufpreis
     * als Info-Zeile «nicht zugeordnet – Einstand unbekannt» nach der Summe. Wie iOS.
     */
    @Test
    fun fifoCostPartsAtOwnRates() {
        val f = data["fifoCostParts"].obj()
        val z = ZoneId.of(f["zone"] as String)
        val y = f["year"].num()!!.toInt()
        val cur = f["currency"] as String
        val fRates = f["rates"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }
        val fTrades = parseTrades(f["trades"])
        val fTexts = texts.copy(unassignedFee = f["unassignedFeeText"] as String)
        f["cases"].list().map { it.obj() }.forEach { c ->
            val method = CostMethod.fromName(c["method"] as String)
            val rows = TaxExport.gainRows(fTrades, method, y, z, cur, fRates)
            val unassigned = TaxExport.unassignedFeesIn(fTrades, method, y, z)
            val csv = TaxExport.gainsCsv(rows, fTexts, emptyList(), unassignedFees = unassigned, zone = z)
            assertEquals("fifoCostParts $method", c["gains"].list(), lines(csv))
        }
    }

    /**
     * Jahresübersicht DE über die Vorlage ([TaxExport.yearSummary] mit [TaxTemplate.DE]): Summen, Freigrenzen je Jahr
     * (600/1000 €, 256 €), CSV; Tausch als Veräusserung. Zähler des Fixtures als Merker der Posten (> 0 ↔ Merker gesetzt).
     */
    @Test
    fun deSummary() {
        val de = data["deSummary"].obj()
        val deZone = ZoneId.of(de["zone"] as String)
        val deTrades = parseTrades(de["trades"])
        val eurRates = de["eurRates"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }
        val t = de["texts"].obj().mapValues { it.value as String }
        val labels = de["labels"].obj().entries.associate { TaxLine.valueOf(it.key) to it.value as String }
        val summaryTexts = TaxSummaryTexts(
            title = t.getValue("title"), disclaimer = t.getValue("disclaimer"), item = t.getValue("item"), usdt = t.getValue("usdt"),
            target = t.getValue("eur"), note = t.getValue("note"), orientation = "Orientation", labels = labels,
            below = t.getValue("below"), reached = t.getValue("reached"), unknown = t.getValue("unknown"),
            incomplete = t.getValue("incomplete"), noFx = t.getValue("noFx"),
            orientationHeading = t.getValue("orientationHeading"), orientationItem = t.getValue("orientationItem"),
        )
        val deHeader = de["header"].list().map { it as String }
        de["limits"].list().map { it.list() }.forEach { (y, limit) ->
            assertEquals("limit $y", 0, BigDecimal(limit as String).compareTo(TaxExport.privateSalesLimitEur(y.num()!!.toInt())))
        }
        assertEquals(0, BigDecimal(256).compareTo(TaxExport.INCOME_LIMIT_EUR))
        val cases = de["cases"].list().map { it.obj() }
        assertTrue(cases.size >= 4)
        for (c in cases) {
            val y = c["year"].num()!!.toInt()
            val method = CostMethod.fromName(c["method"] as String)
            val eur = c["eur"] == true
            val sum = TaxExport.yearSummary(
                deTrades, TaxTemplate.DE, TaxMethod.of(method), y, deZone, "EUR", if (eur) eurRates else null,
            )
            val label = "de $y $method eur=$eur"
            val e = c["expected"].obj()
            fun line(l: TaxLine) = sum.lines.firstOrNull { it.line == l }
            fun m(v: BigDecimal?): String? = v?.let { CutoffExport.money(it) }
            // Posten fehlt (z. B. ohne Haltedauer): Betrag 0, in EUR nur mit Kursen
            fun usdt(l: TaxLine): String? = m(line(l)?.usdt ?: BigDecimal.ZERO)
            fun eurOf(l: TaxLine): String? = if (!eur) null else m(line(l)?.target ?: BigDecimal.ZERO)
            val unclassified = line(TaxLine.UNCLASSIFIED)
            val got = mapOf(
                "shortGainsUsdt" to usdt(TaxLine.SHORT_GAINS), "shortLossesUsdt" to usdt(TaxLine.SHORT_LOSSES),
                "netShortUsdt" to usdt(TaxLine.SHORT_NET), "longGainsUsdt" to usdt(TaxLine.LONG_GAINS),
                "longLossesUsdt" to usdt(TaxLine.LONG_LOSSES), "unclassifiedUsdt" to usdt(TaxLine.UNCLASSIFIED),
                "shortGainsEur" to eurOf(TaxLine.SHORT_GAINS), "shortLossesEur" to eurOf(TaxLine.SHORT_LOSSES),
                "netShortEur" to eurOf(TaxLine.SHORT_NET), "longGainsEur" to eurOf(TaxLine.LONG_GAINS),
                "longLossesEur" to eurOf(TaxLine.LONG_LOSSES), "unclassifiedEur" to eurOf(TaxLine.UNCLASSIFIED),
                "privateSalesLimitEur" to m(line(TaxLine.DE_PRIVATE_LIMIT)?.target),
                "privateSalesStatus" to line(TaxLine.DE_PRIVATE_LIMIT)?.status?.name,
                "incomeUsdt" to m(line(TaxLine.INCOME)?.usdt), "incomeEur" to m(line(TaxLine.INCOME)?.target),
                "incomeLimitEur" to m(line(TaxLine.DE_INCOME_LIMIT)?.target),
                "incomeStatus" to line(TaxLine.DE_INCOME_LIMIT)?.status?.name, "eurAvailable" to sum.fxAvailable,
            )
            val flags = mapOf(
                "unknownGainCount" to (line(TaxLine.SHORT_NET)?.incomplete == true || unclassified?.incomplete == true),
                "unclassifiedCount" to (unclassified != null),
                "eurFxMissingCount" to (line(TaxLine.SHORT_NET)?.noFx == true || unclassified?.noFx == true),
                "incomeValueMissingCount" to (line(TaxLine.INCOME)?.incomplete == true),
                "incomeFxMissingCount" to (line(TaxLine.INCOME)?.noFx == true),
            )
            e.forEach { (k, v) ->
                when {
                    k in flags -> assertEquals("$label $k", (v.num()?.toInt() ?: 0) > 0, flags.getValue(k))
                    v is Number -> assertEquals("$label $k", v.toInt(), got[k])
                    else -> assertEquals("$label $k", v, got[k])
                }
            }
            c["csv"]?.let { expected ->
                val csv = TaxExport.summaryCsv(sum, summaryTexts, deHeader, de["zoneLine"] as String)
                assertEquals("$label csv", expected.list(), lines(csv))
            }
            c["swapLine"]?.let { raw ->
                val (prefix, suffix) = raw.list().map { it as String }
                val gains = lines(TaxExport.gainsCsv(TaxExport.gainRows(deTrades, method, y, deZone, "EUR", eurRates), texts, header))
                assertTrue("$label swap line in $gains", gains.any { it.startsWith(prefix) && it.endsWith(suffix) })
            }
        }
    }

    @Test
    fun emptyYear() {
        val gains = TaxExport.gainsCsv(TaxExport.gainRows(trades, CostMethod.FIFO, 2019, zone, currency, rates), texts, emptyList())
        assertEquals(3, lines(gains).size)
        assertEquals("Zeitzone zuerst", data["texts"].obj()["timeZone"], lines(gains).first())
        assertTrue(lines(gains).last().startsWith("Total;;;;;;0.00;0.00;0.00;0.00;0.00;0.00;"))
    }

    /** CH: «Vermögenssteuer nicht gerechnet – Kursliste ESTV» als Kopfzeile 2 jeder CSV; andere Vorlagen unverändert. */
    @Test
    fun chWealthNoteInFirstLines() {
        val header = listOf("Title", "Info", "Disclaimer")
        val note = "Wealth tax not calculated – ESTV price list: https://example"
        assertEquals(listOf("Title", note, "Info", "Disclaimer"),
            TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.CH, header, note))
        assertEquals(header, TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.DE, header, note))
        assertEquals(header, TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.CH, header, ""))
        // Nicht doppelt
        val once = TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.CH, header, note)
        assertEquals(once, TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.CH, once, note))
        assertEquals(listOf(note), TaxExport.withChWealthNote(com.cryptochecker.app.domain.portfolio.TaxTemplate.CH, emptyList(), note))
    }

    /**
     * Build 47 (B4): Gewinn 1020 ≤ 1 Jahr und Auszahlungsgebühr 25 USDT — die Gebühr ist keine Veräusserung: eigener
     * Posten, NICHT in der Summe § 23 → 1020 ≥ 1000 «erreicht» (vorher 995 «unter der Freigrenze»). Wie iOS.
     */
    @Test
    fun withdrawalFeeIsNotNettedIntoPrivateSales() {
        val z = ZoneId.of("UTC")
        val t0 = 1736510400000L // 10.01.2025
        val trades = listOf(
            PortfolioTrade(1, "BTC", PortfolioTxType.BUY, BigDecimal("1"), BigDecimal("1000"), t0),
            PortfolioTrade(2, "BTC", PortfolioTxType.SELL, BigDecimal("1"), BigDecimal("2020"), t0 + 50 * 86_400_000L),
            PortfolioTrade(3, "ETH", PortfolioTxType.BUY, BigDecimal("1"), BigDecimal("100"), t0),
            PortfolioTrade(
                4, "ETH", PortfolioTxType.WITHDRAWAL, BigDecimal("0.5"), null, t0 + 80 * 86_400_000L,
                fee = BigDecimal("25"), feeUnit = PortfolioFeeUnit.QUOTE,
            ),
        )
        // EUR = USD (Kurs 1) an jedem Tag des Jahres
        val eur = (LocalDate.of(2025, 1, 1).toEpochDay()..LocalDate.of(2025, 12, 31).toEpochDay()).associateWith { BigDecimal.ONE }
        val s = TaxExport.yearSummary(trades, TaxTemplate.DE, TaxMethod.FIFO, 2025, z, "EUR", eur)
        fun line(l: TaxLine) = s.lines.first { it.line == l }
        assertEquals(0, BigDecimal("1020").compareTo(line(TaxLine.SHORT_GAINS).usdt))
        assertEquals(0, BigDecimal.ZERO.compareTo(line(TaxLine.SHORT_LOSSES).usdt))
        assertEquals(0, BigDecimal("1020").compareTo(line(TaxLine.SHORT_NET).target))
        assertEquals(com.cryptochecker.app.domain.portfolio.TaxLimitStatus.REACHED, line(TaxLine.DE_PRIVATE_LIMIT).status)
        val fees = line(TaxLine.WITHDRAWAL_FEES)
        assertEquals(0, BigDecimal("-25").compareTo(fees.usdt))
        assertEquals(0, BigDecimal("-25").compareTo(fees.target))
        assertEquals(com.cryptochecker.app.domain.portfolio.TaxSummaryNote.DE_WITHDRAWAL_FEE, fees.note)
        // Eigener Posten in der CSV mit Vermerk
        val texts = TaxSummaryTexts(
            title = "T", disclaimer = "D", item = "I", usdt = "USDT", target = "EUR", note = "N", orientation = "O",
            labels = mapOf(TaxLine.WITHDRAWAL_FEES to "WF", TaxLine.DE_PRIVATE_LIMIT to "PL"),
            below = "below", reached = "reached", unknown = "unknown", incomplete = "inc", noFx = "nofx",
            notes = mapOf(com.cryptochecker.app.domain.portfolio.TaxSummaryNote.DE_WITHDRAWAL_FEE to "not netted"),
        )
        val csv = TaxExport.summaryCsv(s, texts, emptyList())
        assertTrue(csv, csv.contains("WF;-25.00;-25.00;not netted"))
        assertTrue(csv, csv.contains("reached"))
    }
}
