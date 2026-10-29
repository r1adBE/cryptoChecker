import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `Parity/server_values.json` — wie `ServerValuesParityTest.kt`: unplausible Server-Werte werden
/// verworfen statt überzulaufen (hier stürzte die App dort ab) — Sekunden → ms, Zeitangabe unbekannter Einheit, Blockhöhe.
final class ServerValuesParityTests: XCTestCase {
    private func data() throws -> [String: Any] { try TestSupport.fixture("server_values") }

    private func int64(_ value: Any?) -> Int64? { (value as? String).flatMap { Int64($0) } }

    func testSecondsToMillis() throws {
        let cases = TestSupport.list(try data()["secondsToMillis"])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for c in cases {
            let seconds = try XCTUnwrap(int64(c["seconds"]), "\(c)")
            XCTAssertEqual(EpochTime.secondsToMillis(seconds), int64(c["expected"]), "\(c)")
        }
    }

    func testParseTimeToMillis() throws {
        for c in TestSupport.list(try data()["parseTimeToMillis"]) {
            let time = try XCTUnwrap(int64(c["time"]), "\(c)")
            XCTAssertEqual(EpochTime.parseTimeToMillis(time), int64(c["expected"]), "\(c)")
        }
    }

    func testHalving() throws {
        for c in TestSupport.list(try data()["halving"]) {
            let height = try XCTUnwrap((c["tipHeight"] as? NSNumber)?.intValue, "\(c)")
            let estimate = HalvingMath.estimate(tipHeight: height, avgBlockSeconds: 600, nowMillis: 1_700_000_000_000)
            let expected = c["expected"] as? [String: Any]
            XCTAssertEqual(estimate?.height, (expected?["height"] as? NSNumber)?.intValue, "\(c)")
            XCTAssertEqual(estimate?.remainingBlocks, (expected?["remaining"] as? NSNumber)?.intValue, "\(c)")
        }
    }
}
