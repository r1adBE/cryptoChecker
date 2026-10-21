import XCTest
@testable import CryptoChecker

/// Verfallstag nach Kalender (`FuturesContractType.delivery(asOf:)`): Abrechnung freitags 08:00 UTC, danach gilt der nächste
/// Kontrakt — auch für Monat und Quartal, wenn ihr letzter Freitag vorbei ist. Gleiche Fälle wie `FuturesDeliveryDateTest.kt`.
final class FuturesDeliveryDateTests: XCTestCase {
    private func delivery(_ type: FuturesContractType, _ now: String) -> String? {
        let parser = ISO8601DateFormatter()
        guard let date = type.delivery(asOf: parser.date(from: now)!) else { return nil }
        let out = DateFormatter()
        out.locale = Locale(identifier: "en_US_POSIX")
        out.timeZone = TimeZone(identifier: "UTC")
        out.dateFormat = "yyyy-MM-dd"
        return out.string(from: date)
    }

    func testMidWeekEveryContractLiesAhead() {
        let now = "2026-10-21T12:00:00Z" // Mittwoch
        XCTAssertEqual(delivery(.weekly, now), "2026-10-23")
        XCTAssertEqual(delivery(.biweekly, now), "2026-10-30")
        XCTAssertEqual(delivery(.monthly, now), "2026-10-30")
        XCTAssertEqual(delivery(.bimonthly, now), "2026-11-27")
        XCTAssertEqual(delivery(.quarterly, now), "2026-12-25")
        XCTAssertEqual(delivery(.biquarterly, now), "2027-03-26")
    }

    func testLastFridayOfTheMonthAlreadyPassedMovesToNextMonth() {
        // Samstag nach dem letzten Freitag im Oktober: bis Build 91 noch der 30. Oktober
        let now = "2026-10-31T00:00:00Z"
        XCTAssertEqual(delivery(.monthly, now), "2026-11-27")
        XCTAssertEqual(delivery(.bimonthly, now), "2026-12-25")
        XCTAssertEqual(delivery(.weekly, now), "2026-11-06")
        XCTAssertEqual(delivery(.quarterly, now), "2026-12-25")
    }

    func testOnExpiryFridayTheContractRollsAt0800UTC() {
        XCTAssertEqual(delivery(.weekly, "2026-10-30T07:59:59Z"), "2026-10-30")
        XCTAssertEqual(delivery(.monthly, "2026-10-30T07:59:59Z"), "2026-10-30")
        XCTAssertEqual(delivery(.weekly, "2026-10-30T08:00:00Z"), "2026-11-06")
        XCTAssertEqual(delivery(.biweekly, "2026-10-30T08:00:00Z"), "2026-11-13")
        XCTAssertEqual(delivery(.monthly, "2026-10-30T08:00:00Z"), "2026-11-27")
        XCTAssertEqual(delivery(.bimonthly, "2026-10-30T08:00:00Z"), "2026-12-25")
    }

    func testQuarterRollsAfterItsLastFriday() {
        let now = "2026-12-25T08:00:00Z"
        XCTAssertEqual(delivery(.quarterly, now), "2027-03-26")
        XCTAssertEqual(delivery(.biquarterly, now), "2027-06-25")
        XCTAssertEqual(delivery(.monthly, now), "2027-01-29")
        // Kurz davor noch das laufende Quartal
        XCTAssertEqual(delivery(.quarterly, "2026-12-25T07:00:00Z"), "2026-12-25")
    }

    func testSpotAndPerpetualsHaveNoDelivery() {
        let now = "2026-10-21T12:00:00Z"
        XCTAssertNil(delivery(.none, now))
        XCTAssertNil(delivery(.perpetual, now))
        XCTAssertNil(delivery(.inversePerpetual, now))
    }
}
