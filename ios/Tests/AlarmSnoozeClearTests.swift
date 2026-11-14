import XCTest
@testable import CryptoChecker

/// Wiederherstellen einer Sicherung: Mitteilungen der ersetzten Paar- und Portfolio-Alarme und alle «1 h später»-Pausen
/// weg — sonst träfe «1 h später» bzw. «Anpassen» einen eingespielten Alarm gleicher Id. Wie Android
/// (`cancelAlarms` + `clearAlarmSnoozes`, `AlarmSnoozeStoreTest`).
final class AlarmSnoozeClearTests: XCTestCase {
    func testClearAllRemovesEverySnooze() throws {
        let suite = "AlarmSnoozeClearTests"
        let store = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { store.removePersistentDomain(forName: suite) }
        let now: Int64 = 1_800_000_000_000
        AlarmSnooze.snooze(1, now: now, store: store)
        AlarmSnooze.snooze(7, now: now, store: store)
        store.set(3, forKey: "other")
        XCTAssertEqual(AlarmSnooze.until(1, store: store), AlarmSnooze.snoozeUntil(now: now))
        AlarmSnooze.clearAll(store: store)
        XCTAssertNil(AlarmSnooze.until(1, store: store))
        XCTAssertNil(AlarmSnooze.until(7, store: store))
        XCTAssertEqual(store.integer(forKey: "other"), 3)
    }

    func testRestoreRemovesReplacedAlarmNotifications() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let source = try String(contentsOf: root.appendingPathComponent("App/Services/BackupManager.swift"), encoding: .utf8)
        XCTAssertTrue(source.contains("removeReplacedAlarmNotifications(previous.snapshot)"))
        XCTAssertTrue(source.contains("old.alarms.forEach { Notifier.cancelAlarm($0.id) }"))
        XCTAssertTrue(source.contains("(old.portfolioAlarms ?? []).forEach { Notifier.cancelPortfolioAlarm($0.id) }"))
        XCTAssertTrue(source.contains("AlarmSnooze.clearAll()"))
    }
}
