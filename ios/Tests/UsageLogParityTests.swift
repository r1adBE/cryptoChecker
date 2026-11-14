import os
import XCTest
@testable import CryptoChecker

/// «Akku & Aktivität»: Eimer je Stunde, 24-h-Summe, feste Grenzen, Treiber, Datei- und Berichtsformat wie Android
/// (`UsageLogParityTest.kt`, `usage_log.json`), dazu der Zähler (`UsageMeter`) mit Datei und die Läufe über
/// «Letzte Prüfungen» (`CheckRunStore.add`, ohne die offene App) — wie Android `UsageMeterTest`.
final class UsageLogParityTests: XCTestCase {
    private func int64(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? 0 }
    private func int(_ value: Any?) -> Int { (value as? NSNumber)?.intValue ?? 0 }

    private func buckets(_ raw: Any?) -> [UsageBucket] {
        ((raw as? [Any]) ?? []).map { row in
            let r = (row as? [Any]) ?? []
            return UsageBucket(hour: int64(r[0]), runs: int(r[1]), requests: int(r[2]), bytes: int64(r[3]))
        }
    }

    func testConstantsMatchFixture() throws {
        let c = try XCTUnwrap(try TestSupport.fixture("usage_log")["constants"] as? [String: Any])
        XCTAssertEqual(int64(c["hourMillis"]), UsageLog.hourMillis)
        XCTAssertEqual(int64(c["windowHours"]), UsageLog.windowHours)
        XCTAssertEqual(int64(c["keepHours"]), UsageLog.keepHours)
        XCTAssertEqual(int(c["mediumRuns"]), UsageLog.mediumRuns)
        XCTAssertEqual(int(c["highRuns"]), UsageLog.highRuns)
        XCTAssertEqual(int(c["mediumRequests"]), UsageLog.mediumRequests)
        XCTAssertEqual(int(c["highRequests"]), UsageLog.highRequests)
        XCTAssertEqual(int64(c["mediumBytes"]), UsageLog.mediumBytes)
        XCTAssertEqual(int64(c["highBytes"]), UsageLog.highBytes)
    }

    func testHourMergeAndTotalsMatchFixture() throws {
        let data = try TestSupport.fixture("usage_log")
        let now = int64(data["now"])
        for c in TestSupport.list(data["hourOf"]) {
            XCTAssertEqual(UsageLog.hourOf(int64(c["millis"])), int64(c["hour"]), "\(c["millis"] ?? "")")
        }
        let merges = TestSupport.list(data["merge"])
        XCTAssertGreaterThanOrEqual(merges.count, 5)
        for c in merges {
            XCTAssertEqual(UsageLog.merge(buckets(c["a"]), buckets(c["b"]), now: now), buckets(c["expected"]),
                           "\(c["name"] ?? "")")
        }
        for c in TestSupport.list(data["add"]) {
            let added = UsageLog.add(buckets(c["buckets"]), now: now, runs: int(c["runs"]), requests: int(c["requests"]),
                                     bytes: int64(c["bytes"]))
            XCTAssertEqual(added, buckets(c["expected"]), "\(c["name"] ?? "")")
        }
        for c in TestSupport.list(data["totals"]) {
            let e = (c["expected"] as? [Any]) ?? []
            XCTAssertEqual(UsageLog.totals(buckets(c["buckets"]), now: now),
                           UsageTotals(runs: int(e[0]), requests: int(e[1]), bytes: int64(e[2])), "\(c["name"] ?? "")")
        }
    }

    func testLevelAndDriverMatchFixture() throws {
        let data = try TestSupport.fixture("usage_log")
        let levels = TestSupport.list(data["level"])
        XCTAssertGreaterThanOrEqual(levels.count, 10)
        for c in levels {
            let totals = UsageTotals(runs: int(c["runs"]), requests: int(c["requests"]), bytes: int64(c["bytes"]))
            XCTAssertEqual(UsageLog.level(totals).rawValue, c["expected"] as? String, "\(totals)")
        }
        for c in TestSupport.list(data["driver"]) {
            let driver = UsageLog.driver(backgroundPlanned: c["planned"] as? Bool ?? false, exactActive: c["exact"] as? Bool ?? false)
            XCTAssertEqual(driver.rawValue, c["expected"] as? String, "\(c)")
        }
    }

