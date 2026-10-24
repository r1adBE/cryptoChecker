import XCTest
@testable import CryptoChecker

/// Gewinne-Export und Jahresübersicht aus einer Rechnung (`TaxExport.GainsRun`, `TaxExport.feeNotes`) liefern EXAKT
/// dieselben Spannen, Zeilen und CSV-Texte wie die bisherigen Einzelrechnungen (`fxSpan`, `gainRows`, `lostFeeCosts`,
/// `unassignedFees`, `yearSummary` ohne `run`) — für die Vorlagen-Fälle aus `tax_templates.json` und ein grösseres
/// Portfolio (`PortfolioPerformanceData`) über mehrere Jahre, Methoden und Vorlagen (u. a. UK, CA, FR, DE, US).
final class TaxExportRunTests: XCTestCase {

    private typealias S = TestSupport

    private static let header = ["Arbeitsunterlage", "Keine Steuerberatung"]

    /// Ergebnis eines Exports: Devisenspanne, Gewinne-Zeilen, Gewinne-CSV, Jahresübersicht-CSV.
    private struct Files: Equatable {
        let from: LocalDay
        let until: LocalDay
        let rows: [TaxGainRow]
        let gainsCsv: String
        let summaryCsv: String
    }

    private struct Input {
        let trades: [PortfolioTx]
        let template: TaxTemplate
        let method: TaxMethod
        let year: Int
        let zone: TimeZone
        let currency: String
        let rates: [Int: Decimal]?
        let priceAt: ((String, Int64) -> Decimal?)?
        let usdtUsd: [Int: Decimal]?
        let today: LocalDay

        var period: TaxPeriod { template.period(year) }
        var until: LocalDay { TaxExport.priceUntil(template, period: period, today: today) }
        var walletFrom: LocalDay? { template.walletFrom(method.costMethod ?? .defaultMethod, year) }
    }

    /// Bisheriger Ablauf: jede Liste mit ihrer eigenen Rechnung.
    private static func separate(_ i: Input) -> Files {
        let rules = i.template.eventRules
        let span = TaxExport.fxSpan(i.trades, method: i.method, period: i.period, timeZone: i.zone, priceAt: i.priceAt,
                                    rules: rules, walletFrom: i.walletFrom, until: i.until, today: i.today)
        let rows = TaxExport.gainRows(i.trades, method: i.method, period: i.period, timeZone: i.zone,
                                      currency: i.currency, rates: i.rates, priceAt: i.priceAt, rules: rules,
                                      walletFrom: i.walletFrom, usdtUsd: i.usdtUsd)
        let lost = TaxExport.lostFeeCosts(in: i.trades, method: i.method, period: i.period, timeZone: i.zone,
                                          rules: rules)
        let unassigned = TaxExport.unassignedFees(in: i.trades, method: i.method, period: i.period, timeZone: i.zone,
                                                  rules: rules)
        let summary = TaxExport.yearSummary(i.trades, template: i.template, method: i.method, startYear: i.year,
                                            timeZone: i.zone, currency: i.currency, rates: i.rates, priceAt: i.priceAt,
                                            walletFrom: i.walletFrom, usdtUsd: i.usdtUsd, today: i.today)
        return files(span, rows, lost, unassigned, summary, i)
    }

    /// Neuer Ablauf: Veräusserungen einmal (`GainsRun`), Vermerke aus einer Rechnung (`feeNotes`).
    private static func shared(_ i: Input) -> Files {
        let rules = i.template.eventRules
        let run = TaxExport.GainsRun(i.trades, method: i.method, period: i.period, timeZone: i.zone,
                                     priceAt: i.priceAt, rules: rules, walletFrom: i.walletFrom)
        let span = run.span(until: i.until, today: i.today)
        let rows = run.gainRows(currency: i.currency, rates: i.rates, usdtUsd: i.usdtUsd)
        let notes = TaxExport.feeNotes(in: i.trades, method: i.method, period: i.period, timeZone: i.zone, rules: rules)
        let summary = TaxExport.yearSummary(i.trades, template: i.template, method: i.method, startYear: i.year,
                                            timeZone: i.zone, currency: i.currency, rates: i.rates, priceAt: i.priceAt,
                                            walletFrom: i.walletFrom, usdtUsd: i.usdtUsd, today: i.today, run: run)
        return files(span, rows, notes.lostFeeCosts, notes.unassignedFees, summary, i)
    }

