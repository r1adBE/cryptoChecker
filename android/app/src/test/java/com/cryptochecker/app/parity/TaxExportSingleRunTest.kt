package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCsvFormat
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxCsvTexts
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.gainRows
import com.cryptochecker.app.domain.portfolio.gainsCsv
import com.cryptochecker.app.domain.portfolio.incomeCsv
import com.cryptochecker.app.domain.portfolio.incomeRows
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import com.cryptochecker.app.performance.PortfolioPerformanceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Steuer-Export aus einem Lauf der Veräusserungen ([TaxExport.allDisposals] für Spanne, Übersicht und Zeilen) und einem
 * Lauf der Gebühr-Vermerke ([TaxExport.feesIn]) ergibt Zeichen für Zeichen dieselben Dateien wie die Referenz (je
 * Teil ein eigener Lauf: [TaxExport.fxSpan], [TaxExport.yearSummary], [TaxExport.gainRows], [TaxExport.lostFeeCostsIn],
 * [TaxExport.unassignedFeesIn]) — für alle Fälle aus `tax_templates.json` und `tax_export.json` und die Daten der
 * Leistungstests (alle Vorlagen, mehrere Jahre und App-Methoden). iOS: TaxExportSingleRunTests.swift.
 */
class TaxExportSingleRunTest {

    private class Case(
        val label: String,
        val trades: List<PortfolioTrade>,
        val template: TaxTemplate,
        val method: TaxMethod,
        val year: Int,
        val zone: ZoneId,
        val currency: String,
        val rates: Map<Long, BigDecimal>?,
        val priceAt: ((String, Long) -> BigDecimal?)?,
        val walletFrom: LocalDate?,
        val usdtUsd: Map<Long, BigDecimal>? = null,
    )

    /** Spanne und die drei Dateien eines Exports. */
    private data class Files(
        val span: Pair<LocalDate, LocalDate>,
        val summaryCsv: String,
        val gainsCsv: String,
        val incomeCsv: String,
    )

    /** Referenz: jeder Teil rechnet selbst. */
    private fun reference(c: Case): Files {
        val period = c.template.period(c.year)
        val until = TaxExport.priceUntil(c.template, period, TODAY)
        val rules = c.template.eventRules
        val span = TaxExport.fxSpan(c.trades, c.method, period, c.zone, c.priceAt, rules, c.walletFrom, until, TODAY)
        val summary = TaxExport.yearSummary(
            c.trades, c.template, c.method, c.year, c.zone, c.currency, c.rates, c.priceAt, c.walletFrom, c.usdtUsd,
            today = TODAY,
        )
        val rows = TaxExport.gainRows(
            c.trades, c.method, period, c.zone, c.currency, c.rates, c.priceAt, rules = rules,
            walletFrom = c.walletFrom, usdtUsd = c.usdtUsd,
        )
        val gains = TaxExport.gainsCsv(
            rows, CSV_TEXTS, HEADER, FORMAT,
            lostFeeCosts = TaxExport.lostFeeCostsIn(c.trades, c.method, period, c.zone, rules),
            zone = c.zone,
            unassignedFees = TaxExport.unassignedFeesIn(c.trades, c.method, period, c.zone, rules),
        )
        return Files(span, summaryCsv(summary, c), gains, incomeCsv(c))
    }

    /** Weg der App wie `PortfolioExportViewModel`: Veräusserungen einmal, Gebühr-Vermerke zusammen. */
    private fun singleRun(c: Case): Files {
        val period = c.template.period(c.year)
        val until = TaxExport.priceUntil(c.template, period, TODAY)
        val rules = c.template.eventRules
        val disposals = TaxExport.allDisposals(c.trades, c.method, c.zone, c.priceAt, rules, c.walletFrom)
        val span = TaxExport.fxSpan(
            c.trades, c.method, period, c.zone, c.priceAt, rules, c.walletFrom, until, TODAY, disposals,
        )
        val summary = TaxExport.yearSummary(
            c.trades, c.template, c.method, c.year, c.zone, c.currency, c.rates, c.priceAt, c.walletFrom, c.usdtUsd,
            today = TODAY, disposals = disposals,
        )
        val rows = TaxExport.gainRows(
            c.trades, c.method, period, c.zone, c.currency, c.rates, c.priceAt, rules = rules,
            walletFrom = c.walletFrom, usdtUsd = c.usdtUsd, disposals = disposals,
        )
        val fees = TaxExport.feesIn(c.trades, c.method, period, c.zone, rules)
        val lost = TaxExport.lostFeeCostsIn(c.trades, c.method, period, c.zone, rules)
        assertEquals("${c.label} Netzwerkgebühr", lost, fees.lostFeeCosts)
        val unassigned = TaxExport.unassignedFeesIn(c.trades, c.method, period, c.zone, rules)
        assertEquals("${c.label} nicht zugeordnet", unassigned, fees.unassignedFees)
        val gains = TaxExport.gainsCsv(
            rows, CSV_TEXTS, HEADER, FORMAT, lostFeeCosts = fees.lostFeeCosts, zone = c.zone,
            unassignedFees = fees.unassignedFees,
        )
        return Files(span, summaryCsv(summary, c), gains, incomeCsv(c))
    }

