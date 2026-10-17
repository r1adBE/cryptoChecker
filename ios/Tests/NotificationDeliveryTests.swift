import XCTest
@testable import CryptoChecker

/// Gas- und Aktivitätsmeldungen werden nur verbraucht, wenn sie ankommen (wie Kursalarme,
/// `QuietHours.mayFire`); Portfolio-Alarm mit Portfolio-Sperre ohne Beträge — wie Android.
final class NotificationDeliveryTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    // MARK: Gas

    func testGasUndeliverableStaysArmed() {
        let held = GasAlertCheck.evaluate(value: 1.5, threshold: 2.0, armed: true, deliverable: false)
        XCTAssertFalse(held.fire)
        XCTAssertTrue(held.armed)
        let fired = GasAlertCheck.evaluate(value: 1.5, threshold: 2.0, armed: true, deliverable: true)
        XCTAssertTrue(fired.fire)
        XCTAssertFalse(fired.armed)
        // Nicht scharf: Zustellbarkeit ändert nichts
        XCTAssertFalse(GasAlertCheck.evaluate(value: 1.5, threshold: 2.0, armed: false, deliverable: false).armed)
        XCTAssertTrue(GasAlertCheck.evaluate(value: 2.6, threshold: 2.0, armed: false, deliverable: false).armed)
    }

    func testGasFailedPostKeepsPreviousState() {
        XCTAssertTrue(GasAlertCheck.armedAfter(fire: true, posted: false, armedBefore: true, next: false))
        XCTAssertFalse(GasAlertCheck.armedAfter(fire: true, posted: true, armedBefore: true, next: false))
        XCTAssertTrue(GasAlertCheck.armedAfter(fire: false, posted: false, armedBefore: false, next: true))
    }

    // MARK: Aktivität

    func testActivityUndeliverableStaysPendingWhileActive() {
        let move: Set<ActivitySignalKind> = [.PRICE_MOVE]
        let report = ActivityReport(
            signals: [ActivitySignal(kind: .PRICE_MOVE, severity: .STRONG, value: 5, seenAt: now)], computedAt: now)
        XCTAssertEqual(ActivityAnalyzer.pendingAfter(kinds: move, notify: true, posted: false), move)
        XCTAssertEqual(ActivityAnalyzer.pendingAfter(kinds: move, notify: true, posted: true), [])
        XCTAssertEqual(ActivityAnalyzer.pendingAfter(kinds: move, notify: false, posted: false), [])
        // Nächster Lauf: zurückgehaltene Art zählt wieder, solange das Signal aktiv ist
        XCTAssertEqual(ActivityAnalyzer.noticeKinds(newKinds: [], pending: move, report: report, now: now + 600_000), move)
        XCTAssertTrue(ActivityAnalyzer.shouldNotify(
            enabled: true, newKinds: ActivityAnalyzer.noticeKinds(newKinds: [], pending: move, report: report, now: now),
            lastNotifiedAt: 0, now: now))
        XCTAssertEqual(ActivityAnalyzer.noticeKinds(newKinds: [], pending: move, report: report,
                                                    now: now + ActivityAnalyzer.signalTtlMillis + 1), [])
        XCTAssertEqual(ActivityAnalyzer.noticeKinds(newKinds: [.VOLUME_SPIKE], pending: [.FUNDING_EXTREME],
                                                    report: report, now: now), [.VOLUME_SPIKE])
    }

    // MARK: Portfolio-Alarm

    func testPortfolioAlarmWithAppLockHasNeutralBody() {
        let alarm = PortfolioAlarm(id: 1, kind: .CHANGE_UP, threshold: 5, currency: nil)
        var settings = AppSettings()
        settings.appLock = true
        XCTAssertEqual(PortfolioAlarmNotice.body(alarm, measured: 6.2, settings: settings),
                       L("notification_portfolio_alarm_locked"))
        // Ohne Sperre: Satz und Messwert wie bisher
        settings.appLock = false
        XCTAssertNotEqual(PortfolioAlarmNotice.body(alarm, measured: 6.2, settings: settings),
                          L("notification_portfolio_alarm_locked"))
    }
}