    private static func files(_ span: (from: LocalDay, until: LocalDay), _ rows: [TaxGainRow],
                              _ lost: [PortfolioLostFeeCost], _ unassigned: [PortfolioUnassignedFee],
                              _ summary: TaxYearSummary, _ i: Input) -> Files {
        let gains = TaxExport.gainsCsv(rows, texts: csvTexts, header: header, format: .semicolonPoint,
                                       lostFeeCosts: lost, timeZone: i.zone, unassignedFees: unassigned)
        let summaryCsv = TaxExport.summaryCsv(summary, texts: summaryTexts, header: header,
                                              zoneLine: "Zone \(i.zone.identifier)", format: .semicolonPoint)
        return Files(from: span.from, until: span.until, rows: rows, gainsCsv: gains, summaryCsv: summaryCsv)
    }

    private func assertSame(_ i: Input, file: StaticString = #filePath, line: UInt = #line) {
        let name = "\(i.template.rawValue) \(i.method.rawValue) \(i.year) \(i.currency)"
        let old = TaxExportRunTests.separate(i)
        let new = TaxExportRunTests.shared(i)
        XCTAssertEqual(new.from, old.from, "\(name) Spanne von", file: file, line: line)
        XCTAssertEqual(new.until, old.until, "\(name) Spanne bis", file: file, line: line)
        XCTAssertEqual(new.rows, old.rows, "\(name) Zeilen", file: file, line: line)
        XCTAssertEqual(new.gainsCsv, old.gainsCsv, "\(name) Gewinne-CSV", file: file, line: line)
        XCTAssertEqual(new.summaryCsv, old.summaryCsv, "\(name) Übersicht-CSV", file: file, line: line)
    }

    /// Alle Übersicht-Fälle aus `tax_templates.json`: Methode des Falls und die Einstandsmethoden, Vorjahr, Jahr und
    /// Folgejahr, mit und ohne Devisenkurse.
    func testTemplateCasesSameAsSeparate() throws {
        let d = try S.fixture("tax_templates")
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        var checked = 0
        for c in S.list(d["summaries"]) {
            let tpl = try XCTUnwrap(TaxTemplate(rawValue: c["template"] as? String ?? ""))
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            let year = Int(int64(c["year"]))
            let txs = trades(c["trades"])
            let today = (c["today"] as? String).flatMap { LocalDay(iso: $0) } ?? LocalDay(2026, 10, 24)
            let methods = [method, .FIFO, .FIFO_WALLET, .AVERAGE]
            for m in methods {
                for y in [year - 1, year, year + 1] {
                    for withRates in [true, false] {
                        assertSame(Input(trades: txs, template: tpl, method: m, year: y, zone: zone,
                                         currency: c["currency"] as? String ?? tpl.currency ?? "USD",
                                         rates: withRates ? rates(c["rates"]) : nil, priceAt: priceAt(c["prices"]),
                                         usdtUsd: nil, today: today))
                        checked += 1
                    }
                }
            }
        }
        XCTAssertGreaterThan(checked, 500)
    }

    /// Grösseres Portfolio (`PortfolioPerformanceData`): jede Vorlage mit ihrer Methode (App FIFO und Ø), drei Jahre,
    /// Devisen- und USDT/USD-Kurse aus den festen Tabellen.
    func testPerformanceDataSameAsSeparate() {
        let trades = PortfolioPerformanceData.trades(800)
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        let priceAt: (String, Int64) -> Decimal? = { PortfolioPerformanceData.priceAt($0, $1) }
        let usdtUsd = PortfolioPerformanceData.usdtUsd()
        let templates: [TaxTemplate] = [.NEUTRAL, .CH, .DE, .UK, .US, .CA, .FR, .AT, .JP, .AU]
        for tpl in templates {
            let currency = tpl.currency ?? "EUR"
            let rates = PortfolioPerformanceData.rates(currency)
            for app in [CostMethod.FIFO, .AVERAGE] {
                for year in [2021, 2023, 2025] {
                    assertSame(Input(trades: trades, template: tpl, method: tpl.methodFor(app, year), year: year,
                                     zone: tpl.zone(device: utc), currency: currency, rates: rates, priceAt: priceAt,
                                     usdtUsd: usdtUsd, today: LocalDay(2026, 10, 24)))
                }
            }
        }
    }

