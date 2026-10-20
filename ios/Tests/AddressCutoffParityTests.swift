import XCTest
@testable import CryptoChecker

/// Stichtag-CSV und CH-Vermögenswert mit beobachteten Adressen wie die Karte (`AddressCutoff`): `cutoff_export.json`
/// «addressCases» (ganze Datei ab dem Kommentar) und `tax_ch_wealth.json` «watchedCases» (Werte je Coin, Vermerk an der
/// Summe, Hinweiszeile der Jahresübersicht). Wie `AddressCutoffParityTest.kt` (Android).
final class AddressCutoffParityTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ raw: Any?) -> Decimal? { PortfolioDecimal.parse(raw as? String) }

    private func int64(_ value: Any?) -> Int64 { Int64(S.number(value) ?? 0) }

    private func r8(_ value: Decimal?) -> Decimal? {
        guard var v = value else { return nil }
        var out = Decimal()
        NSDecimalRound(&out, &v, 8, .plain)
        return out
    }

    private func address(_ o: [String: Any]) -> WatchedAddress {
        WatchedAddress(id: int64(o["id"]), chain: AddressChain(rawValue: o["chain"] as? String ?? "") ?? .BTC,
                       address: o["address"] as? String ?? "", label: o["label"] as? String,
                       lastBalance: o["lastBalance"] as? String, lastUpdated: int64(o["lastUpdated"]),
                       costLots: S.list(o["costLots"]).map {
                           AddressCostLot(id: int64($0["id"]), coin: $0["coin"] as? String ?? "", time: int64($0["t"]),
                                          amount: dec($0["amount"]) ?? 0, priceUsdt: dec($0["price"]))
                       })
    }

    private func trade(_ o: [String: Any]) -> PortfolioTx {
        var t = PortfolioTx(id: int64(o["id"]), coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"]) ?? 0, priceExact: dec(o["price"]), time: int64(o["time"]),
                            exchange: o["exchange"] as? String)
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        return t
    }

    private var csvTexts: CutoffCsvTexts {
        var t = CutoffCsvTexts(coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT", rate: "Kurs",
                               valueTarget: "Wert", note: "Hinweis", total: "Total", noPrice: "kein Kurs",
                               noFx: "kein Devisenkurs", unchecked: "ungeprüft", incomplete: "unvollständig",
                               commentFormat: "# %@ %@ %@")
        t.fromAddress = "aus Adresse"
        t.addressLots = "Posten nach Beobachtung"
        return t
    }

    func testCutoffCsvCountsAddressesLikeTheCard() throws {
        let data = try S.fixture("cutoff_export")
        let cases = S.list(data["addressCases"])
        for c in cases {
            let name = c["name"] as? String ?? ""
            let date = try XCTUnwrap(LocalDay(iso: c["date"] as? String ?? ""))
            let day = CutoffDay(year: date.year, month: date.month, day: date.day)
            let cutoff = day.endOfDayMillis(in: try XCTUnwrap(TimeZone(identifier: c["zone"] as? String ?? "")))
            let trades = S.list(c["trades"]).map(trade)
            let view = AddressCutoff.at(S.list(c["addresses"]).map(address), real: trades, cutoffMillis: cutoff)
            let method = try XCTUnwrap(CostMethod(rawValue: c["method"] as? String ?? ""))
            let holdings = AddressCutoff.holdingsAt(trades, view: view, cutoffMillis: cutoff, method: method)
            var prices: [String: Decimal] = [:]
            for (k, v) in c["prices"] as? [String: Any] ?? [:] { prices[k] = dec(v) }
            let fx = dec(c["fxRate"])
            let rows = CutoffExport.rows(holdings, prices: prices, fxRate: fx)
                + CutoffExport.addressRows(view.part, prices: prices, fxRate: fx)
            let lines = CutoffExport.csv(day: day, currency: "CHF", fxDate: nil, rows: rows, texts: csvTexts,
                                         addressLots: view.lotsAfterObservation)
                .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
            let expected = (c["expected"] as? [String]) ?? []
            XCTAssertEqual(Array(lines.dropFirst().prefix(expected.count)), expected, name)
            XCTAssertEqual(Array(lines.dropFirst(1 + expected.count)), [""], "\(name) Ende")
        }
        XCTAssertGreaterThanOrEqual(cases.count, 3)
    }

    private var summaryTexts: TaxSummaryTexts {
        var labels: [TaxLine: String] = [:]
        for l in TaxLine.allCases { labels[l] = l.rawValue }
        var t = TaxSummaryTexts(title: "T", disclaimer: "D", item: "Item", usdt: "USDT", target: "CHF", note: "Note",
                                orientation: "O", labels: labels, below: "below", reached: "reached", unknown: "unknown",
                                incomplete: "incomplete", noFx: "nofx")
        t.addressLots = "lots"
        return t
    }

    func testChWealthCountsAddressesLikeTheCard() throws {
        let data = try S.fixture("tax_ch_wealth")
        let zone = try XCTUnwrap(TimeZone(identifier: data["zone"] as? String ?? ""))
        let date = try XCTUnwrap(LocalDay(iso: data["date"] as? String ?? ""))
        let cutoff = CutoffDay(year: date.year, month: date.month, day: date.day).endOfDayMillis(in: zone)
        for c in S.list(data["watchedCases"]) {
            let name = c["name"] as? String ?? ""
            let e = c["expected"] as? [String: Any] ?? [:]
            let trades = S.list(c["trades"]).map(trade)
            let view = AddressCutoff.at(S.list(c["watched"]).map(address), real: trades, cutoffMillis: cutoff)
            var closes = CutoffCloses()
            for (k, v) in c["closes"] as? [String: Any] ?? [:] { closes.prices[k] = dec(v) }
            for case let m as String in c["methods"] as? [Any] ?? [] {
                let method = try XCTUnwrap(CostMethod(rawValue: m))
                let w = TaxExport.chWealth(trades, method: method, date: date, timeZone: zone, closes: closes,
                                           rate: dec(c["rate"]), addresses: view)
                let rows = (e["rows"] as? [[Any]]) ?? []
                XCTAssertEqual(rows.map { $0[0] as? String ?? "" }, w.rows.map(\.coin), "\(name) \(m) coins")
                for (x, r) in zip(rows, w.rows) {
                    XCTAssertEqual(r8(dec(x[1])), r8(r.amount), "\(name) \(m) amount")
                    XCTAssertEqual(r8(dec(x[2])), r8(r.valueUsdt), "\(name) \(m) usdt")
                    XCTAssertEqual(r8(dec(x[3])), r8(r.valueTarget), "\(name) \(m) target")
                    XCTAssertEqual(x[4] as? Bool, r.missing, "\(name) \(m) missing")
                }
                XCTAssertEqual(r8(dec(e["totalUsdt"])), r8(w.totalUsdt), "\(name) \(m) total usdt")
                XCTAssertEqual(e["moneyTotal"] as? String, w.totalTarget.map { CutoffExport.money($0) }, "\(name) \(m) money")
                XCTAssertEqual(e["incomplete"] as? Bool, w.incomplete, "\(name) \(m) incomplete")
                XCTAssertEqual(e["addressNote"] as? Bool, w.addressNote, "\(name) \(m) note")
            }
            // Jahresübersicht: Vermerk aus dem Vermögenswert, Hinweiszeile nur bei Bedarf
            let rate = try XCTUnwrap(dec(c["rate"]))
            let s = TaxExport.yearSummary(trades, template: .CH, method: .FIFO, startYear: date.year, timeZone: zone,
                                          currency: "CHF", rates: [date.epochDay: rate],
                                          today: LocalDay(date.year + 1, 6, 30), yearEndCloses: closes,
                                          yearEndAddresses: view)
            XCTAssertEqual(s.wealth?.addressNote, e["addressNote"] as? Bool, "\(name) summary note")
            let line = try XCTUnwrap(s.lines.first { $0.line == .CH_WEALTH_VALUE })
            XCTAssertEqual(r8(line.usdt), r8(dec(e["totalUsdt"])), "\(name) summary usdt")
            let csv = TaxExport.summaryCsv(s, texts: summaryTexts, header: ["H"]).components(separatedBy: "\r\n")
            XCTAssertEqual(csv.filter { $0 == "lots" }.count, (e["addressNote"] as? Bool ?? false) ? 1 : 0, "\(name) lots line")
        }
    }
}
