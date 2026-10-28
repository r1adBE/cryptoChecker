import XCTest
@testable import CryptoChecker

/// Bestand je Einstandsmethode überall gleich (`Parity/holdings_method.json`) — wie `HoldingsMethodParityTest.kt`:
/// Karte, Stichtag, «7 T» und Wertverlauf rechnen mit denselben Mengen (FIFO je Wallet je Wallet gekappt).
final class HoldingsMethodParityTests: XCTestCase {

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private func trade(_ raw: Any) -> PortfolioTx {
        let o = raw as? [String: Any] ?? [:]
        var t = PortfolioTx(id: Int64(TestSupport.number(o["id"]) ?? 0),
                            coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"] as? String ?? "0"),
                            priceExact: (o["price"] as? String).map(dec),
                            time: Int64(TestSupport.number(o["time"]) ?? 0),
                            feeExact: (o["fee"] as? String).map(dec),
                            feeUnit: (o["feeUnit"] as? String).flatMap(PortfolioFeeUnit.init(rawValue:)))
        t.feeCoin = o["feeCoin"] as? String
        t.feeCoinAmountExact = (o["feeCoinAmount"] as? String).map(dec)
        t.feeCoinPriceExact = (o["feeCoinPrice"] as? String).map(dec)
        t.exchange = o["exchange"] as? String
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        t.toCoin = o["toCoin"] as? String
        t.toAmountExact = (o["toAmount"] as? String).map(dec)
        t.swapValueExact = (o["swapValue"] as? String).map(dec)
        t.importKey = o["importKey"] as? String
        // Gebühr-Zeile: eigenes Feld; fehlt = nein
        t.feeRow = o["feeRow"] as? Bool ?? false
        return t
    }

