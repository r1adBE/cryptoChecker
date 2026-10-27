import XCTest
@testable import CryptoChecker

/// Buchungen unter den Coins im Portfolio-Widget (`Parity/widget_portfolio_entries.json`) — wie
/// `WidgetPortfolioEntriesParityTest.kt`: gekürzte Notiz, Bestandsänderung je Buchung nach der Regel des Rechners und
/// welche Buchungen unter den Coins Platz haben.
final class WidgetPortfolioEntriesParityTests: XCTestCase {

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private func trade(_ raw: Any, notes: [String: Any]) -> PortfolioTx {
        let o = raw as? [String: Any] ?? [:]
        let id = Int64(TestSupport.number(o["id"]) ?? 0)
        var t = PortfolioTx(id: id,
                            coin: o["coin"] as? String ?? "",
                            type: PortfolioTxType(rawValue: o["type"] as? String ?? "") ?? .BUY,
                            amountExact: dec(o["amount"] as? String ?? "0"),
                            priceExact: (o["price"] as? String).map(dec),
                            time: Int64(TestSupport.number(o["time"]) ?? 0),
                            feeExact: (o["fee"] as? String).map(dec),
                            feeUnit: (o["feeUnit"] as? String).flatMap(PortfolioFeeUnit.init(rawValue:)))
        t.exchange = o["exchange"] as? String
        t.transferFrom = o["transferFrom"] as? String
        t.transferTo = o["transferTo"] as? String
        t.toCoin = o["toCoin"] as? String
        t.toAmountExact = (o["toAmount"] as? String).map(dec)
        t.swapValueExact = (o["swapValue"] as? String).map(dec)
        t.note = notes[String(id)] as? String
        return t
    }

    func testShortNote() throws {
        let cases = TestSupport.list(try TestSupport.fixture("widget_portfolio_entries")["shortNote"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let note = c["note"] as? String
            XCTAssertEqual(PortfolioWidgetEntries.shortNote(note), c["expected"] as? String, "«\(note ?? "nil")»")
        }
    }

    func testEntriesFollowTheHoldingsRule() throws {
        let cases = TestSupport.list(try TestSupport.fixture("widget_portfolio_entries")["entries"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let notes = c["notes"] as? [String: Any] ?? [:]
            let trades = (c["trades"] as? [Any] ?? []).map { trade($0, notes: notes) }
            let method = CostMethod(rawValue: c["method"] as? String ?? "") ?? .defaultMethod
            let entries = PortfolioWidgetEntries.withValues(
                PortfolioWidgetEntries.of(c["coin"] as? String ?? "", trades: trades, method: method),
                price: TestSupport.number(c["price"]), factor: TestSupport.number(c["fx"]) ?? 1)
            let expected = TestSupport.list(c["expected"])
            XCTAssertEqual(entries.count, expected.count, "\(name): Anzahl")
            for (i, (e, actual)) in zip(expected, entries).enumerated() {
                XCTAssertEqual(PortfolioDecimal.of(actual.quantity), dec(e["quantity"] as? String ?? ""), "\(name) [\(i)] Menge")
                XCTAssertEqual(actual.note, e["note"] as? String, "\(name) [\(i)] Notiz")
                if let value = TestSupport.number(e["value"]) {
                    XCTAssertEqual(actual.value ?? .nan, value, accuracy: 1e-9, "\(name) [\(i)] Wert")
                } else {
                    XCTAssertNil(actual.value, "\(name) [\(i)] Wert")
                }
            }
            XCTAssertEqual(PortfolioWidgetEntries.total(entries), dec(c["total"] as? String ?? ""), "\(name): Summe")
        }
    }

    func testAssignKeepsWholeCoins() throws {
        let cases = TestSupport.list(try TestSupport.fixture("widget_portfolio_entries")["assign"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let perCoin: [[PortfolioWidgetEntry]?] = (c["perCoin"] as? [Any] ?? []).map { coin in
                (coin as? [Any]).map { list in
                    list.enumerated().map { i, q in
                        PortfolioWidgetEntry(time: Int64(i), quantity: Double(q as? String ?? "") ?? 0, note: nil, value: nil)
                    }
                }
            }
            let shown = PortfolioWidgetEntries.assign(perCoin,
                                                      filter: PortfolioWidgetEntryFilter.from(key: c["filter"] as? String),
                                                      available: TestSupport.number(c["available"]) ?? 0,
                                                      rowHeight: TestSupport.number(c["rowHeight"]) ?? 0)
            let expected = (c["expected"] as? [Any] ?? []).map { coin in
                (coin as? [Any] ?? []).map { Int(TestSupport.number($0) ?? -1) }
            }
            XCTAssertEqual(shown.map { $0.map { Int($0.time) } }, expected, name)
        }
    }
}
