import XCTest
@testable import CryptoChecker

/// Abstürze, Hänger und Enden laut iOS (`ProcessExitLog`, MetricKit): nur Codes, höchstens 5, neueste zuerst; im
/// Diagnosebericht als Abschnitt «Process exits» wie Android.
final class ProcessExitLogTests: XCTestCase {
    func testCrashNamesExceptionAndSignal() {
        XCTAssertEqual(ProcessExitLog.crash(at: 1, exceptionType: 1, signal: 11).detail, "EXC_BAD_ACCESS SIGSEGV")
        XCTAssertEqual(ProcessExitLog.crash(at: 1, exceptionType: 99, signal: nil).detail, "exc_99")
        XCTAssertEqual(ProcessExitLog.crash(at: 1, exceptionType: nil, signal: nil).detail, "-")
    }

    func testTimedUsesSecondsWithOneDecimal() {
        XCTAssertEqual(ProcessExitLog.timed(at: 1, kind: "HANG", seconds: 3.24).detail, "3.2 s")
        XCTAssertEqual(ProcessExitLog.timed(at: 1, kind: "DISK_WRITE_EXCEPTION", seconds: nil).detail, "-")
    }

    func testExitsOnlyNonZeroSortedByReason() {
        let entries = ProcessExitLog.exits(at: 5, scope: "background", counts: ["WATCHDOG": 0, "MEMORY_LIMIT": 2, "CPU_LIMIT": 1])
        XCTAssertEqual(entries.map(\.kind), ["CPU_LIMIT", "MEMORY_LIMIT"])
        XCTAssertEqual(entries.last?.detail, "background 2")
    }

    func testMergedKeepsNewestFiveWithoutDuplicates() {
        let old = (1...4).map { ProcessExitEntry(at: Int64($0), kind: "HANG", detail: "1.0 s") }
        let new = [ProcessExitEntry(at: 9, kind: "CRASH", detail: "-"), ProcessExitEntry(at: 8, kind: "CRASH", detail: "-"),
                   ProcessExitEntry(at: 4, kind: "HANG", detail: "1.0 s")]
        let merged = ProcessExitLog.merged(new, into: old)
        XCTAssertEqual(merged.map(\.at), [9, 8, 4, 3, 2])
    }

    func testStoredRoundTrip() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "ProcessExitLogTests"))
        defaults.removePersistentDomain(forName: "ProcessExitLogTests")
        ProcessExitLog.add([ProcessExitLog.crash(at: 7, exceptionType: 10, signal: 6)], defaults)
        XCTAssertEqual(ProcessExitLog.load(defaults).first?.detail, "EXC_CRASH SIGABRT")
        defaults.removePersistentDomain(forName: "ProcessExitLogTests")
    }
}
