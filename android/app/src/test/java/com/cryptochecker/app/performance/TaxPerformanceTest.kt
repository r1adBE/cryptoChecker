package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.portfolio.CutoffCsvFormat
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.TaxCsvTexts
import com.cryptochecker.app.domain.portfolio.TaxExport
import com.cryptochecker.app.domain.portfolio.TaxLine
import com.cryptochecker.app.domain.portfolio.TaxMethod
import com.cryptochecker.app.domain.portfolio.TaxSummaryTexts
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.domain.portfolio.TaxYearSummary
import com.cryptochecker.app.domain.portfolio.gainRows
import com.cryptochecker.app.domain.portfolio.gainsCsv
import com.cryptochecker.app.domain.portfolio.incomeCsv
import com.cryptochecker.app.domain.portfolio.incomeRows
import com.cryptochecker.app.domain.portfolio.summaryCsv
import com.cryptochecker.app.domain.portfolio.yearSummary
import com.cryptochecker.app.performance.PerformanceTiming.TRANSACTIONS
import com.cryptochecker.app.performance.PerformanceTiming.assertLimitOnly
import com.cryptochecker.app.performance.PerformanceTiming.assertScales
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Steuer-Arbeitsunterlage eines grossen Portfolios (bis 10 000 Transaktionen über 2019–2025,
 * [PortfolioPerformanceData]) für das Steuerjahr 2025 je Vorlage NEUTRAL, CH, DE, UK, US — derselbe Ablauf wie
 * `PortfolioExportViewModel` ohne Netz: Devisenspanne ([TaxExport.fxSpan]), Jahresübersicht samt CSV, Gewinne-CSV mit
 * Netzwerkgebühr- und Gebühr-Vermerken, Einkünfte-CSV. Devisen-, USDT- und Tageskurse aus festen Tabellen. Grenzen und
 * Skalierung: [PerformanceTiming] mit [TRANSACTIONS]. iOS: TaxPerformanceTests.swift.
 */
class TaxPerformanceTest {

    /** Ergebnis eines Exports (für die Prüfungen und gegen das Wegoptimieren). */
    private class Export(val summary: TaxYearSummary, val gainRows: Int, val incomeRows: Int, val chars: Int)

    /** Feste Eingaben je Grösse und Vorlage (nicht gemessen). */
    private class Inputs(
        val trades: List<PortfolioTrade>,
        val rates: Map<Long, BigDecimal>,
        val usdtUsd: Map<Long, BigDecimal>,
    )

    private fun inputs(n: Int, template: TaxTemplate) = Inputs(
        PortfolioPerformanceData.trades(n), PortfolioPerformanceData.rates(currency(template)),
        PortfolioPerformanceData.usdtUsd(),
    )

    private fun currency(template: TaxTemplate): String = template.currency ?: NEUTRAL_CURRENCY

    /** Alle Dateien der Vorlage [template] für [YEAR], wie `PortfolioExportViewModel.buildSummary`/`buildTax`. */
    private fun export(template: TaxTemplate, input: Inputs): Export {
        val trades = input.trades
        val zone = template.zone(UTC)
        val method = template.methodFor(APP_METHOD, YEAR)
        val currency = currency(template)
        val period = template.period(YEAR)
        val until = TaxExport.priceUntil(template, period, TODAY)
        val priceAt = PortfolioPerformanceData::priceAt
        val walletFrom = template.walletFrom(APP_METHOD, YEAR)
        val rules = template.eventRules
        val span = TaxExport.fxSpan(trades, method, period, zone, priceAt, rules, walletFrom, until, TODAY)
        val summary = TaxExport.yearSummary(
            trades, template, method, YEAR, zone, currency, input.rates, priceAt, walletFrom, input.usdtUsd,
            today = TODAY,
        )
        val summaryCsv = TaxExport.summaryCsv(summary, SUMMARY_TEXTS, HEADER, "Zone ${zone.id}", FORMAT)
        val rows = TaxExport.gainRows(
            trades, method, period, zone, currency, input.rates, priceAt, rules = rules, walletFrom = walletFrom,
            usdtUsd = input.usdtUsd,
        )
        val gainsCsv = TaxExport.gainsCsv(
            rows, CSV_TEXTS, HEADER, FORMAT,
            lostFeeCosts = TaxExport.lostFeeCostsIn(trades, method, period, zone, rules),
            zone = zone,
            unassignedFees = TaxExport.unassignedFeesIn(trades, method, period, zone, rules),
        )
        val income = TaxExport.incomeRows(trades, period, zone, currency, input.rates, priceAt, input.usdtUsd)
        val incomeCsv = TaxExport.incomeCsv(income, CSV_TEXTS, HEADER, FORMAT)
        val chars = span.first.toString().length + summaryCsv.length + gainsCsv.length + incomeCsv.length
        return Export(summary, rows.size, income.size, chars)
    }

