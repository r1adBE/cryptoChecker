import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/tax_day_close.json` — wie `TaxDayCloseParityTest.kt`.
final class TaxDayCloseParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("tax_day_close") }

    private func dec(_ value: Any?) -> Decimal? {
        (value as? String).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) }
    }

    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }

    private func closes(_ d: [String: Any]) -> [String: Decimal] {
        var out: [String: Decimal] = [:]
        for c in TestSupport.list(d["closes"]) {
            out[TaxDayClose.key(c["coin"] as? String ?? "", Int(int64(c["day"])))] = dec(c["close"])
        }
        return out
    }

    private func trades(_ d: [String: Any]) -> [PortfolioTx] {
        TestSupport.list(d["trades"]).map { t in
            var tx = PortfolioTx(id: int64(t["id"]), coin: t["coin"] as? String ?? "",
                                 type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                                 amountExact: dec(t["amount"]) ?? 0, priceExact: nil, time: int64(t["time"]),
                                 feeExact: dec(t["fee"]),
                                 feeUnit: (t["feeUnit"] as? String).flatMap { PortfolioFeeUnit(rawValue: $0) })
            tx.feeRow = t["feeRow"] as? Bool ?? false
            return tx
        }
    }

    private func rounded(_ v: Decimal) -> Decimal {
        var value = v
        var out = Decimal()
        NSDecimalRound(&out, &value, 2, .plain)
        return out
    }

    /// Kraken-Gebühr-Zeile und Netzwerkgebühr derselben Stunde: derselbe Tag, derselbe Kurs, derselbe Wert.
    func testFeeRowAndNetworkFeeShareOneClose() throws {
        let d = try data()
        let expectedDay = Int(int64(d["expectedDay"]))
        let expectedClose = try XCTUnwrap(dec(d["expectedClose"]))
        let expectedValue = try XCTUnwrap(dec(d["expectedFeeValue"]))
        let lookup = TaxDayClose.lookup(closes(d))
        let txs = trades(d)

        // Gebühr-Zeile: Bedarf wie feeDisposalPrices (PortfolioLedger.dayCloseNeeds → UTC-Tag)
        let feeRow = try XCTUnwrap(txs.first { $0.feeRow })
        let feeNeeds = PortfolioLedger.dayCloseNeeds([feeRow]).map { "\($0.coin)|\(TaxDayClose.day($0.time))" }
        XCTAssertEqual(feeNeeds, ["BTC|\(expectedDay)"])
        // Netzwerkgebühr als Veräusserung: Bedarf wie networkFeePrices
        let transfer = try XCTUnwrap(txs.first { $0.type == .TRANSFER })
        XCTAssertEqual(TaxDayClose.networkFeeNeeds(txs, end: int64(d["end"])), ["BTC": [expectedDay]])

        let feeRowClose = try XCTUnwrap(lookup("BTC", feeRow.time))
        let networkClose = try XCTUnwrap(lookup("BTC", transfer.time))
        XCTAssertEqual(feeRowClose, expectedClose)
        XCTAssertEqual(networkClose, expectedClose)
        XCTAssertEqual(rounded(feeRow.amountExact * feeRowClose), expectedValue)
        XCTAssertEqual(rounded(transfer.coinFeeExact * networkClose), expectedValue)
    }

    /// Kurz nach Mitternacht Ortszeit gilt der Schluss des UTC-Tags, nicht des lokalen Kalendertags.
    func testUtcDayDecides() throws {
        let d = try data()
        let late = try XCTUnwrap(d["lateLocal"] as? [String: Any])
        let time = int64(late["time"])
        XCTAssertEqual(TaxDayClose.day(time), Int(int64(late["expectedDay"])))
        XCTAssertEqual(TaxDayClose.lookup(closes(d))("BTC", time), dec(late["expectedClose"]))
    }
}
