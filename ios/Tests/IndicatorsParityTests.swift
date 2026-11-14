import XCTest
@testable import CryptoChecker

/// Kennzahlen auf Schlusskursen (`Parity/indicators.json`): gleitender Durchschnitt der letzten n Werte und RSI nach
/// Wilder, nil bei zu wenig Daten oder nicht positivem n bzw. Zeitraum. Gegenstück: `IndicatorsParityTest.kt`.
@MainActor
final class IndicatorsParityTests: XCTestCase {
    private typealias S = TestSupport

    private func closes(_ raw: Any?) -> [Double] {
        (raw as? [Any])?.compactMap { S.number($0) } ?? []
    }

    private func check(_ name: String, _ expected: Double?, _ actual: Double?) {
        guard let expected else {
            XCTAssertNil(actual, name)
            return
        }
        guard let actual else {
            XCTFail("\(name): nil")
            return
        }
        XCTAssertEqual(actual, expected, accuracy: 1e-9, name)
    }

    func testSmaWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("indicators")["sma"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let n = Int(try XCTUnwrap(S.number(c["n"])))
            check("sma: \(c["name"] as? String ?? "")", S.number(c["expected"]),
                  Indicators.smaOfLast(closes(c["values"]), n))
        }
    }

    func testRsiWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("indicators")["rsi"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let period = Int(try XCTUnwrap(S.number(c["period"])))
            check("rsi: \(c["name"] as? String ?? "")", S.number(c["expected"]),
                  Indicators.rsi(closes(c["closes"]), period: period))
        }
    }

    private func days(_ raw: Any?) -> [Indicators.DayClose] {
        (raw as? [[Any]] ?? []).map { Indicators.DayClose(day: Int(S.number($0[0]) ?? 0), close: S.number($0[1]) ?? 0) }
    }

    /// Kennzahlen «vor n Tagen» nach Datum, nicht nach Kerzen-Position (Lücken einer Quelle verschieben nichts).
    func testByDateWieGemeinsameTestfaelle() throws {
        let data = try S.fixture("indicators")
        for c in S.list(data["closeOnOrBefore"]) {
            check("closeOnOrBefore: \(c["name"] as? String ?? "")", S.number(c["expected"]),
                  Indicators.closeOnOrBefore(days(c["series"]), day: Int(S.number(c["day"]) ?? 0)))
        }
        for c in S.list(data["changeOverDays"]) {
            check("changeOverDays: \(c["name"] as? String ?? "")", S.number(c["expected"]),
                  Indicators.changeOverDays(days(c["series"]), days: Int(S.number(c["days"]) ?? 0)))
        }
        let relative = S.list(data["relativeChange"])
        XCTAssertGreaterThanOrEqual(relative.count, 4)
        for c in relative {
            check("relativeChange: \(c["name"] as? String ?? "")", S.number(c["expected"]),
                  Indicators.relativeChangePercent(coin: days(c["coin"]), btc: days(c["btc"]),
                                                   days: Int(S.number(c["days"]) ?? 0)))
        }
    }

    func testRsiBleibtZwischenNullUndHundert() throws {
        for c in S.list(try S.fixture("indicators")["rsi"]) {
            let period = Int(try XCTUnwrap(S.number(c["period"])))
            guard let value = Indicators.rsi(closes(c["closes"]), period: period) else { continue }
            XCTAssertTrue((0.0...100.0).contains(value), "\(c["name"] as? String ?? ""): \(value)")
        }
    }
}
