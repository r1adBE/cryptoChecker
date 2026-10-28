import XCTest
@testable import CryptoChecker

/// Wie `CheckRunLogTest.kt` und `CheckRunStoreTest.kt`.
final class CheckRunLogTests: XCTestCase {

    private func run(_ at: Int64, _ trigger: CheckTrigger = .worker, _ result: CheckResult = .ok) -> CheckRun {
        CheckRun(at: at, trigger: trigger, result: result, durationMillis: 1_200)
    }

    func testRingBufferKeepsNewestTwenty() {
        var runs: [CheckRun] = []
        for i in 1...25 { runs = CheckRunLog.add(runs, run(Int64(i))) }
        XCTAssertEqual(runs.count, CheckRunLog.max)
        XCTAssertEqual(runs.first?.at, 25)
        XCTAssertEqual(runs.last?.at, 6)
    }

    func testAddPrepends() {
        let runs = CheckRunLog.add([run(1)], run(2, .exact))
        XCTAssertEqual(runs.map(\.at), [2, 1])
        XCTAssertEqual(runs.first?.trigger, .exact)
    }

    func testResultFromCounts() {
        XCTAssertEqual(CheckRunLog.result(checked: 0, failed: 0), .ok)
        XCTAssertEqual(CheckRunLog.result(checked: 5, failed: 0), .ok)
        XCTAssertEqual(CheckRunLog.result(checked: 5, failed: 2), .partial)
        XCTAssertEqual(CheckRunLog.result(checked: 5, failed: 5), .failed)
    }

    /// «Letzte Prüfung»: nur geplante Läufe, die nicht gescheitert sind (misslungenes Vormerken, nichts gespeichert);
    /// übersprungene zählen (das System hat die Aufgabe gestartet). Wie Android.
    func testLastBackgroundCheckSkipsFailedAndUnscheduledRuns() {
        let runs = [run(50, .bgTask, .failed), run(40, .widget), run(30, .foreground), run(20, .bgTask, .skipped),
                    run(10, .bgTask)]
        XCTAssertEqual(CheckRunLog.lastBackgroundCheck(runs), 20)
        XCTAssertNil(CheckRunLog.lastBackgroundCheck([run(5, .bgTask, .failed), run(4, .widget)]))
        XCTAssertNil(CheckRunLog.lastBackgroundCheck([]))
        XCTAssertEqual(CheckRunLog.lastBackgroundCheck([run(7, .worker, .partial)]), 7)
    }

    /// Nur gescheiterte geplante Läufe gespeichert: Zeit des neuesten (Warnung statt «noch nie»). Wie Android.
    func testLastFailedBackgroundCheckOnlyWhenNoScheduledRunSucceeded() {
        let failing = (0..<CheckRunLog.max).map { run(100 - Int64($0), .bgTask, .failed) }
        XCTAssertEqual(CheckRunLog.lastFailedBackgroundCheck(failing), 100)
        // Widget und App zählen weder als gelungen noch als gescheitert
        XCTAssertEqual(CheckRunLog.lastFailedBackgroundCheck([run(95, .widget), run(90, .bgTask, .failed)]), 90)
        // Eine gelungene oder übersprungene (eben geprüft) genügt: dann gilt lastBackgroundCheck
        XCTAssertNil(CheckRunLog.lastFailedBackgroundCheck(Array(failing.prefix(5)) + [run(3, .bgTask, .skipped)]))
        XCTAssertNil(CheckRunLog.lastFailedBackgroundCheck([run(5, .foreground, .failed)]))
        XCTAssertNil(CheckRunLog.lastFailedBackgroundCheck([]))
    }

    func testEncodeDecodeRoundTrip() {
        let runs = [
            CheckRun(at: 1_700_000_000_000, trigger: .exact, result: .partial, durationMillis: 2_345),
            CheckRun(at: 1_699_999_000_000, trigger: .bgTask, result: .skipped, durationMillis: 0),
            CheckRun(at: 1_699_998_000_000, trigger: .widget, result: .failed, durationMillis: 15_000),
            CheckRun(at: 1_699_997_000_000, trigger: .foreground, result: .ok, durationMillis: 800),
        ]
        let text = CheckRunLog.encode(runs)
        XCTAssertEqual(text.components(separatedBy: "\n").first, "1700000000000;exact;partial;2345")
        XCTAssertEqual(CheckRunLog.decode(text), runs)
    }