    /// Karte: eigener Bestand je offenem Coin nach `method`.
    private func card(_ trades: [PortfolioTx], _ method: CostMethod) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for coin in PortfolioLedger.coins(trades) {
            let h = PortfolioCalculator.position(coin, trades: trades, currentPrice: nil, method: method).ledgerHoldingsExact
            if h > 0 { out[coin] = h }
        }
        return out
    }

    private func assertHoldings(_ label: String, _ expected: [String: Any], _ actual: [String: Decimal]) {
        XCTAssertEqual(expected.keys.sorted(), actual.keys.sorted(), "\(label) coins")
        for (coin, text) in expected {
            XCTAssertEqual(actual[coin], dec(text as? String ?? ""), "\(label) \(coin)")
        }
    }

    private func map(_ list: [CutoffHolding]) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for h in list { out[h.coin] = h.amount }
        return out
    }

    /// Exakte Beträge bis auf Rundung der letzten Stellen (Android rechnet mit 34 Stellen).
    private func assertNear(_ label: String, _ expected: Any?, _ actual: Decimal) {
        let diff: Decimal = dec(expected as? String ?? "") - actual
        let small: Bool = abs(diff) < Decimal(string: "1e-20", locale: Locale(identifier: "en_US_POSIX"))!
        XCTAssertTrue(small, "\(label) \(actual)")
    }

    func testSameHoldingsEverywhere() throws {
        let cases = TestSupport.list(try TestSupport.fixture("holdings_method")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 2)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            let today = Int(TestSupport.number(c["today"]) ?? 0)
            var closes: [String: [Int: Double]] = [:]
            for (coin, v) in c["closes"] as? [String: Any] ?? [:] {
                var byDay: [Int: Double] = [:]
                for (k, x) in v as? [String: Any] ?? [:] { if let d = Int(k), let n = TestSupport.number(x) { byDay[d] = n } }
                closes[coin] = byDay
            }
            let live = (c["live"] as? [String: Any] ?? [:]).compactMapValues { TestSupport.number($0) }
            for (m, spec) in c["methods"] as? [String: Any] ?? [:] {
                guard let method = CostMethod(rawValue: m), let s = spec as? [String: Any] else { continue }
                let label = "\(name)/\(m)"
                let expected = s["holdings"] as? [String: Any] ?? [:]
                assertHoldings("\(label) card", expected, card(trades, method))
                var direct: [String: Decimal] = [:]
                for h in PortfolioCalculator.holdingsAt(trades, method: method) { direct[h.coin] = h.amount }
                assertHoldings("\(label) holdingsAt", expected, direct)
                assertHoldings("\(label) cutoff", expected,
                               map(CutoffExport.holdingsAt(trades, cutoffMillis: .max, method: method)))
                let gain = PortfolioPeriodChange.gain(trades: trades, current: live, closes: closes,
                                                      baseDay: today - PortfolioPeriodChange.days, method: method)
                XCTAssertEqual(gain?.amount ?? .nan, TestSupport.number(s["weekGain"]) ?? 0, accuracy: 1e-9, "\(label) weekGain")
                let series = PortfolioHistory.build(trades: trades, closes: closes, livePrices: live, range: .week,
                                                    todayEpochDay: today, method: method)
                XCTAssertEqual(series.points.last?.value ?? .nan, TestSupport.number(s["historyLast"]) ?? 0,
                               accuracy: 1e-9, "\(label) historyLast")
                XCTAssertEqual(series.pnlChange ?? .nan, TestSupport.number(s["historyPnl"]) ?? 0,
                               accuracy: 1e-9, "\(label) historyPnl")
                // Gebühr-Zeilen zum Tagesschluss (gleicher Kurs wie der Abfluss im Verlauf)
                let feePrice: (String, Int64) -> Decimal? = { coin, time in
                    PortfolioPeriodChange.marketPriceAt(coin, live: live[coin], closes: closes, time: time)
                        .flatMap { PortfolioDecimal.of($0) }
                }
                let valued = PortfolioLedger.valueFeeDisposals(trades, price: feePrice)
                // Steuer-Export und «realisiert» mit derselben Aufteilung des Erlöses wie «7 T»/Wertverlauf
                for (coin, d) in s["disposals"] as? [String: Any] ?? [:] {
                    let x = d as? [String: Any] ?? [:]
                    let list: [PortfolioDisposal] = PortfolioCalculator.disposals(valued, method: method).filter { $0.coin == coin }
                    let covered: Decimal = list.filter { !$0.uncovered }.reduce(Decimal(0)) { $0 + ($1.proceedsUsdt ?? 0) }
                    let uncovered: Decimal = list.filter { $0.uncovered }.reduce(Decimal(0)) { $0 + ($1.proceedsUsdt ?? 0) }
                    let position = PortfolioCalculator.ledger(coin, trades: valued, currentPrice: nil, method: method).position
                    // Karte bewertet Gebühr-Zeilen selbst (withdrawalPrice) — gleiche Zahl
                    let card = PortfolioCalculator.summarize(trades, prices: live, method: method) { c2, t2 in
                        PortfolioPeriodChange.marketPriceAt(c2, live: live[c2], closes: closes, time: t2)
                    }
                    let cardRealized: Decimal = (card.open + card.closed).first(where: { $0.coin == coin })?.realizedExact ?? -1
                    assertNear("\(label) \(coin) card realized", x["realized"], cardRealized)
                    // Coin-Ansicht bewertet Gebühr-Zeilen ebenso (ledger feePrice) — gleiche Zahl
                    let detail = PortfolioCalculator.ledger(coin, trades: trades, currentPrice: live[coin], method: method) { c2, t2 in
                        PortfolioPeriodChange.marketPriceAt(c2, live: live[c2], closes: closes, time: t2)
                    }
                    assertNear("\(label) \(coin) detail realized", x["realized"], detail.position.realizedExact)
                    assertNear("\(label) \(coin) proceeds", x["proceeds"], covered)
                    assertNear("\(label) \(coin) uncovered", x["uncoveredProceeds"], uncovered)
                    assertNear("\(label) \(coin) realized", x["realized"], position.realizedExact)
                    XCTAssertEqual(position.realizedIncomplete, x["incomplete"] as? Bool, "\(label) \(coin) incomplete")
                }
                // Gebühr als Veräusserung ohne Erlös: Vermerk «Gebühr» im Steuer-Export
                if let n = TestSupport.number(s["feeRows"]) {
                    var utc = Calendar(identifier: .gregorian)
                    utc.timeZone = TimeZone(identifier: "UTC")!
                    let last: Int64 = trades.map(\.time).max() ?? 0
                    let year: Int = utc.component(.year, from: Date(timeIntervalSince1970: Double(last) / 1000))
                    let rows = TaxExport.gainRows(trades, method: TaxMethod.of(method), period: TaxTemplate.NEUTRAL.period(year),
                                                  timeZone: TimeZone(identifier: "UTC")!, currency: "USD", rates: nil,
                                                  priceAt: feePrice)
                    XCTAssertEqual(rows.filter(\.feeRow).count, Int(n), "\(label) feeRows")
                }
            }
            for cut in c["cutoffs"] as? [Any] ?? [] {
                guard let o = cut as? [String: Any] else { continue }
                let time = TestSupport.number(o["cutoff"]).map { Int64($0) } ?? .max
                for (m, expected) in o["methods"] as? [String: Any] ?? [:] {
                    guard let method = CostMethod(rawValue: m) else { continue }
                    let label = "\(name)/\(m)@\(time)"
                    let exp = expected as? [String: Any] ?? [:]
                    assertHoldings("\(label) card", exp, card(trades.filter { $0.time <= time }, method))
                    assertHoldings("\(label) cutoff", exp,
                                   map(CutoffExport.holdingsAt(trades, cutoffMillis: time, method: method)))
                }
            }
        }
    }

    func testRealizedIncompleteFromDisposals() throws {
        let cases = TestSupport.list(try TestSupport.fixture("holdings_method")["realized"])
        XCTAssertGreaterThanOrEqual(cases.count, 2)
        for c in cases {
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            for (m, spec) in c["methods"] as? [String: Any] ?? [:] {
                guard let method = CostMethod(rawValue: m), let s = spec as? [String: Any] else { continue }
                let label = "\(c["name"] as? String ?? "")/\(m)"
                let ledger = PortfolioCalculator.ledger(c["coin"] as? String ?? "", trades: trades, currentPrice: nil,
                                                        method: method)
                XCTAssertEqual(ledger.position.realizedIncomplete, s["incomplete"] as? Bool, "\(label) incomplete")
                // Gleiche Daten wie der Steuer-Export («unvollständig» bei fehlendem Einstand/Erlös)
                let taxIncomplete = PortfolioCalculator.disposals(trades, method: method)
                    .contains { $0.costUsdt == nil || $0.proceedsUsdt == nil }
                XCTAssertEqual(ledger.position.realizedIncomplete, taxIncomplete, "\(label) tax")
                XCTAssertEqual(ledger.position.realizedExact, dec(s["realized"] as? String ?? ""), "\(label) realized")
            }
        }
    }

    /// Bestand der Arbeitsunterlage nach der Methode der Vorlage (wie im Kopf), allgemeiner Stichtag nach der App-Methode.
    func testWorkpaperHoldingsUseTemplateMethod() throws {
        let cases = TestSupport.list(try TestSupport.fixture("holdings_method")["workpaperHoldings"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let template = TaxTemplate.fromName(c["template"] as? String)
            let app = try XCTUnwrap(CostMethod(rawValue: c["appMethod"] as? String ?? ""), name)
            let year = Int(TestSupport.number(c["year"]) ?? 0)
            let cutoff = Int64(TestSupport.number(c["cutoff"]) ?? 0)
            let trades = (c["trades"] as? [Any] ?? []).map(trade)
            let taxMethod = template.methodFor(app, year)
            XCTAssertEqual(taxMethod.rawValue, c["taxMethod"] as? String, name)
            XCTAssertEqual(taxMethod.holdingsCostMethod.rawValue, c["holdingsCostMethod"] as? String, name)
            XCTAssertEqual(template.methodOverridden(app, year), c["overridden"] as? Bool, name)
            assertHoldings("\(name) workpaper", c["holdings"] as? [String: Any] ?? [:],
                           map(CutoffExport.holdingsAt(trades, cutoffMillis: cutoff, method: taxMethod.holdingsCostMethod)))
            assertHoldings("\(name) app", c["appHoldings"] as? [String: Any] ?? [:],
                           map(CutoffExport.holdingsAt(trades, cutoffMillis: cutoff, method: app)))
        }
    }
}
