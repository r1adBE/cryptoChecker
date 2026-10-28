import XCTest
@testable import CryptoChecker

/// Funding/Open Interest der Börsen mit eigenen Futures-Daten — wie `FuturesParsersTest.kt`, dieselbe
/// Datei `futures_derivatives.json` (Quelle `android/testdata/exchanges`, Kopie in `Tests/Exchanges`).
final class FuturesParsersTests: XCTestCase {

    private func run(_ parser: String, _ b: [String: Any]) throws -> FuturesInfo {
        func text(_ key: String) -> String? { b[key] as? String }
        switch parser {
        case "woo":
            return try FuturesParsers.woo(text("body") ?? "")
        case "bitget":
            return try FuturesParsers.bitget(ticker: text("ticker") ?? "", fundingTime: text("fundingTime"))
        case "mexc":
            return try FuturesParsers.mexc(ticker: text("ticker") ?? "", detail: text("detail"),
                                           fundingRate: text("fundingRate"), symbol: text("symbol") ?? "")
        case "htx":
            return try FuturesParsers.htx(funding: text("funding") ?? "", openInterest: text("openInterest"))
        case "hyperliquid":
            return try FuturesParsers.hyperliquid(text("body") ?? "", coin: text("coin") ?? "")
        case "deribit":
            return try FuturesParsers.deribit(text("body") ?? "")
        case "binanceCoinM":
            let sizes = try text("exchangeInfo").map { try FuturesParsers.binanceCoinMContractSizes($0) }
            return try FuturesParsers.binanceCoinM(premium: text("premium") ?? "", openInterest: text("openInterest"),
                                                   symbol: text("symbol") ?? "", contractSizes: sizes)
        default:
            throw JSONError(message: "unknown parser \(parser)")
        }
    }

    private func check(_ label: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else {
            XCTAssertNil(actual, "\(label): expected nil")
            return
        }
        guard let actual else {
            XCTFail("\(label): expected \(expected), got nil")
            return
        }
        XCTAssertEqual(actual, expected, accuracy: max(abs(expected) * 1e-9, 1e-12), label)
    }

    func testParsersMatchFixtures() throws {
        let cases = TestSupport.list(try TestSupport.fixture("futures_derivatives")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let parser = c["parser"] as? String ?? ""
            let name = "\(parser): \(c["name"] as? String ?? "")"
            let bodies = c["bodies"] as? [String: Any] ?? [:]
            if c["expectError"] as? Bool == true {
                XCTAssertThrowsError(try run(parser, bodies), name)
                continue
            }
            let info = try run(parser, bodies)
            let e = c["expected"] as? [String: Any] ?? [:]
            check("\(name) funding", TestSupport.number(e["fundingRatePercent"]), info.fundingRatePercent)
            check("\(name) openInterest", TestSupport.number(e["openInterestUsd"]), info.openInterestUsd)
            XCTAssertEqual(info.nextFundingTime, TestSupport.number(e["nextFundingTime"]).map { Int64($0) },
                           "\(name) nextFundingTime")
            XCTAssertEqual(info.source, e["source"] as? String, "\(name) source")
            if e.keys.contains("fundingIntervalHours") {
                XCTAssertEqual(info.fundingIntervalHours, TestSupport.number(e["fundingIntervalHours"]).map { Int($0) },
                               "\(name) fundingIntervalHours")
            }
        }
    }

    func testFundingIntervalsMatchFixtures() throws {
        let f = try TestSupport.fixture("futures_derivatives")["intervals"] as? [String: Any] ?? [:]
        let binance = try FuturesParsers.binanceFundingInfo(f["binanceFundingInfo"] as? String ?? "[]")
        for c in TestSupport.list(f["binance"]) {
            let symbol = c["symbol"] as? String ?? ""
            XCTAssertEqual(FuturesParsers.binanceIntervalHours(binance, symbol: symbol),
                           TestSupport.number(c["expected"]).map { Int($0) }, "binance \(symbol)")
        }
        for c in TestSupport.list(f["okx"]) {
            XCTAssertEqual(FuturesParsers.okxIntervalHours(fundingTime: TestSupport.number(c["fundingTime"]).map { Int64($0) },
                                                           nextFundingTime: TestSupport.number(c["nextFundingTime"]).map { Int64($0) }),
                           TestSupport.number(c["expected"]).map { Int($0) }, "okx \(c["name"] as? String ?? "")")
        }
        for c in TestSupport.list(f["hours"]) {
            let value = TestSupport.number(c["value"]) ?? (c["value"] as? String).flatMap { Double($0) }
            XCTAssertEqual(FuturesParsers.hours(value), TestSupport.number(c["expected"]).map { Int($0) }, "hours \(String(describing: c["value"]))")
        }
    }

    func testNotesMatchFixtures() throws {
        for c in TestSupport.list(try TestSupport.fixture("futures_derivatives")["notes"]) {
            let note = FuturesSources.note(perpetual: c["perpetual"] as? Bool ?? false,
                                           exchangeKey: c["exchangeKey"] as? String ?? "",
                                           source: c["source"] as? String ?? "",
                                           pairId: c["pairId"] as? String)
            XCTAssertEqual(note?.rawValue, c["expected"] as? String,
                           "note \(c["exchangeKey"] as? String ?? "") \(c["pairId"] as? String ?? "") / \(c["source"] as? String ?? "")")
        }
    }

    /// Standard-Intervall je Quelle: nur Quellen mit festem Intervall haben eines, sonst unbekannt — wie Android.
    func testSourceIntervalDefaultsMatchFixtures() throws {
        let f = try TestSupport.fixture("futures_derivatives")["intervals"] as? [String: Any] ?? [:]
        for c in TestSupport.list(f["defaults"]) {
            let source = c["source"] as? String ?? ""
            XCTAssertEqual(FuturesSources.fundingIntervalHours(source), TestSupport.number(c["expected"]).map { Int($0) },
                           "default \(source)")
        }
    }
}