    func testDecodeSkipsBadLines() {
        let text = "1;worker;ok;5\nkaputt\n2;unknown;ok;5\n3;worker;meh;5\n4;worker;ok\n\n5;exact;failed;-3"
        let runs = CheckRunLog.decode(text)
        XCTAssertEqual(runs.map(\.at), [1, 5])
        XCTAssertEqual(runs.last?.durationMillis, 0)
        XCTAssertTrue(CheckRunLog.decode(nil).isEmpty)
        XCTAssertTrue(CheckRunLog.decode("").isEmpty)
    }

    func testDecodeCapsAtMax() {
        let text = (1...30).map { "\($0);worker;ok;1" }.joined(separator: "\n")
        XCTAssertEqual(CheckRunLog.decode(text).count, CheckRunLog.max)
    }

    func testStorePersistsAndKeepsTwenty() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent(CheckRunStore.fileName)
        XCTAssertTrue(CheckRunStore.load(from: url).isEmpty)
        for i in 1...25 { CheckRunStore.add(run(Int64(i)), to: url) }
        let loaded = CheckRunStore.load(from: url)
        XCTAssertEqual(loaded.count, CheckRunLog.max)
        XCTAssertEqual(loaded.first?.at, 25)
        XCTAssertEqual(BackupExclusion.isExcluded(url), true)
    }
}

/// Wie `BackgroundScheduleTest.kt`.
final class BackgroundScheduleTests: XCTestCase {

    private let now: Int64 = 1_700_000_000_000
    private let window = BackgroundSchedule.dedupeWindowMillis
    private let interval = BackgroundSchedule.exactIntervalMillis

