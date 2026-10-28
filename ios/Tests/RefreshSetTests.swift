import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus RefreshDebounceParityTests

/// 15-s-Sperre für «Aktualisieren» liest nur das Ende der letzten VOLLSTÄNDIGEN Aktualisierung
/// (`SharedStorage.lastFullRefreshAt`), nicht die Uhrzeit der Live-Takte (`lastRefreshAt`) —
/// `Parity/refresh_debounce.json`, wie `RefreshDebounceParityTest.kt`. Dazu ein Quelltext-Scan der Aufrufer.
final class RefreshDebounceParityTests: XCTestCase {
    func testParityCases() throws {
        let fixture = try TestSupport.fixture("refresh_debounce")
        let now = TestSupport.millis(fixture["now"])
        let cases = TestSupport.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let full = TestSupport.number(c["fullAgo"]).map { now - Int64($0) } ?? 0
            let decision = RefreshDebounce.decide(running: c["running"] as? Bool ?? false, lastFinishedAt: full, now: now)
            let expected: RefreshDebounce.Decision? = switch c["expected"] as? String {
            case "start": .start
            case "running": .running
            case "recent": .recent
            default: nil
            }
            XCTAssertEqual(decision, expected, name)
            let seconds = RefreshDebounce.remainingSeconds(lastFinishedAt: full, now: now)
            XCTAssertEqual(seconds, Int(TestSupport.number(c["remainingSeconds"]) ?? -1), name)
        }
    }

    /// Eigener Wert neben der Uhrzeit der Anzeige: ein Live-Takt verschiebt die Sperre nicht.
    func testFullRefreshTimeIsSeparateFromDisplayTime() {
        let savedDisplay = SharedStorage.lastRefreshAt
        let savedFull = SharedStorage.lastFullRefreshAt
        defer {
            SharedStorage.lastRefreshAt = savedDisplay
            SharedStorage.lastFullRefreshAt = savedFull
        }
        let now = EpochTime.nowMillis
        SharedStorage.markFullRefresh(at: now - 600_000)
        XCTAssertEqual(SharedStorage.lastRefreshAt, now - 600_000)
        // Live-Takt (`AppData.applyLive`): nur die Uhrzeit der Anzeige
        SharedStorage.lastRefreshAt = now - 5_000
        XCTAssertEqual(SharedStorage.lastFullRefreshAt, now - 600_000)
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: SharedStorage.lastFullRefreshAt, now: now),
                       .start)
    }

    /// Knopf, Ziehen, Menü-Countdown und Widget-Knopf lesen `lastFullRefreshAt`; Live-Kurse setzen es nie.
    func testCallersUseTheFullRefreshTime() throws {
        let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func text(_ path: String) throws -> String {
            try String(contentsOf: ios.appendingPathComponent(path), encoding: .utf8)
        }
        let callers = ["App/Services/AppData+Refresh.swift", "App/Features/Watchlist/WatchlistList.swift",
                       "App/Features/Watchlist/WatchlistRefreshCountdown.swift", "Widgets/WidgetIntents.swift",
                       "Widgets/WatchlistWidget.swift"]
        for path in callers {
            let source = try text(path)
            XCTAssertTrue(source.contains("lastFinishedAt: SharedStorage.lastFullRefreshAt"), path)
            XCTAssertFalse(source.contains("lastFinishedAt: SharedStorage.lastRefreshAt"), path)
            XCTAssertFalse(source.contains("lastFinishedAt: entry.lastRefreshAt"), path)
        }
        XCTAssertTrue(try text("App/Features/Watchlist/WatchlistRefreshCountdown.swift")
            .contains("let last = SharedStorage.lastFullRefreshAt"))
        let live = try text("App/Services/AppData+Live.swift")
        XCTAssertFalse(live.contains("markFullRefresh"))
        XCTAssertFalse(live.contains("lastFullRefreshAt ="))
    }
}

// ---- aus RefreshEffectsDerivativesTests

/// Funding-/Open-Interest-Alarme an `PriceRefresher.checkAlarms`: Fehlt der benötigte Wert, heisst es «nicht geprüft»
/// (Grund `noData`) statt stiller Lücke — wie Android `checkDerivativesAlarm` (`run.unchecked(alarm, NO_DATA)`).
final class RefreshEffectsDerivativesTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    private func check(_ alarms: [Alarm], values: DerivativesAlarmData.Values?) -> PriceRefresher.Outcome {
        let before = Watch(id: 1, marketKey: "BinanceFutures", marketName: "Test", baseAsset: "BTC", quoteAsset: "USDT",
                           lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        var inputs = PriceRefresher.AlarmInputs()
        if let values { inputs.derivatives[1] = values }
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(alarms, watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false, inputs: inputs, outcome: &outcome)
        return outcome
    }