    /// CA (Superficial Loss in CAD): fehlende Kurse an manchen Tagen ändern die Rechnung mit Kursen — die zweite
    /// Rechnung in `GainsRun.gainRows` muss sie genau wie bisher sehen.
    func testCanadaWithGapsSameAsSeparate() {
        let trades = PortfolioPerformanceData.trades(800)
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        var rates = PortfolioPerformanceData.rates("CAD")
        // Lücken von 9 Tagen (länger als `TaxExport.fxMaxGapDays`): an manchen Tagen fehlt der Kurs ganz
        for day in rates.keys where day % 30 < 9 { rates[day] = nil }
        for year in [2022, 2024, 2025] {
            assertSame(Input(trades: trades, template: .CA, method: .CA_ACB, year: year, zone: TaxTemplate.CA.zone(device: utc),
                             currency: "CAD", rates: rates, priceAt: { PortfolioPerformanceData.priceAt($0, $1) },
                             usdtUsd: nil, today: LocalDay(2026, 10, 24)))
        }
    }

    /// `yearSummary(run:)` prüft in Debug, dass `run` aus denselben Eingaben stammt (`GainsRun.matches`).
    func testRunMatchesOnlyItsOwnInputs() {
        let utc = TimeZone(identifier: "UTC") ?? .gmt
        let period = TaxTemplate.DE.period(2024)
        let rules = TaxTemplate.DE.eventRules
        let wallet = LocalDay(2024, 1, 1)
        let run = TaxExport.GainsRun([], method: .FIFO, period: period, timeZone: utc, rules: rules, walletFrom: wallet)
        XCTAssertTrue(run.matches(method: .FIFO, period: period, timeZone: utc, rules: rules, walletFrom: wallet))
        XCTAssertFalse(run.matches(method: .AVERAGE, period: period, timeZone: utc, rules: rules, walletFrom: wallet))
        let other = TaxTemplate.DE.period(2025)
        XCTAssertFalse(run.matches(method: .FIFO, period: other, timeZone: utc, rules: rules, walletFrom: wallet))
        XCTAssertFalse(run.matches(method: .FIFO, period: period, timeZone: utc, rules: rules, walletFrom: nil))
        XCTAssertFalse(run.matches(method: .FIFO, period: period, timeZone: utc, rules: nil, walletFrom: wallet))
    }

    // MARK: - Fixture

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func trades(_ raw: Any?) -> [PortfolioTx] {
        S.list(raw).map { (t: [String: Any]) -> PortfolioTx in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]), time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.toCoin = t["toCoin"] as? String
            tx.toAmountExact = dec(t["toAmount"])
            tx.swapValueExact = dec(t["swapValue"])
            tx.feeCoin = t["feeCoin"] as? String
            tx.feeCoinAmountExact = dec(t["feeCoinAmount"])
            tx.feeCoinPriceExact = dec(t["feeCoinPrice"])
            tx.exchange = t["exchange"] as? String
            tx.transferFrom = t["transferFrom"] as? String
            tx.transferTo = t["transferTo"] as? String
            tx.giftToSpouse = t["giftToSpouse"] as? Bool ?? false
            tx.acquiredTime = TestSupport.number(t["acquiredTime"]).map { Int64($0) }
            tx.importKey = t["importKey"] as? String
            tx.note = t["note"] as? String
            tx.feeRow = t["feeRow"] as? Bool
                ?? LegacyStorageMigration.legacyFeeRow(tx.type, importKey: tx.importKey, price: tx.priceExact)
            tx.interest = t["interest"] as? Bool ?? LegacyStorageMigration.legacyInterest(tx.type, note: tx.note)
            return tx
        }
    }

    private func rates(_ raw: Any?) -> [Int: Decimal]? {
        guard let map = raw as? [String: Any] else { return nil }
        var out: [Int: Decimal] = [:]
        for (k, v) in map {
            if let d = LocalDay(iso: k), let r = dec(v) { out[d.epochDay] = r }
        }
        return out
    }

    private func priceAt(_ raw: Any?) -> ((String, Int64) -> Decimal?)? {
        guard let map = raw as? [String: Any] else { return nil }
        var prices: [String: Decimal] = [:]
        for (k, v) in map { prices[k] = dec(v) }
        return { coin, _ in prices[coin] }
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
