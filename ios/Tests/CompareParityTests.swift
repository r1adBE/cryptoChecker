import XCTest
@testable import CryptoChecker

/// Vergleichsansicht (`Parity/compare.json`) — wie `CompareParityTest.kt`: Auswahl, Linien in %, Kennzahlen, Achsen.
final class CompareParityTests: XCTestCase {

    private func candle(_ raw: Any) -> MarketCandle {
        let o = raw as? [String: Any] ?? [:]
        let close = TestSupport.number(o["close"]) ?? 0
        return MarketCandle(openTime: Int64(TestSupport.number(o["t"]) ?? 0), open: close,
                            high: TestSupport.number(o["high"]) ?? 0, low: close, close: close, volume: 0, quoteVolume: nil)
    }

    private func point(_ raw: Any) -> ComparePoint {
        let p = raw as? [Any] ?? []
        return ComparePoint(time: Int64(TestSupport.number(p.first) ?? 0), percent: TestSupport.number(p.last) ?? 0)
    }

    private func assertNear(_ expected: Double?, _ actual: Double?, _ label: String) {
        if let expected {
            XCTAssertEqual(actual ?? .nan, expected, accuracy: 1e-9, label)
        } else {
            XCTAssertNil(actual, label)
        }
    }

    func testCanCompare() throws {
        for c in TestSupport.list(try TestSupport.fixture("compare")["canCompare"]) {
            let n = Int(TestSupport.number(c["count"]) ?? -1)
            XCTAssertEqual(CompareMath.canCompare(n), c["expected"] as? Bool, "\(n)")
        }
    }

    func testNormalized() throws {
        for c in TestSupport.list(try TestSupport.fixture("compare")["normalized"]) {
            let name = c["name"] as? String ?? ""
            let actual = CompareMath.normalized((c["candles"] as? [Any] ?? []).map(candle))
            let expected = (c["expected"] as? [Any] ?? []).map(point)
            XCTAssertEqual(actual.count, expected.count, name)
            for (e, a) in zip(expected, actual) {
                XCTAssertEqual(a.time, e.time, name)
                XCTAssertEqual(a.percent, e.percent, accuracy: 1e-9, name)
            }
        }
    }

    func testFigures() throws {
        for c in TestSupport.list(try TestSupport.fixture("compare")["figures"]) {
            let name = c["name"] as? String ?? ""
            let f = CompareMath.figures((c["daily"] as? [Any] ?? []).map(candle), price: TestSupport.number(c["price"]),
                                        now: Int64(TestSupport.number(c["now"]) ?? 0))
            let e = c["expected"] as? [String: Any] ?? [:]
            assertNear(TestSupport.number(e["change7d"]), f.change7d, "\(name) 7 T")
            assertNear(TestSupport.number(e["change30d"]), f.change30d, "\(name) 30 T")
            assertNear(TestSupport.number(e["fromHigh30d"]), f.fromHigh30d, "\(name) Hoch")
        }
    }

    func testBounds() throws {
        for c in TestSupport.list(try TestSupport.fixture("compare")["bounds"]) {
            let name = c["name"] as? String ?? ""
            let series = (c["series"] as? [Any] ?? []).map { ($0 as? [Any] ?? []).map(point) }
            let b = CompareMath.bounds(series)
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(b, name)
                continue
            }
            XCTAssertEqual(b?.minTime, Int64(TestSupport.number(e["minTime"]) ?? -1), name)
            XCTAssertEqual(b?.maxTime, Int64(TestSupport.number(e["maxTime"]) ?? -1), name)
            XCTAssertEqual(b?.minPercent ?? .nan, TestSupport.number(e["minPercent"]) ?? 0, accuracy: 1e-9, name)
            XCTAssertEqual(b?.maxPercent ?? .nan, TestSupport.number(e["maxPercent"]) ?? 0, accuracy: 1e-9, name)
        }
    }
}