    func testFundingOhneWertIstNichtGeprueft() {
        let alarm = Alarm(id: 90_191, watchId: 1, condition: .FUNDING_ABOVE, threshold: 0.01)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        // Open Interest da, Funding fehlt
        let outcome = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: nil, oiUnits: 5, fetchedAt: now))
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.reason, .noData)
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.since, now)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
        XCTAssertNil(outcome.alarms[alarm.id])
    }

    func testOpenInterestOhneWertIstNichtGeprueft() {
        let alarm = Alarm(id: 90_192, watchId: 1, condition: .OI_UP, threshold: 5, windowHours: 4)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        let outcome = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: 0.02, oiUnits: nil, fetchedAt: now))
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarm.id)?.reason, .noData)
        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
    }

    /// Mit Wert: geprüft — ein früherer Hinweis «nicht geprüft» fällt weg.
    func testFundingMitWertLoeschtHinweis() {
        let alarm = Alarm(id: 90_193, watchId: 1, condition: .FUNDING_BELOW, threshold: -0.5)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        AlarmCheckStatus.shared.markUnchecked(alarm.id, reason: .noData, now: now - 60_000)
        _ = check([alarm], values: DerivativesAlarmData.Values(fundingPercent: 0.01, oiUnits: nil, fetchedAt: now))
        XCTAssertNil(AlarmCheckStatus.shared.unchecked(alarm.id))
    }

    /// Paar ohne solche Daten (kein Perpetual einer unterstützten Börse): kein Hinweis — wie Android.
    func testOhneDerivateDatenKeinHinweis() {
        let alarm = Alarm(id: 90_194, watchId: 1, condition: .FUNDING_ABOVE, threshold: 0.01)
        defer { AlarmCheckStatus.shared.markChecked(alarm.id) }
        _ = check([alarm], values: nil)
        XCTAssertNil(AlarmCheckStatus.shared.unchecked(alarm.id))
    }
}

// ---- aus RefreshScheduleClockTests

/// Uhr vor- und zurückgestellt: iOS hält den absoluten Beginn der vorgemerkten Anfrage fest — liegt er weiter weg,
/// als `RefreshSchedule.nextBegin` je planen könnte, wird sie ersetzt; sonst bleibt sie stehen (ähnlich Android UPDATE: der laufende Takt bleibt).
final class RefreshScheduleClockTests: XCTestCase {
    private let m: Int64 = 60_000
    private let now: Int64 = 1_760_000_000_000

    func testPlannedBeginIsPlausible() {
        // Gerade geplant mit 30 min Intervall
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 30 * m, intervalMinutes: 30, now: now))
        // Knapp innerhalb des Spielraums
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 45 * m, intervalMinutes: 30, now: now))
        // Schon überfällig oder ohne Beginn: bleibt stehen
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now - 10 * m, intervalMinutes: 30, now: now))
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: nil, intervalMinutes: 30, now: now))
    }

    func testBeginHoursAwayAfterClockChangeIsReplaced() {
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 45 * m + 1, intervalMinutes: 30, now: now))
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + 5 * 60 * m, intervalMinutes: 30, now: now))
    }

    func testNextBeginIsNeverImplausible() {
        for interval in [1, 15, 30, 60, 240, 24 * 60] {
            for last in [0, now - 3 * 60 * m, now - m, now, now + 60 * m] {
                for zone in [nil, 0, now - 11 * 60 * m, now] as [Int64?] {
                    let begin = RefreshSchedule.nextBegin(lastRunMillis: last, intervalMinutes: interval,
                                                          zoneLastRunMillis: zone, now: now)
                    let millis = Int64((begin.timeIntervalSince1970 * 1000).rounded())
                    XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: millis,
                                                                    intervalMinutes: interval, now: now))
                }
            }
        }
    }

    func testHugeIntervalDoesNotOverflow() {
        // Intervall aus alter/fremder Sicherung: `SafeMillis` begrenzt, kein Absturz
        let fiftyYears: Int64 = 50 * 365 * 24 * 60 * m
        XCTAssertFalse(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: now + fiftyYears, intervalMinutes: Int.max, now: now))
        XCTAssertTrue(RefreshSchedule.isImplausiblyFar(earliestBeginMillis: Int64.max, intervalMinutes: Int.max, now: now))
    }
}
