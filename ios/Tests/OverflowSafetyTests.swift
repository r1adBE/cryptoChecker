import XCTest
@testable import CryptoChecker

/// Riesige Werte aus einer (manipulierten oder fremden) Sicherung: werden gekürzt und lassen keine
/// Zeitrechnung überlaufen (Swift bricht bei Int64-Überlauf ab). Wie `OverflowSafetyTest.kt`.
final class OverflowSafetyTests: XCTestCase {

    private let now: Int64 = 1_791_000_000_000

    func testSafeMillisSaturates() {
        XCTAssertEqual(SafeMillis.minutes(15), 15 * 60_000)
        XCTAssertEqual(SafeMillis.seconds(-2), -2_000)
        XCTAssertEqual(SafeMillis.minutes(Int.max), SafeMillis.limit)
        XCTAssertEqual(SafeMillis.minutes(Int.min), -SafeMillis.limit)
        XCTAssertEqual(SafeMillis.hours(Int.max / 1_000_000_000), SafeMillis.limit)
        // Summe mit einem Zeitstempel läuft nicht über
        XCTAssertGreaterThan(now + SafeMillis.minutes(Int.max), now)
    }

    func testBackupWithHugeSettingsRestoresClamped() {
        let backup: [String: Any] = [
            "backgroundIntervalMinutes": NSNumber(value: Int64.max),
            "liveIntervalSeconds": NSNumber(value: Int64.max),
            "alarmCooldownMinutes": NSNumber(value: Int64.max),
        ]
        let s = BackupManager.applySettings(backup, onto: AppSettings())
        XCTAssertEqual(s.backgroundIntervalMinutes, AppSettings.maxBackgroundIntervalMinutes)
        XCTAssertEqual(s.liveIntervalSeconds, AppSettings.maxLiveIntervalSeconds)
        XCTAssertEqual(s.alarmCooldownMinutes, AppSettings.maxAlarmCooldownMinutes)
        // Untergrenzen bleiben
        let low = BackupManager.applySettings(["backgroundIntervalMinutes": 1, "liveIntervalSeconds": 1,
                                               "alarmCooldownMinutes": -5], onto: AppSettings())
        XCTAssertEqual(low.backgroundIntervalMinutes, AppSettings.minBackgroundIntervalMinutes)
        XCTAssertEqual(low.liveIntervalSeconds, AppSettings.minLiveIntervalSeconds)
        XCTAssertEqual(low.alarmCooldownMinutes, 0)
        // Werte der Auswahl bleiben unverändert
        let kept = BackupManager.applySettings(["backgroundIntervalMinutes": 60, "liveIntervalSeconds": 300,
                                                "alarmCooldownMinutes": 15], onto: AppSettings())
        XCTAssertEqual(kept.backgroundIntervalMinutes, 60)
        XCTAssertEqual(kept.liveIntervalSeconds, 300)
        XCTAssertEqual(kept.alarmCooldownMinutes, 15)
    }

    func testStoredSettingsDecodeClamped() throws {
        let json = #"{"backgroundIntervalMinutes": 9223372036854775807, "liveIntervalSeconds": 9223372036854775807, "alarmCooldownMinutes": 9223372036854775807}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertEqual(s.backgroundIntervalMinutes, AppSettings.maxBackgroundIntervalMinutes)
        XCTAssertEqual(s.liveIntervalSeconds, AppSettings.maxLiveIntervalSeconds)
        XCTAssertEqual(s.alarmCooldownMinutes, AppSettings.maxAlarmCooldownMinutes)
    }

    func testStoredAlarmWindowDecodesClamped() throws {
        let json = #"{"id": 1, "watchId": 2, "condition": "MOVE_PERCENT_WINDOW", "threshold": 5, "windowHours": 9223372036854775807}"#
        let alarm = try JSONDecoder().decode(Alarm.self, from: Data(json.utf8))
        XCTAssertEqual(alarm.windowHours, Alarm.maxWindowHours)
    }

    func testTimeRulesDoNotTrapOnHugeValues() {
        // Planung beim Start (früher Absturz bei jedem Start)
        let begin = RefreshSchedule.earliestBegin(lastRunMillis: now - 1_000, intervalMinutes: Int.max, now: now)
        XCTAssertGreaterThan(begin.timeIntervalSince1970, TimeInterval(now) / 1000)
        _ = RefreshSchedule.nextBegin(lastRunMillis: now, intervalMinutes: Int.max, zoneLastRunMillis: now, now: now)
        // Veraltet-Regel
        XCTAssertGreaterThan(OutdatedRule.afterMillis(backgroundIntervalMinutes: Int.max), 0)
        // Vergleichsfenster
        XCTAssertGreaterThan(MoveWindow.maxAgeMillis(Int.max), 0)
        XCTAssertGreaterThan(DerivativesAlarm.oiMaxAgeMillis(Int.max), 0)
        // Ruhezeit: riesig = noch in der Ruhezeit, kein Absturz
        XCTAssertTrue(NearExtreme.inCooldown(lastTriggeredAt: now - 1_000, now: now, cooldownMinutes: Int.max))
    }
}