    private fun measure(template: TaxTemplate, limitMillis: Double) {
        assertScales("Steuer-Export ${template.name} $YEAR", limitMillis, TRANSACTIONS) { n ->
            val input = inputs(n, template)
            medianMillis { export(template, input) }
        }
    }

    /** Plausibel für 10 000 Transaktionen: Methode der Vorlage, Zeilen, Posten, Devisenkurse vorhanden. */
    private fun check(template: TaxTemplate, method: TaxMethod): Export {
        val e = export(template, inputs(TRANSACTIONS.large, template))
        assertEquals(template, e.summary.template)
        assertEquals(method, e.summary.method)
        assertTrue("${template.name}: Devisenkurse", e.summary.fxAvailable)
        assertTrue("${template.name}: ${e.gainRows} Gewinn-Zeilen", e.gainRows >= 100)
        assertTrue("${template.name}: ${e.incomeRows} Einkünfte", e.incomeRows >= 50)
        assertTrue("${template.name}: Posten", e.summary.lines.isNotEmpty())
        assertTrue(e.chars > 10_000)
        return e
    }

    @Test
    fun neutral() {
        // FIFO über alle Wallets, Anzeigewährung EUR: erwartet rund 300–800 ms (mehrere Durchgänge über alle
        // Transaktionen: Spanne, Übersicht, Gewinne, Vermerke)
        measure(TaxTemplate.NEUTRAL, 5_000.0)
        assertNull(check(TaxTemplate.NEUTRAL, TaxMethod.FIFO).summary.walletFrom)
    }

    @Test
    fun switzerland() {
        // FIFO, CHF, Vermögenswert ohne Tagesschlüsse des 31.12. («nicht gerechnet»): erwartet rund 300–800 ms
        measure(TaxTemplate.CH, 5_000.0)
        check(TaxTemplate.CH, TaxMethod.FIFO)
    }

    @Test
    fun germany() {
        // FIFO je Wallet (BMF 2025), EUR, Freigrenzen: erwartet rund 300–900 ms
        measure(TaxTemplate.DE, 5_000.0)
        check(TaxTemplate.DE, TaxMethod.FIFO_WALLET)
    }

    @Test
    fun unitedKingdom() {
        // Share Pooling: wächst bekanntlich schneller als linear — der Section-104-Pool führt je Kalendertag mit Zugang
        // einen Herkunftsteil (TaxPool mit Zeitzone), und jeder Abgang nimmt aus ALLEN Teilen anteilig (`takeParts`),
        // dazu trägt jede Zeile alle Teile (Devisenkurs je Teil). Zugänge (bis zur Zahl der Tage) × Abgänge je Coin,
        // fünfmal je Export (Spanne, Übersicht, Gewinne, zwei Vermerk-Listen). Nur die feste Grenze; erwartet rund
        // 2–6 s für 10 000 Transaktionen (Verhältnis zu 2500 etwa ×10–16)
        assertLimitOnly("Steuer-Export UK $YEAR", 40_000.0, TRANSACTIONS) { n ->
            val input = inputs(n, TaxTemplate.UK)
            medianMillis { export(TaxTemplate.UK, input) }
        }
        check(TaxTemplate.UK, TaxMethod.UK_POOL)
    }

    @Test
    fun unitedStates() {
        // FIFO je Wallet ab 2025 mit Übergang (universelles FIFO bis 31.12.2024, Restposten den Wallets zugeordnet),
        // USD: erwartet rund 300–900 ms
        measure(TaxTemplate.US, 5_000.0)
        assertEquals(LocalDate.of(2025, 1, 1), check(TaxTemplate.US, TaxMethod.FIFO_WALLET).summary.walletFrom)
    }

    private companion object {
        const val YEAR = 2025
        const val NEUTRAL_CURRENCY = "EUR"
        val APP_METHOD = CostMethod.FIFO
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
