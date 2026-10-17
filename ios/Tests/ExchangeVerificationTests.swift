import XCTest
@testable import CryptoChecker

/// Datum «zuletzt gegen echte Antworten geprüft» passt zu den Aufnahmen — wie `ExchangeVerificationTest.kt`.
final class ExchangeVerificationTests: XCTestCase {

    func testLastVerifiedIsNotBeforeNewestRecording() throws {
        let bundle = Bundle(for: ExchangeVerificationTests.self)
        let urls = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("recorded_") }
        XCTAssertFalse(urls.isEmpty, "recorded responses")
        var days: [LocalDay] = []
        for url in urls {
            let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
            let at = try XCTUnwrap((object as? [String: Any])?["recordedAt"] as? String, url.lastPathComponent)
            // «2026-10-16T23:27:56Z» → UTC-Datum (die ersten 10 Zeichen)
            days.append(try XCTUnwrap(LocalDay(iso: String(at.prefix(10))), at))
        }
        let newest = try XCTUnwrap(days.max())
        let verified = ExchangeVerification.lastVerified
        XCTAssertFalse(verified < newest, "ExchangeVerification.lastVerified \(verified) is before the newest recording \(newest)")
        // Nicht in der Zukunft der Aufnahmen erfunden: höchstens einen Tag nach der neuesten (Aufnahme kurz vor Mitternacht)
        XCTAssertFalse(newest.plusDays(1) < verified)
    }
}
