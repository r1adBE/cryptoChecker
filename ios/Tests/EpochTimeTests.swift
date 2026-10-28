import XCTest
@testable import CryptoChecker

/// Zeitstempel der Börsen nach Grösse (s/ms/µs/ns) → ms — wie `EpochTimeTest.kt`.
final class EpochTimeTests: XCTestCase {

    func testConvertsByMagnitudeStepwise() {
        let ms: Int64 = 1_728_540_000_123
        XCTAssertEqual(EpochTime.parseTimeToMillis(1_728_540_000), 1_728_540_000_000)
        XCTAssertEqual(EpochTime.parseTimeToMillis(ms), ms)
        XCTAssertEqual(EpochTime.parseTimeToMillis(1_728_540_000_123_456), ms)
        // Nanosekunden (Phemex): zweimal durch 1000, nicht nur einmal
        XCTAssertEqual(EpochTime.parseTimeToMillis(1_728_540_000_123_456_789), ms)
    }
}
