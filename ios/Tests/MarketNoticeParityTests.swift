import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/market_notice.json` (Android: `MarketNoticeParityTest.kt`): Der gemerkte Stand einer
/// Markt-Meldung gilt nur für die Grenze, mit der er gespeichert wurde — fehlt sie oder ist sie eine andere (auch 0 =
/// zwischendurch aus), ist die Meldung wieder scharf. Dazu die eingeschalteten Kennzahl-Grenzen, die erneuten Versuche
/// der Prüfung und die Zeitgrenzen des Markt-Tabs.
final class MarketNoticeParityTests: XCTestCase {
    private typealias S = TestSupport

    private func cases(_ key: String) throws -> [[String: Any]] {
        let list = S.list(try S.fixture("market_notice")[key])
        XCTAssertFalse(list.isEmpty, key)
        return list
    }

    private func int(_ value: Any?) -> Int? { S.number(value).map { Int($0) } }

    func testBoundChanged() throws {
        for c in try cases("boundChanged") {
            XCTAssertEqual(MarketNoticeGate.boundChanged(stored: int(c["stored"]), current: int(c["current"]) ?? 0),
                           c["changed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testIndicatorWasCrossed() throws {
        for c in try cases("indicatorWasCrossed") {
            XCTAssertEqual(IndicatorAlertRules.wasCrossed(storedCrossed: c["storedCrossed"] as? Bool ?? false,
                                                          storedThreshold: int(c["storedThreshold"]),
                                                          current: int(c["current"]) ?? 0),
                           c["wasCrossed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testGasArmedFor() throws {
        for c in try cases("gasArmedFor") {
            XCTAssertEqual(GasAlertCheck.armedFor(storedArmed: c["storedArmed"] as? Bool ?? true,
                                                  storedThreshold: S.number(c["storedThreshold"]),
                                                  threshold: S.number(c["threshold"]) ?? 0),
                           c["armed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testDepegArmedFor() throws {
        for c in try cases("depegArmedFor") {
            XCTAssertEqual(DepegAlarm.armedFor(storedArmed: c["storedArmed"] as? Bool ?? true,
                                               storedTenths: int(c["storedTenths"]), tenths: int(c["tenths"]) ?? 0),
                           c["armed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testIndicatorActive() throws {
        for c in try cases("indicatorActive") {
            let name = c["name"] as? String ?? ""
            var stored: [String: Int] = [:]
            for (key, value) in c["stored"] as? [String: Any] ?? [:] { stored[key] = int(value) }
            let actual = IndicatorAlertRules.active(stored)
            let expected = c["active"] as? [String: Any] ?? [:]
            XCTAssertEqual(Set(actual.keys.map(\.rawValue)), Set(expected.keys), name)
            for (key, value) in expected {
                let kind = try XCTUnwrap(IndicatorAlertKind(rawValue: key), name)
                XCTAssertEqual(try XCTUnwrap(actual[kind], name), S.number(value) ?? 0, accuracy: 1e-12, name)
            }
        }
    }

    func testZoneRetry() throws {
        let retry = try XCTUnwrap(try S.fixture("market_notice")["zoneRetry"] as? [String: Any])
        XCTAssertEqual(int(retry["maxRetries"]), MarketNoticeGate.maxRetries)
        let delays = (retry["delaysMillis"] as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } }
        XCTAssertEqual(delays, (1...MarketNoticeGate.maxRetries).map { MarketNoticeGate.retryDelayMillis(attempt: $0) })
        XCTAssertEqual(delays.first, MarketNoticeGate.retryBaseMillis)
    }

    func testMarketTabTimeouts() throws {
        let expected = try XCTUnwrap(try S.fixture("market_notice")["marketTabTimeouts"] as? [String: Any])
        XCTAssertEqual(CycleCachePolicy.timeoutMillis, expected.mapValues { Int64(S.number($0) ?? 0) })
    }
}