    func testExactStateOrder() {
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: false, permitted: false, hasWork: false), .off)
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: false, permitted: true, hasWork: true), .off)
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: true, permitted: false, hasWork: true), .permissionMissing)
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: true, permitted: false, hasWork: false), .permissionMissing)
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: true, permitted: true, hasWork: false), .nothingToCheck)
        XCTAssertEqual(BackgroundSchedule.exactState(enabled: true, permitted: true, hasWork: true), .active)
    }

    func testArmOnlyWhenActive() {
        XCTAssertTrue(BackgroundSchedule.shouldArmExact(enabled: true, permitted: true, hasWork: true))
        XCTAssertFalse(BackgroundSchedule.shouldArmExact(enabled: false, permitted: true, hasWork: true))
        XCTAssertFalse(BackgroundSchedule.shouldArmExact(enabled: true, permitted: false, hasWork: true))
        XCTAssertFalse(BackgroundSchedule.shouldArmExact(enabled: true, permitted: true, hasWork: false))
    }

    func testRearmFifteenMinutesLater() {
        XCTAssertEqual(BackgroundSchedule.nextExactAt(now: now), now + 15 * 60_000)
        XCTAssertGreaterThanOrEqual(interval, 9 * 60_000)
    }

    func testNeedsArm() {
        XCTAssertTrue(BackgroundSchedule.needsArm(armedFor: 0, now: now))
        XCTAssertTrue(BackgroundSchedule.needsArm(armedFor: now, now: now))
        XCTAssertTrue(BackgroundSchedule.needsArm(armedFor: now - 1, now: now))
        XCTAssertFalse(BackgroundSchedule.needsArm(armedFor: now + 60_000, now: now))
        XCTAssertFalse(BackgroundSchedule.needsArm(armedFor: now + interval, now: now))
        XCTAssertTrue(BackgroundSchedule.needsArm(armedFor: now + interval + 1, now: now))
        XCTAssertTrue(BackgroundSchedule.needsArm(armedFor: now + 60_000, now: now, force: true))
    }

    func testDedupeOnlyScheduledWithinWindow() {
        XCTAssertTrue(BackgroundSchedule.shouldSkip(trigger: .worker, lastFinishedAt: now - 1_000, now: now))
        XCTAssertTrue(BackgroundSchedule.shouldSkip(trigger: .exact, lastFinishedAt: now, now: now))
        XCTAssertTrue(BackgroundSchedule.shouldSkip(trigger: .bgTask, lastFinishedAt: now - window + 1, now: now))
        XCTAssertFalse(BackgroundSchedule.shouldSkip(trigger: .exact, lastFinishedAt: now - window, now: now))
        XCTAssertFalse(BackgroundSchedule.shouldSkip(trigger: .worker, lastFinishedAt: 0, now: now))
        XCTAssertFalse(BackgroundSchedule.shouldSkip(trigger: .worker, lastFinishedAt: now + 10_000, now: now))
        XCTAssertFalse(BackgroundSchedule.shouldSkip(trigger: .widget, lastFinishedAt: now - 1_000, now: now))
        XCTAssertFalse(BackgroundSchedule.shouldSkip(trigger: .foreground, lastFinishedAt: now - 1_000, now: now))
    }

    /// Nur eine volle Prüfung zählt für die Sperre — wie Android `BackgroundScheduleTest`.
    func testOnlyFullCheckCountsForDedupe() {
        XCTAssertTrue(BackgroundSchedule.isFullCheck(attempted: 10, failed: 0))
        XCTAssertTrue(BackgroundSchedule.isFullCheck(attempted: 10, failed: 4))
        XCTAssertFalse(BackgroundSchedule.isFullCheck(attempted: 10, failed: 5))
        XCTAssertFalse(BackgroundSchedule.isFullCheck(attempted: 1, failed: 1))
        XCTAssertTrue(BackgroundSchedule.isFullCheck(attempted: 1, failed: 0))
        XCTAssertFalse(BackgroundSchedule.isFullCheck(attempted: 0, failed: 0))
    }

    /// Gescheitertes Vormerken (iOS) nur einmal eintragen, solange der neueste Eintrag schon dieser Fehler ist.
    func testSubmitErrorLoggedOnce() {
        XCTAssertTrue(BackgroundSchedule.shouldLogSubmitError(newest: nil))
        XCTAssertFalse(BackgroundSchedule.shouldLogSubmitError(
            newest: CheckRun(at: now, trigger: .bgTask, result: .failed, durationMillis: 0)))
        XCTAssertTrue(BackgroundSchedule.shouldLogSubmitError(
            newest: CheckRun(at: now, trigger: .bgTask, result: .failed, durationMillis: 1_200)))
        XCTAssertTrue(BackgroundSchedule.shouldLogSubmitError(
            newest: CheckRun(at: now, trigger: .bgTask, result: .ok, durationMillis: 0)))
        XCTAssertTrue(BackgroundSchedule.shouldLogSubmitError(
            newest: CheckRun(at: now, trigger: .widget, result: .failed, durationMillis: 0)))
    }

    func testBatteryHint() {
        XCTAssertTrue(BackgroundSchedule.showBatteryHint(batteryRestricted: true, hasWork: true))
        XCTAssertFalse(BackgroundSchedule.showBatteryHint(batteryRestricted: true, hasWork: false))
        XCTAssertFalse(BackgroundSchedule.showBatteryHint(batteryRestricted: false, hasWork: true))
    }

    // MARK: Abgestimmtes Schreiben (Zähler am App-Symbol, «Letzte Prüfungen»)

    private func tempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("coordinated-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    /// Gleichzeitige Läufe gehen nicht verloren: Lesen und Schreiben sind ein abgestimmter Schritt.
    func testConcurrentAddsKeepEveryRun() throws {
        let url = try tempDir().appendingPathComponent(CheckRunStore.fileName)
        DispatchQueue.concurrentPerform(iterations: 15) { i in
            CheckRunStore.add(CheckRun(at: Int64(i + 1), trigger: .worker, result: .ok, durationMillis: 1_200), to: url)
        }
        XCTAssertEqual(Set(CheckRunStore.load(from: url).map(\.at)), Set((1...15).map(Int64.init)))
    }

    /// Zähler am App-Symbol: ohne Datei ab 0, danach aus der Datei; nie eine Mitteilung verloren.
    func testBadgeCounterStartsAtZeroThenCountsInFile() throws {
        let url = try tempDir().appendingPathComponent("app_icon_badge.txt")
        XCTAssertNil(Notifier.readBadge(at: url))
        XCTAssertEqual(Notifier.incrementBadge(at: url), 1)
        XCTAssertEqual(Notifier.incrementBadge(at: url), 2)
        Notifier.storeBadgeCount(0, at: url)
        XCTAssertEqual(Notifier.readBadge(at: url), 0)
        XCTAssertEqual(Notifier.incrementBadge(at: url), 1)
    }

    func testConcurrentBadgeIncrementsAreNotLost() throws {
        let url = try tempDir().appendingPathComponent("app_icon_badge.txt")
        DispatchQueue.concurrentPerform(iterations: 40) { _ in _ = Notifier.incrementBadge(at: url) }
        XCTAssertEqual(Notifier.readBadge(at: url), 40)
    }
}
