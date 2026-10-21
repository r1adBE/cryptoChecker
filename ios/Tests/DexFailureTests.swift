import XCTest
@testable import CryptoChecker

/// Einordnung einer gescheiterten DEX-Suche (`DexFailure`) — wie `DexFailureTest.kt` (Android).
final class DexFailureTests: XCTestCase {

    private func kind(_ error: Error) -> DexFailure {
        DexFailure.of(RefreshReportLogic.classify(MarketFailure.of(error), ConnectionErrors.describe(error)))
    }

    func testUrsachenWieInDerMerkliste() {
        // Zeitüberschreitung zählt wie in der Merkliste als «Keine Verbindung»
        XCTAssertEqual(DexFailure.of(.OFFLINE), .offline)
        XCTAssertEqual(DexFailure.of(.TIMEOUT), .offline)
        XCTAssertEqual(DexFailure.of(.SERVER), .unreachable)
        XCTAssertEqual(DexFailure.of(.RATE_LIMIT), .unreachable)
        XCTAssertEqual(DexFailure.of(.NO_DATA), .other)
        XCTAssertEqual(DexFailure.of(.UNAVAILABLE), .other)
        XCTAssertEqual(DexFailure.of(.OTHER), .other)
    }

    func testFehlerWerdenEingeordnet() {
        XCTAssertEqual(kind(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(kind(URLError(.timedOut)), .offline)
        XCTAssertEqual(kind(NSError(domain: "Test", code: 1)), .other)
    }

    func testTexteDerMerkliste() {
        XCTAssertEqual(DexFailure.offline.messageKey, "watch_error_offline")
        XCTAssertEqual(DexFailure.unreachable.messageKey, "error_market_unreachable_short")
        XCTAssertEqual(DexFailure.other.messageKey, "something_went_wrong")
    }
}