    private fun summaryCsv(summary: com.cryptochecker.app.domain.portfolio.TaxYearSummary, c: Case): String =
        TaxExport.summaryCsv(summary, SUMMARY_TEXTS, HEADER, "Zone ${c.zone.id}", FORMAT)

    private fun incomeCsv(c: Case): String = TaxExport.incomeCsv(
        TaxExport.incomeRows(c.trades, c.template.period(c.year), c.zone, c.currency, c.rates, c.priceAt, c.usdtUsd),
        CSV_TEXTS, HEADER, FORMAT,
    )

    /** Prüft [c]; Rückgabe: Zahl der Gewinne-Zeilen (Abdeckung). */
    private fun check(c: Case): Int {
        val ref = reference(c)
        val run = singleRun(c)
        assertEquals("${c.label} Spanne", ref.span, run.span)
        assertEquals("${c.label} Jahresübersicht", ref.summaryCsv, run.summaryCsv)
        assertEquals("${c.label} Gewinne", ref.gainsCsv, run.gainsCsv)
        assertEquals("${c.label} Einkünfte", ref.incomeCsv, run.incomeCsv)
        return run.gainsCsv.count { it == '\n' }
    }

    // ---- Fixtures ----

    private fun dec(v: Any?): BigDecimal? = (v as String?)?.let { BigDecimal(it) }

    private fun parseTrades(raw: Any?): List<PortfolioTrade> = raw.list().map { it.obj() }.map { t ->
        val type = PortfolioTxType.fromName(t["type"] as String)!!
        PortfolioTrade(
            id = t["id"].num()!!.toLong(),
            coin = t["coin"] as String,
            type = type,
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
            imported = t["importKey"] != null,
            feeRow = t["feeRow"] == true,
            interestIncome = t["interest"] == true,
        )
    }

    private fun isoRates(raw: Any?): Map<Long, BigDecimal>? =
        raw?.obj()?.entries?.associate { LocalDate.parse(it.key).toEpochDay() to BigDecimal(it.value as String) }

    private fun priceAt(raw: Any?): ((String, Long) -> BigDecimal?)? {
        val prices = (raw ?: return null).obj().mapValues { BigDecimal(it.value as String) }
        return { coin, _ -> prices[coin] }
    }

    /** Vorlage zu einer Rechnung ohne Vorlage-Angabe im Fall. */
    private fun templateOf(method: TaxMethod): TaxTemplate = when (method) {
        TaxMethod.UK_POOL -> TaxTemplate.UK
        TaxMethod.CA_ACB -> TaxTemplate.CA
        TaxMethod.JP_TOTAL_AVERAGE -> TaxTemplate.JP
        TaxMethod.FR_PTA -> TaxTemplate.FR
        TaxMethod.AT_MOVING_AVERAGE -> TaxTemplate.AT
        TaxMethod.FIFO_WALLET -> TaxTemplate.DE
        TaxMethod.FIFO, TaxMethod.AVERAGE -> TaxTemplate.NEUTRAL
    }

    /** Je Steuerjahr mit Transaktionen ein Fall (Vorjahr dazu: Herkunftsteile über die Jahresgrenze). */
    private fun yearsOf(trades: List<PortfolioTrade>, zone: ZoneId, tpl: TaxTemplate): List<Int> {
        val years = TaxExport.years(trades, zone, tpl)
        return (years + years.map { it + 1 }).distinct()
    }

    @Test
    fun templateFixtures() {
        val data = ParityJson.load("tax_templates.json")
        val zone = ZoneId.of(data["zone"] as String)
        var rows = 0
        var cases = 0
        val raw = data["cases"].list().map { it.obj() } + data["summaries"].list().map { it.obj() }
        raw.forEach { c ->
            val method = TaxMethod.valueOf(c["method"] as String)
            val tpl = (c["template"] as String?)?.let { TaxTemplate.valueOf(it) } ?: templateOf(method)
            val trades = parseTrades(c["trades"])
            val currency = (c["currency"] as String?) ?: if (c["fx"] != null) "CAD" else "USD"
            val rates = isoRates(c["rates"] ?: c["fx"])
            yearsOf(trades, zone, tpl).forEach { year ->
                val label = "${c["name"] ?: c["template"]} $method $year"
                rows += check(
                    Case(
                        label, trades, tpl, method, year, zone, currency, rates, priceAt(c["prices"]),
                        tpl.walletFrom(CostMethod.FIFO, year),
                    ),
                )
                cases++
            }
        }
        assertTrue("$cases Fälle", cases >= 100)
        assertTrue("$rows Zeilen", rows > cases)
    }

