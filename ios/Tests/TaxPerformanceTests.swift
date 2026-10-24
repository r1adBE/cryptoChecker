import XCTest
@testable import CryptoChecker

/// Steuer-Arbeitsunterlage eines grossen Portfolios (bis 10 000 Transaktionen über 2019–2025,
/// `PortfolioPerformanceData`) für das Steuerjahr 2025 je Vorlage NEUTRAL, CH, DE, UK, US — derselbe Ablauf wie
/// `PortfolioTaxExporter` ohne Netz: Devisenspanne (`TaxExport.GainsRun`), Jahresübersicht samt CSV, Gewinne-CSV mit
/// Netzwerkgebühr- und Gebühr-Vermerken, Einkünfte-CSV. Devisen-, USDT- und Tageskurse aus festen Tabellen. Grenzen und
/// Skalierung: `PerformanceTiming` mit `transactions` (Debug-Build, darum grosszügiger als Android). Wie
/// `TaxPerformanceTest.kt`.
final class TaxPerformanceTests: XCTestCase {
    /// Unter Thread Sanitizer (CI setzt `CC_SKIP_PERFORMANCE=1`) übersprungen: die Zeiten wären verfälscht.
    override func setUpWithError() throws {
        try super.setUpWithError()
        try PerformanceTiming.skipIfRequested()
    }

    private typealias T = PerformanceTiming
    private static let year = 2025
    private static let neutralCurrency = "EUR"
    private static let appMethod = CostMethod.FIFO
    private static let utc = TimeZone(identifier: "UTC") ?? .gmt
    private static let today = LocalDay(2026, 10, 24)
    private static let header = ["Arbeitsunterlage", "Keine Steuerberatung"]

    /// Ergebnis eines Exports (für die Prüfungen).
    private struct Export {
        let summary: TaxYearSummary
        let gainRows: Int
        let incomeRows: Int
        let chars: Int
    }

    /// Feste Eingaben je Grösse und Vorlage (nicht gemessen).
    private struct Inputs {
        let trades: [PortfolioTx]
        let rates: [Int: Decimal]
        let usdtUsd: [Int: Decimal]
    }

    private static func currency(_ template: TaxTemplate) -> String {
        template.currency ?? TaxPerformanceTests.neutralCurrency
    }

    private static func inputs(_ n: Int, _ template: TaxTemplate) -> Inputs {
        Inputs(trades: PortfolioPerformanceData.trades(n),
               rates: PortfolioPerformanceData.rates(TaxPerformanceTests.currency(template)),
               usdtUsd: PortfolioPerformanceData.usdtUsd())
    }

    /// Alle Dateien der Vorlage `template` für `year`, wie `PortfolioTaxExporter.summary`/`build`.
    private static func export(_ template: TaxTemplate, _ input: Inputs) -> Export {
        typealias S = TaxPerformanceTests
        let trades = input.trades
        let zone = template.zone(device: S.utc)
        let method = template.methodFor(S.appMethod, S.year)
        let currency = S.currency(template)
        let period = template.period(S.year)
        let until = TaxExport.priceUntil(template, period: period, today: S.today)
        let priceAt: (String, Int64) -> Decimal? = { PortfolioPerformanceData.priceAt($0, $1) }
        let walletFrom = template.walletFrom(S.appMethod, S.year)
        let rules = template.eventRules
        // Übersicht und Gewinne-Export rechnen je ihre Veräusserungen einmal (`GainsRun`) wie `PortfolioTaxExporter`
        let summaryRun = TaxExport.GainsRun(trades, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                            rules: rules, walletFrom: walletFrom)
        let span = summaryRun.span(until: until, today: S.today)
        let summary = TaxExport.yearSummary(trades, template: template, method: method, startYear: S.year,
                                            timeZone: zone, currency: currency, rates: input.rates, priceAt: priceAt,
                                            walletFrom: walletFrom, usdtUsd: input.usdtUsd, today: S.today,
                                            run: summaryRun)
        let summaryCsv = TaxExport.summaryCsv(summary, texts: S.summaryTexts, header: S.header,
                                              zoneLine: "Zone \(zone.identifier)", format: .semicolonPoint)
        let gainsRun = TaxExport.GainsRun(trades, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                          rules: rules, walletFrom: walletFrom)
        _ = gainsRun.span(until: until, today: S.today)
        let rows = gainsRun.gainRows(currency: currency, rates: input.rates, usdtUsd: input.usdtUsd)
        let notes = TaxExport.feeNotes(in: trades, method: method, period: period, timeZone: zone, rules: rules)
        let gainsCsv = TaxExport.gainsCsv(rows, texts: S.csvTexts, header: S.header, format: .semicolonPoint,
                                          lostFeeCosts: notes.lostFeeCosts, timeZone: zone,
                                          unassignedFees: notes.unassignedFees)
        let income = TaxExport.incomeRows(trades, period: period, timeZone: zone, currency: currency,
                                          rates: input.rates, priceAt: priceAt, usdtUsd: input.usdtUsd)
        let incomeCsv = TaxExport.incomeCsv(income, texts: S.csvTexts, header: S.header, format: .semicolonPoint)
        let chars = span.from.description.count + summaryCsv.utf8.count + gainsCsv.utf8.count + incomeCsv.utf8.count
        return Export(summary: summary, gainRows: rows.count, incomeRows: income.count, chars: chars)
    }

