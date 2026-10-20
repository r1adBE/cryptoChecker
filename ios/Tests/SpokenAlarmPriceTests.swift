import XCTest
@testable import CryptoChecker

/// Alarm-Ansage: Kurs in der Alarmwährung nennt diese Währung (`SpokenText.spokenAlarmPrice`) — Spiegel von
/// SpokenAlarmPriceTest.kt.
final class SpokenAlarmPriceTests: XCTestCase {
    func testCurrencyOnlyWhenDifferentFromQuote() {
        XCTAssertEqual(SpokenText.spokenAlarmPrice("60000", quote: "USDT", currency: nil), "60000")
        XCTAssertEqual(SpokenText.spokenAlarmPrice("60000", quote: "USDT", currency: "usdt"), "60000")
        XCTAssertEqual(SpokenText.spokenAlarmPrice("60000", quote: "USDT", currency: "chf"), "60000 CHF")
        XCTAssertEqual(SpokenText.spokenAlarmPrice("60000", quote: "USDT", currency: " "), "60000")
    }
}
