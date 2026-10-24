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

    func testRsiBleibtZwischenNullUndHundert() throws {
        for c in S.list(try S.fixture("indicators")["rsi"]) {
            let period = Int(try XCTUnwrap(S.number(c["period"])))
            guard let value = Indicators.rsi(closes(c["closes"]), period: period) else { continue }
            XCTAssertTrue((0.0...100.0).contains(value), "\(c["name"] as? String ?? ""): \(value)")
        }
    }
}