    /// Nur die feste Grenze (Verhältnis klein/gross steht im Protokoll): Der Export wächst gemessen etwas schneller als
    /// n·log n (Android-CI: NEUTRAL/CH ×5,9 bei vierfacher Menge) — FIFO-Posten und Vermerke je Coin über die ganze
    /// Geschichte. Wie `TaxPerformanceTest.kt`; steht auf der Performance-Liste.
    private func measureExport(_ template: TaxTemplate, limitMillis: Double, file: StaticString = #filePath,
                               line: UInt = #line) {
        T.assertLimitOnly("Steuer-Export \(template.rawValue) \(TaxPerformanceTests.year)", limitMillis: limitMillis,
                       sizes: T.transactions, file: file, line: line) { n in
            let input = TaxPerformanceTests.inputs(n, template)
            return T.medianMillis { TaxPerformanceTests.export(template, input).chars }
        }
    }

    /// Plausibel für 10 000 Transaktionen: Methode der Vorlage, Zeilen, Posten, Devisenkurse vorhanden.
    @discardableResult
    private func check(_ template: TaxTemplate, _ method: TaxMethod, file: StaticString = #filePath,
                       line: UInt = #line) -> Export {
        let e = TaxPerformanceTests.export(template, TaxPerformanceTests.inputs(T.transactions.large, template))
        let name = template.rawValue
        XCTAssertEqual(e.summary.template, template, file: file, line: line)
        XCTAssertEqual(e.summary.method, method, file: file, line: line)
        XCTAssertTrue(e.summary.fxAvailable, "\(name): Devisenkurse", file: file, line: line)
        XCTAssertGreaterThanOrEqual(e.gainRows, 100, "\(name): Gewinn-Zeilen", file: file, line: line)
        XCTAssertGreaterThanOrEqual(e.incomeRows, 50, "\(name): Einkünfte", file: file, line: line)
        XCTAssertFalse(e.summary.lines.isEmpty, "\(name): Posten", file: file, line: line)
        XCTAssertGreaterThan(e.chars, 10_000, file: file, line: line)
        return e
    }

    func testNeutral() {
        // FIFO über alle Wallets, Anzeigewährung EUR: erwartet rund 1,5–4 s (Debug; mehrere Durchgänge über alle
        // Transaktionen: Übersicht samt Spanne, Gewinne samt Spanne, Vermerke)
        measureExport(.NEUTRAL, limitMillis: 30_000)
        XCTAssertNil(check(.NEUTRAL, .FIFO).summary.walletFrom)
    }

    func testSwitzerland() {
        // FIFO, CHF, Vermögenswert ohne Tagesschlüsse des 31.12. («nicht gerechnet»): erwartet rund 1,5–4 s (Debug)
        measureExport(.CH, limitMillis: 30_000)
        check(.CH, .FIFO)
    }

