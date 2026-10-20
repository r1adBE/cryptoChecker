import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `tax_ch_wealth.json` — CH-Vermögenswert per 31.12. (Android: `TaxChWealthParityTest.kt`).
final class TaxChWealthTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("tax_ch_wealth") }

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    /// Vergleich auf 8 Nachkommastellen (Produkte runden auf 34 gültige Stellen).
    private func r8(_ value: Decimal?) -> Decimal? {
        guard var v = value else { return nil }
        var out = Decimal()
        NSDecimalRound(&out, &v, 8, .plain)
        return out
    }

    private func trades(_ raw: Any?) -> [PortfolioTx] {
        S.list(raw).map { (t: [String: Any]) -> PortfolioTx in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]), time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.exchange = t["exchange"] as? String
            tx.transferFrom = t["transferFrom"] as? String
            tx.transferTo = t["transferTo"] as? String
            return tx
        }
    }

    private func closes(_ c: [String: Any]) -> CutoffCloses? {
        guard let raw = c["closes"] as? [String: Any] else { return nil }
        var out = CutoffCloses()
        for (k, v) in raw { out.prices[k] = dec(v) }
        out.unchecked = Set((c["unchecked"] as? [String]) ?? [])
        return out
    }

    private func addresses(_ c: [String: Any]) -> AddressBalancesAt? {
        guard let a = c["addresses"] as? [String: Any] else { return nil }
        var balances: [String: Decimal] = [:]
        for (k, v) in (a["balances"] as? [String: Any]) ?? [:] { balances[k] = dec(v) }
        return AddressBalancesAt(balances: balances, unobserved: Set((a["unobserved"] as? [String]) ?? []))
    }

    func testWealth() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        let date = try XCTUnwrap(LocalDay(iso: d["date"] as? String ?? ""))
        for c in S.list(d["cases"]) {
            let name = c["name"] as? String ?? ""
            let e = c["expected"] as? [String: Any] ?? [:]
            for case let m as String in c["methods"] as? [Any] ?? [] {
                let method = try XCTUnwrap(CostMethod(rawValue: m))
                let yearEnd = try XCTUnwrap(closes(c))
                let w = TaxExport.chWealth(trades(c["trades"]), method: method, date: date, timeZone: zone,
                                           closes: yearEnd, rate: dec(c["rate"]), addresses: addresses(c))
                let rows = (e["rows"] as? [[Any]]) ?? []
                XCTAssertEqual(rows.map { $0[0] as? String ?? "" }, w.rows.map(\.coin), "\(name) \(m) coins")
                for (x, r) in zip(rows, w.rows) {
                    XCTAssertEqual(r8(dec(x[1])), r8(r.amount), "\(name) \(m) \(r.coin) amount")
                    XCTAssertEqual(r8(dec(x[2])), r8(r.valueUsdt), "\(name) \(m) \(r.coin) usdt")
                    XCTAssertEqual(r8(dec(x[3])), r8(r.valueTarget), "\(name) \(m) \(r.coin) target")
                    XCTAssertEqual(x[4] as? Bool, r.missing, "\(name) \(m) \(r.coin) missing")
                    // Nur ein Teil fehlt: bekannter Teil gerechnet, «unvollständig» (wie die Bestandsdatei); fehlt = false
                    XCTAssertEqual(x.count > 5 ? (x[5] as? Bool ?? false) : false, r.incomplete, "\(name) \(m) \(r.coin) incomplete")
                }
                XCTAssertEqual(r8(dec(e["totalUsdt"])), r8(w.totalUsdt), "\(name) \(m) total usdt")
                XCTAssertEqual(r8(dec(e["totalTarget"])), r8(w.totalTarget), "\(name) \(m) total target")
                XCTAssertEqual(e["incomplete"] as? Bool, w.incomplete, "\(name) \(m) incomplete")
                XCTAssertEqual(e["noFx"] as? Bool, w.noFx, "\(name) \(m) noFx")
                // Summe = Summe der gerundeten Zeilen, wie sie in der Datei stehen (Nachrechnen geht auf)
                XCTAssertEqual(e["moneyTotal"] as? String, w.totalTarget.map { CutoffExport.money($0) }, "\(name) \(m) money")
                let sumOfRows: Decimal = w.rows.compactMap(\.valueTarget).reduce(Decimal(0)) { $0 + CutoffExport.roundedMoney($1) }
                if let total = w.totalTarget {
                    XCTAssertEqual(CutoffExport.money(sumOfRows), CutoffExport.money(total), "\(name) \(m) sum of rows")
                }
            }
        }
    }

    private var texts: TaxSummaryTexts {
        var labels: [TaxLine: String] = [:]
        for l in TaxLine.allCases { labels[l] = l.rawValue }
        return TaxSummaryTexts(title: "T", disclaimer: "D", item: "Item", usdt: "USDT", target: "CHF", note: "Note",
                               orientation: "O", labels: labels, below: "below", reached: "reached", unknown: "unknown",
                               incomplete: "incomplete", noFx: "nofx", orientationHeading: "Orientation",
                               orientationItem: "Orientation: %s", notes: [.CH_WEALTH_ORIENTATION: "chwealth"],
                               wealthCoin: "{coin} – {amount}", wealthMissing: "nc", wealthNoPrice: "noprice",
                               wealthUnchecked: "unchecked", wealthAddressUnobserved: "unobserved")
    }

    func testSummaries() throws {
        let d = try data()
        let zone = try XCTUnwrap(TimeZone(identifier: d["zone"] as? String ?? ""))
        for c in S.list(d["summaries"]) {
            let name = c["name"] as? String ?? ""
            var rates: [Int: Decimal] = [:]
            for (k, v) in (c["rates"] as? [String: Any]) ?? [:] {
                if let day = LocalDay(iso: k), let r = dec(v) { rates[day.epochDay] = r }
            }
            let method = try XCTUnwrap(TaxMethod(rawValue: c["method"] as? String ?? ""))
            let s = TaxExport.yearSummary(trades(c["trades"]), template: .CH, method: method,
                                          startYear: Int(S.number(c["year"]) ?? 0), timeZone: zone,
                                          currency: c["currency"] as? String ?? "", rates: rates,
                                          today: LocalDay(iso: c["today"] as? String ?? ""), yearEndCloses: closes(c))
            let line = try XCTUnwrap(s.lines.first { $0.line == .CH_WEALTH_VALUE })
            let x = (c["line"] as? [Any]) ?? []
            XCTAssertEqual(r8(dec(x[0])), r8(line.usdt), "\(name) usdt")
            XCTAssertEqual(r8(dec(x[1])), r8(line.target), "\(name) target")
            XCTAssertEqual(x[2] as? Bool, line.incomplete, "\(name) incomplete")
            XCTAssertEqual(x[3] as? Bool, line.noFx, "\(name) noFx")
            XCTAssertEqual(x[4] as? Bool, line.notCalculated, "\(name) notCalculated")
            XCTAssertEqual(line.note, .CH_WEALTH_ORIENTATION, "\(name) note")
            if line.notCalculated { XCTAssertNil(s.wealth, "\(name) wealth") }
            // Tatsachen-Posten vor dem Orientierungs-Block; Kapitalgewinne bleiben «nicht gerechnet» (ohne Betrag)
            let at = try XCTUnwrap(s.lines.firstIndex { $0.line == .CH_WEALTH_VALUE })
            let firstOrientation = try XCTUnwrap(s.lines.firstIndex { $0.line.orientation })
            XCTAssertLessThan(at, firstOrientation, "\(name) vor Orientierung")
            let priv = try XCTUnwrap(s.lines.first { $0.line == .CH_PRIVATE })
            XCTAssertNil(priv.usdt)
            XCTAssertNil(priv.target)
            var csv = TaxExport.summaryCsv(s, texts: texts, header: ["H"])
            if csv.hasPrefix("\u{FEFF}") { csv.removeFirst() }
            let lines = csv.components(separatedBy: "\r\n").filter { !$0.isEmpty }
            let start = try XCTUnwrap(lines.firstIndex { $0.hasPrefix("CH_WEALTH_VALUE;") }, "\(name) CSV-Posten")
            let expected = (c["csv"] as? [String]) ?? []
            XCTAssertEqual(Array(lines[start..<min(lines.count, start + expected.count)]), expected, "\(name) CSV")
            // Danach kein weiterer Coin mehr, sondern der nächste Posten
            if start + expected.count < lines.count {
                let next = lines[start + expected.count]
                XCTAssertTrue(TaxLine.allCases.contains { next.hasPrefix($0.rawValue + ";") } || next == "Orientation",
                              "\(name) CSV danach \(next)")
            }
            // Adressen mit Einstand: Hinweiszeile nur mit CH-Vermögen und nur bei Bedarf; sonst dieselbe Datei
            var lotsTexts = texts
            lotsTexts.addressLots = "lots"
            let withLots = TaxExport.summaryCsv(s, texts: lotsTexts, header: ["H"], addressLots: true)
                .components(separatedBy: "\r\n")
            let without = TaxExport.summaryCsv(s, texts: lotsTexts, header: ["H"]).components(separatedBy: "\r\n")
            XCTAssertEqual(withLots.filter { $0 == "lots" }.count, s.wealth != nil ? 1 : 0, "\(name) lots")
            XCTAssertFalse(without.contains("lots"), "\(name) ohne lots")
            XCTAssertEqual(withLots.filter { $0 != "lots" }, without, "\(name) sonst gleich")
        }
    }
}
