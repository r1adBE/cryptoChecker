import XCTest
@testable import CryptoChecker

/// Seit Build 104 (F-25): Zeile «Background errors» — gleiche Fälle wie `BackgroundErrorsTest.kt`.
final class BackgroundErrorsTests: XCTestCase {
    private typealias S = TestSupport

    func testAreasMatchFixture() throws {
        let data = try S.fixture("background_errors")
        XCTAssertEqual(Set(data["areas"] as? [String] ?? []), BackgroundErrors.areas)
    }

    func testSharedLines() throws {
        let data = try S.fixture("background_errors")
        for c in S.list(data["lines"]) {
            let raw = c["counts"] as? [String: Any] ?? [:]
            let counts = raw.mapValues { Int(S.number($0) ?? 0) }
            let lastAt = c["lastAt"] is String ? S.millis(c["lastAt"]) : 0
            XCTAssertEqual(BackgroundErrors.line(BackgroundErrorSummary(counts: counts, lastAt: lastAt)),
                           c["line"] as? String, c["name"] as? String ?? "")
        }
    }

    func testCounterCountsOnlyKnownAreas() throws {
        let suite = "BackgroundErrorsTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        BackgroundErrorCounter.record(BackgroundErrors.storage, now: 1_000, defaults: defaults)
        BackgroundErrorCounter.record(BackgroundErrors.storage, now: 1_500, defaults: defaults)
        BackgroundErrorCounter.record("frei erfundener Text", now: 2_000, defaults: defaults)
        let summary = BackgroundErrorCounter.summary(defaults: defaults)
        XCTAssertEqual(summary.counts, [BackgroundErrors.storage: 2, BackgroundErrors.other: 1])
        XCTAssertEqual(summary.lastAt, 2_000)
    }
}