    func testGermany() {
        // FIFO je Wallet (BMF 2025), EUR, Freigrenzen: erwartet rund 1,5–5 s (Debug)
        measureExport(.DE, limitMillis: 30_000)
        check(.DE, .FIFO_WALLET)
    }

    func testUnitedKingdom() {
        // Share Pooling: der Section-104-Pool führt je Kalendertag mit Zugang einen Herkunftsteil (TaxPool mit
        // Zeitzone); ein Abgang merkt sich nur seinen Anteil (`PortfolioCostPartPool`, O(1) bzw. O(log n)), und die
        // Zeilen rechnen ihren Einstand in der Steuerwährung aus der laufenden Summe des Pools (`weightedSum`) statt
        // Teil für Teil.
        // Erwartet rund 1,5–5 s für 10 000 Transaktionen (Debug)
        T.assertScales("Steuer-Export UK \(TaxPerformanceTests.year)", limitMillis: 30_000,
                       sizes: T.transactions) { n in
            let input = TaxPerformanceTests.inputs(n, .UK)
            // Sekunden je Lauf: 1 Aufwärm- und 3 Messläufe
            return T.medianMillis(warmup: T.quickWarmupRuns, runs: T.quickMeasuredRuns) {
                TaxPerformanceTests.export(.UK, input).chars
            }
        }
        check(.UK, .UK_POOL)
    }

    func testUnitedStates() {
        // FIFO je Wallet ab 2025 mit Übergang (universelles FIFO bis 31.12.2024, Restposten den Wallets zugeordnet),
        // USD: erwartet rund 1,5–5 s (Debug)
        measureExport(.US, limitMillis: 30_000)
        XCTAssertEqual(check(.US, .FIFO_WALLET).summary.walletFrom, LocalDay(2025, 1, 1))
    }

    private static let csvTexts = TaxCsvTexts(
        coin: "Coin", acquired: "Kauf", sold: "Verkauf", holdingDays: "Tage", longTerm: "> 1 Jahr", amount: "Menge",
        costUsdt: "Einstand USDT", proceedsUsdt: "Erlös USDT", gainUsdt: "Gewinn USDT", costTarget: "Einstand",
        proceedsTarget: "Erlös", gainTarget: "Gewinn", date: "Datum", valueUsdt: "Wert USDT", valueTarget: "Wert",
        note: "Hinweis", total: "Summe", yes: "ja", no: "nein", costMissing: "Kaufpreis fehlt",
        proceedsMissing: "Verkaufspreis fehlt", feeLeg: "Gebühr in anderem Coin",
        feeValueMissing: "ohne Wert der Gebühr",
        avgCostRates: "Einstand zu den Kursen der Kaufdaten", valueMissing: "Wert fehlt", noFx: "ohne Devisenkurs",
        incomplete: "unvollständig", oversold: "mehr verkauft als erfasst", proceedsWithoutCost: "Erlös ohne Kaufpreis",
        swap: "Tausch", wallet: "Wallet: %s", walletUnspecified: "ohne Angabe", lostFeeCost: "Netzwerkgebühr-Einstand",
        unassignedFee: "Gebühr nicht zugeordnet", matchSameDay: "gleicher Tag", matchThirtyDay: "30 Tage",
        matchPool: "Pool", feeConsumed: "Gebühr verbraucht", withdrawalFee: "Gebühr Auszahlung", feeRow: "Gebühr",
        gift: "Schenkung", payment: "Zahlung mit Krypto")

    private static let summaryTexts: TaxSummaryTexts = {
        var labels: [TaxLine: String] = [:]
        for l in TaxLine.allCases { labels[l] = l.rawValue }
        return TaxSummaryTexts(title: "Jahresübersicht", disclaimer: "Orientierung", item: "Posten", usdt: "USDT",
                               target: "Währung", note: "Hinweis", orientation: "Orientierung", labels: labels,
                               below: "unter der Grenze", reached: "erreicht", unknown: "unbekannt",
                               incomplete: "unvollständig", noFx: "ohne Devisenkurs")
    }()
}