    func testFileAndReportMatchFixture() throws {
        let data = try TestSupport.fixture("usage_log")
        for c in TestSupport.list(data["decode"]) {
            XCTAssertEqual(UsageLog.decode(c["text"] as? String), buckets(c["expected"]), "\(c["name"] ?? "")")
        }
        XCTAssertTrue(UsageLog.decode(nil).isEmpty)
        for c in TestSupport.list(data["encode"]) {
            let list = buckets(c["buckets"])
            XCTAssertEqual(UsageLog.encode(list), c["text"] as? String)
            XCTAssertEqual(UsageLog.decode(UsageLog.encode(list)), list)
        }
        for c in TestSupport.list(data["utf8"]) {
            XCTAssertEqual(UsageLog.utf8Length(c["text"] as? String ?? ""), int64(c["bytes"]), "\(c["text"] ?? "")")
        }
        for c in TestSupport.list(data["reportEncode"]) {
            let report = UsageReport(at: int64(c["at"]), totals: UsageTotals(runs: int(c["runs"]), requests: int(c["requests"]),
                                                                             bytes: int64(c["bytes"])))
            XCTAssertEqual(UsageLog.encodeReport(report), c["text"] as? String)
            XCTAssertEqual(UsageLog.decodeReport(UsageLog.encodeReport(report)), report)
        }
        for c in TestSupport.list(data["reportDecode"]) {
            let decoded = UsageLog.decodeReport(c["text"] as? String)
            if let v = c["expected"] as? [Any] {
                XCTAssertEqual(decoded, UsageReport(at: int64(v[0]), totals: UsageTotals(runs: int(v[1]), requests: int(v[2]),
                                                                                         bytes: int64(v[3]))), "\(c["name"] ?? "")")
            } else {
                XCTAssertNil(decoded, "\(c["name"] ?? "")")
            }
        }
        XCTAssertNil(UsageLog.decodeReport(nil))
    }

    /// Erst nach einer Minute oder mit `flush` in der Datei; ein zweiter Zähler (anderer Prozess) sieht das Geschriebene.
    func testMeterWritesAtMostEveryMinute() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("usage-\(UUID().uuidString).txt")
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = TestClock(1_700_000_000_000)
        let meter = UsageMeter(url: url, now: { clock.now })
        // Leer geschrieben: die nächste Minute schreibt kein Zähler von selbst (sonst abseits, nicht vorhersehbar)
        meter.flush()
        meter.countRequest(bytes: 100)
        XCTAssertEqual(UsageMeter(url: url, now: { clock.now }).totals(), .zero)
        XCTAssertEqual(meter.totals(), UsageTotals(runs: 0, requests: 1, bytes: 100))
        meter.flush()
        XCTAssertEqual(UsageMeter(url: url, now: { clock.now }).totals(), UsageTotals(runs: 0, requests: 1, bytes: 100))
        clock.advance(30_000)
        meter.countBytes(50)
        XCTAssertEqual(meter.totals(), UsageTotals(runs: 0, requests: 1, bytes: 150))
        XCTAssertEqual(UsageMeter(url: url, now: { clock.now }).totals(), UsageTotals(runs: 0, requests: 1, bytes: 100))
        meter.flush()
        XCTAssertEqual(UsageMeter(url: url, now: { clock.now }).totals(), UsageTotals(runs: 0, requests: 1, bytes: 150))
        clock.advance(25 * UsageLog.hourMillis)
        XCTAssertEqual(meter.totals(), .zero)
    }

    func testRunsCountThroughCheckRunsWithoutForeground() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("usage-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let meter = UsageMeter(url: dir.appendingPathComponent(UsageMeter.fileName))
        for trigger in CheckTrigger.allCases {
            CheckRunStore.add(CheckRun(at: EpochTime.nowMillis, trigger: trigger, result: .ok, durationMillis: 1_000),
                              to: dir.appendingPathComponent(CheckRunStore.fileName), meter: meter)
        }
        XCTAssertEqual(meter.totals().runs, CheckTrigger.allCases.count - 1)
    }

    /// Stand der Uhr: nur ein neuerer ersetzt den gemerkten.
    func testWatchReportKeepsTheNewest() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("watch-usage-\(UUID().uuidString).txt")
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertNil(WatchUsageStore.load(from: url))
        WatchUsageStore.take("1;2000;3;4;5", to: url)
        WatchUsageStore.take("1;1000;9;9;9", to: url)
        WatchUsageStore.take("kaputt", to: url)
        XCTAssertEqual(WatchUsageStore.load(from: url), UsageReport(at: 2000, totals: UsageTotals(runs: 3, requests: 4, bytes: 5)))
    }
}

/// Einstellbare Uhr für `UsageMeter` (aus mehreren Fäden gelesen).
private final class TestClock: Sendable {
    private let value: OSAllocatedUnfairLock<Int64>
    init(_ start: Int64) { value = OSAllocatedUnfairLock(initialState: start) }
    var now: Int64 { value.withLock { $0 } }
    func advance(_ millis: Int64) { value.withLock { $0 += millis } }
}
