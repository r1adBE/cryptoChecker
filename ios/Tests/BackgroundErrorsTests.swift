import XCTest
@testable import CryptoChecker

/// Zeile «Background errors» — gleiche Fälle wie `BackgroundErrorsTest.kt`.
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

    func testSharedKinds() throws {
        let data = try S.fixture("background_errors")
        XCTAssertEqual(Int(S.number(data["maxKinds"]) ?? 0), BackgroundErrors.maxKinds)
        for c in S.list(data["kinds"]) {
            XCTAssertEqual(BackgroundErrors.kind(c["raw"] as? String ?? ""), c["kind"] as? String, c["raw"] as? String ?? "")
        }
        for c in S.list(data["kindSlots"]) {
            let known = Set(c["known"] as? [String] ?? [])
            XCTAssertEqual(BackgroundErrors.kindSlot(known: known, kind: c["kind"] as? String ?? ""), c["slot"] as? String,
                           c["name"] as? String ?? "")
        }
        for c in S.list(data["kindLines"]) {
            let raw = c["kinds"] as? [String: Any] ?? [:]
            let kinds = raw.mapValues { area in ((area as? [String: Any]) ?? [:]).mapValues { Int(S.number($0) ?? 0) } }
            XCTAssertEqual(BackgroundErrors.kindsLine(BackgroundErrorSummary(kinds: kinds)), c["line"] as? String,
                           c["name"] as? String ?? "")
        }
    }

    private struct SampleError: Error { let message = "geheim 0x12ab" }
    /// Reiner Swift-Fehler (URLError wird je nach Laufzeit als «NSError» gemeldet).
    private enum OtherError: Error { case timedOut }

    func testCounterCountsKindsWithoutMessage() throws {
        let suite = "BackgroundErrorsKindsTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        BackgroundErrorCounter.record(BackgroundErrors.refresh, now: 1_000, defaults: defaults, error: SampleError())
        BackgroundErrorCounter.record(BackgroundErrors.refresh, now: 1_000, defaults: defaults, error: OtherError.timedOut)
        BackgroundErrorCounter.record(BackgroundErrors.storage, now: 1_000, defaults: defaults,
                                      kind: BackgroundErrors.saveFailedKind)
        BackgroundErrorCounter.record(BackgroundErrors.storage, now: 1_000, defaults: defaults)
        let summary = BackgroundErrorCounter.summary(defaults: defaults)
        XCTAssertEqual(summary.kinds[BackgroundErrors.refresh], ["SampleError": 1, "OtherError": 1])
        XCTAssertEqual(summary.kinds[BackgroundErrors.storage], [BackgroundErrors.saveFailedKind: 1])
        XCTAssertEqual(summary.counts[BackgroundErrors.storage], 2)
        XCTAssertFalse(BackgroundErrors.kindsLine(summary)?.contains("geheim") ?? true)
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
