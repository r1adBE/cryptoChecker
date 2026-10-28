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

    /// Wie `GasAlertLogicTest.changedOrReenabledThresholdRearms` (Android).
    func testGasChangedOrReenabledThresholdRearms() {
        XCTAssertTrue(GasAlertCheck.armedFor(storedArmed: false, storedThreshold: 2.0, threshold: 3.0))
        let fired = GasAlertCheck.evaluate(
            value: 2.5, threshold: 3.0, armed: GasAlertCheck.armedFor(storedArmed: false, storedThreshold: 2.0, threshold: 3.0))
        XCTAssertTrue(fired.fire)
        XCTAssertTrue(GasAlertCheck.armedFor(storedArmed: false, storedThreshold: 0.0, threshold: 2.0))
        // Unveränderte Grenze: kein Doppelalarm
        XCTAssertFalse(GasAlertCheck.armedFor(storedArmed: false, storedThreshold: 2.0, threshold: 2.0))
        XCTAssertFalse(GasAlertCheck.evaluate(value: 1.5, threshold: 2.0, armed: false).fire)
        XCTAssertTrue(GasAlertCheck.armedFor(storedArmed: false, storedThreshold: nil, threshold: 2.0))
    }

    /// Wie `CheckIntervalDueTest` (Android): nach zurückgestellter Uhr nicht stumm.
    func testGasAndDepegDueAfterClockCorrection() {
        let minute: Int64 = 60_000
        let months: Int64 = 90 * 24 * 60 * minute
        XCTAssertTrue(GasAlertCheck.isDue(now: now, lastCheck: 0))
        XCTAssertFalse(GasAlertCheck.isDue(now: now, lastCheck: now))
        XCTAssertFalse(GasAlertCheck.isDue(now: now, lastCheck: now - 9 * minute))
        XCTAssertTrue(GasAlertCheck.isDue(now: now, lastCheck: now - 10 * minute))
        XCTAssertTrue(GasAlertCheck.isDue(now: now, lastCheck: now + months))
        XCTAssertTrue(GasAlertCheck.isDue(now: now, lastCheck: now + 1))
        let stamp = now - 10 * minute + 2 * minute
        XCTAssertFalse(GasAlertCheck.isDue(now: now + minute, lastCheck: stamp))
        XCTAssertTrue(GasAlertCheck.isDue(now: now + 2 * minute, lastCheck: stamp))
        XCTAssertFalse(DepegAlarmCheck.isDue(now: now, lastCheck: now - 4 * minute))
        XCTAssertTrue(DepegAlarmCheck.isDue(now: now, lastCheck: now - 5 * minute))
        XCTAssertTrue(DepegAlarmCheck.isDue(now: now, lastCheck: now + months))
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
        // Ohne Sperre: Satz und Messwert
        settings.appLock = false
        XCTAssertNotEqual(PortfolioAlarmNotice.body(alarm, measured: 6.2, settings: settings),
                          L("notification_portfolio_alarm_locked"))
    }
}

/// Neuplanung der Wirtschaftstermine: nacheinander, ein überholter Durchlauf plant nichts mehr, wartende ältere entfallen.
final class MacroRescheduleQueueTests: XCTestCase {
    private actor Recorder {
        var events: [String] = []
        func add(_ event: String) { events.append(event) }
    }

    func testNewerRescheduleDropsOlderOnes() async {
        let queue = MacroRescheduleQueue()
        let recorder = Recorder()
        let (started, startedSignal) = AsyncStream<Void>.makeStream()
        let (gate, gateSignal) = AsyncStream<Void>.makeStream()
        // A läuft und wartet; B und C werden eingereiht, während A noch läuft
        let a = await queue.enqueue { isCurrent in
            startedSignal.yield(())
            for await _ in gate { break }
            let current = await isCurrent()
            await recorder.add("A:\(current)")
        }
        for await _ in started { break }
        let b = await queue.enqueue { _ in await recorder.add("B") }
        let c = await queue.enqueue { isCurrent in
            let current = await isCurrent()
            await recorder.add("C:\(current)")
        }
        gateSignal.yield(())
        await a.value
        await b.value
        await c.value
        let events = await recorder.events
        // A sieht, dass es überholt ist; B entfällt ganz; C läuft als neuester
        XCTAssertEqual(events, ["A:false", "C:true"])
    }

    func testRunWaitsForCompletion() async {
        let queue = MacroRescheduleQueue()
        let recorder = Recorder()
        await queue.run { _ in await recorder.add("done") }
        let events = await recorder.events
        XCTAssertEqual(events, ["done"])
    }
}

/// Fear & Greed nach geänderter Grenze — wie `MarketNoticeGateTest` (Android).
final class FearGreedBoundChangeTests: XCTestCase {
    func testChangedBoundNotifiesUnchangedDoesNotRepeat() {
        XCTAssertTrue(MarketNoticeGate.boundChanged(stored: 10, current: 20))
        let step = MarketNoticeGate.fearGreed(value: 15, last: 15, below: 20, above: 0, canNotify: true, belowChanged: true)
        XCTAssertEqual(step.step, .notify)
        XCTAssertEqual(step.kind, .below)
        XCTAssertFalse(MarketNoticeGate.boundChanged(stored: 20, current: 20))
        XCTAssertEqual(MarketNoticeGate.fearGreed(value: 14, last: 15, below: 20, above: 0, canNotify: true).step, .store)
        XCTAssertEqual(MarketNoticeGate.fearGreed(value: 15, last: 15, below: 20, above: 90, canNotify: true,
                                                  aboveChanged: true).step, .store)
        XCTAssertTrue(MarketNoticeGate.boundChanged(stored: nil, current: 20))
    }
}