    @Test
    fun exportFixture() {
        val data = ParityJson.load("tax_export.json")
        val zone = ZoneId.of(data["zone"] as String)
        val currency = data["currency"] as String
        val rates = data["rates"].obj().entries.associate { it.key.toLong() to BigDecimal(it.value as String) }
        val trades = parseTrades(data["trades"])
        TaxTemplate.entries.forEach { tpl ->
            listOf(CostMethod.FIFO, CostMethod.AVERAGE, CostMethod.FIFO_WALLET).forEach { app ->
                yearsOf(trades, zone, tpl).forEach { year ->
                    val method = tpl.methodFor(app, year)
                    check(
                        Case(
                            "tax_export ${tpl.name} $method $year", trades, tpl, method, year, zone,
                            tpl.currency ?: currency, rates, null, tpl.walletFrom(app, year),
                        ),
                    )
                }
            }
        }
    }

    /** Daten der Leistungstests (kleiner): alle Vorlagen, frühe/mittlere/letzte Jahre, App-Methode FIFO und Ø. */
    @Test
    fun performanceData() {
        val trades = PortfolioPerformanceData.trades(PERFORMANCE_TRADES)
        val usdtUsd = PortfolioPerformanceData.usdtUsd()
        var rows = 0
        TaxTemplate.entries.forEach { tpl ->
            val currency = tpl.currency ?: "EUR"
            val rates = PortfolioPerformanceData.rates(currency)
            listOf(CostMethod.FIFO, CostMethod.AVERAGE).forEach { app ->
                listOf(2019, 2022, 2025).forEach { year ->
                    val method = tpl.methodFor(app, year)
                    rows += check(
                        Case(
                            "Leistung ${tpl.name} $app $year", trades, tpl, method, year, tpl.zone(UTC), currency, rates,
                            PortfolioPerformanceData::priceAt, tpl.walletFrom(app, year), usdtUsd,
                        ),
                    )
                }
            }
        }
        assertTrue("$rows Zeilen", rows > 1_000)
    }

    private companion object {
        const val PERFORMANCE_TRADES = 1_200
        val UTC: ZoneId = ZoneId.of("UTC")
        val TODAY: LocalDate = LocalDate.of(2026, 10, 24)
        val FORMAT = CutoffCsvFormat.SEMICOLON_POINT
        val HEADER = listOf("Arbeitsunterlage", "Keine Steuerberatung")

        val CSV_TEXTS = TaxCsvTexts(
            coin = "Coin", acquired = "Kauf", sold = "Verkauf", holdingDays = "Tage", longTerm = "> 1 Jahr",
            amount = "Menge", costUsdt = "Einstand USDT", proceedsUsdt = "Erlös USDT", gainUsdt = "Gewinn USDT",
            costTarget = "Einstand", proceedsTarget = "Erlös", gainTarget = "Gewinn", date = "Datum",
            valueUsdt = "Wert USDT", valueTarget = "Wert", note = "Hinweis", total = "Summe", yes = "ja", no = "nein",
            costMissing = "Kaufpreis fehlt", proceedsMissing = "Verkaufspreis fehlt", feeLeg = "Gebühr in anderem Coin",
            feeValueMissing = "ohne Wert der Gebühr", avgCostRates = "Einstand zu den Kursen der Kaufdaten",
            valueMissing = "Wert fehlt", noFx = "ohne Devisenkurs", incomplete = "unvollständig",
            oversold = "mehr verkauft als erfasst", proceedsWithoutCost = "Erlös ohne Kaufpreis", swap = "Tausch",
            wallet = "Wallet: %s", walletUnspecified = "ohne Angabe", lostFeeCost = "Netzwerkgebühr-Einstand",
            unassignedFee = "Gebühr nicht zugeordnet", matchSameDay = "gleicher Tag", matchThirtyDay = "30 Tage",
            matchPool = "Pool", feeConsumed = "Gebühr verbraucht", withdrawalFee = "Gebühr Auszahlung",
            feeRow = "Gebühr", gift = "Schenkung", payment = "Zahlung mit Krypto",
        )

        val SUMMARY_TEXTS = TaxSummaryTexts(
            title = "Jahresübersicht", disclaimer = "Orientierung", item = "Posten", usdt = "USDT", target = "Währung",
            note = "Hinweis", orientation = "Orientierung", labels = TaxLine.entries.associateWith { it.name },
            below = "unter der Grenze", reached = "erreicht", unknown = "unbekannt", incomplete = "unvollständig",
            noFx = "ohne Devisenkurs",
        )
    }
}
