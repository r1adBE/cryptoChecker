import BackgroundTasks
import XCTest
@testable import CryptoChecker

/// Ein ganzer Lauf der Hintergrund-Aufgabe (`BackgroundRefresh.handleRefresh`, seit Build 103, Review TST-03) mit einer
/// Fälschung statt `BGTask`: Das Ende wird genau einmal gemeldet — im normalen Lauf mit Erfolg, beim Ablauf vor dem Ende
/// mit `false`, und der Alarm bleibt dann scharf. Die Reihenfolge im `expirationHandler` (abbrechen, melden, danach
/// Protokoll) prüft `BackgroundExpiryTests`; hier geht es um das Zusammenspiel mit dem Arbeits-Task.
/// Läuft auf der Merkliste der Test-App (App Group): Der Test legt ein eigenes Paar mit Alarm an und entfernt es danach.
/// Seit Build 104 sichert er auch den übrigen geteilten Zustand (Uhrzeiten, Bericht, %-Basis, «Letzte Prüfungen»,
/// vorgemerkte Anfrage) und stellt ihn danach wieder her.
@MainActor
final class BackgroundRefreshRunTests: XCTestCase {
    /// Hält Ablauf-Handler und jede Meldung des Endes fest (thread-sicher).
    private final class FakeTask: BackgroundRefreshTask, @unchecked Sendable { // @unchecked: Zustand nur unter `lock`
        private let lock = NSLock()
        private var handler: (() -> Void)?
        private var results: [Bool] = []

        func onExpiration(_ handler: @escaping () -> Void) {
            lock.lock(); self.handler = handler; lock.unlock()
        }

        func setTaskCompleted(success: Bool) {
            lock.lock(); results.append(success); lock.unlock()
        }

        var completions: [Bool] { lock.lock(); defer { lock.unlock() }; return results }

        func expire() {
            lock.lock(); let current = handler; lock.unlock()
            current?()
        }
    }

    /// Geteilter Zustand vor dem Test (seit Build 104, Review TST2-04).
    private struct SavedState {
        var lastAlarmCheckAt: Int64
        var lastRefreshAt: Int64
        var lastRefreshDuration: Int64
        var lastRefreshReport: RefreshReport?
        var changeStamp: ChangeStamp?
        /// Inhalt von `check_runs.txt`; nil = keine Datei.
        var checkRuns: Data?
        /// Vorgemerkte Anfrage: nil = keine; sonst ihr frühester Beginn (innen nil = ohne).
        var pendingBegin: Date??
    }

    nonisolated private static let watchId: Int64 = 990_001
    nonisolated private static let alarmId: Int64 = 990_002
    private var saved: SavedState?

    /// Echte Wege, aber Protokoll und Vormerken nach einem Ablauf sofort: sonst schriebe es nach `tearDown`.
    private static var liveInline: BackgroundRunSeams {
        var seams = BackgroundRunSeams.live
        seams.detachAfterExpiry = { $0() }
        return seams
    }

    override func setUp() async throws {
        let pending = await BGTaskScheduler.shared.pendingTaskRequests()
            .first { $0.identifier == BackgroundRefresh.refreshId }
        saved = SavedState(lastAlarmCheckAt: SharedStorage.lastAlarmCheckAt, lastRefreshAt: SharedStorage.lastRefreshAt,
                           lastRefreshDuration: SharedStorage.lastRefreshDuration,
                           lastRefreshReport: SharedStorage.lastRefreshReport, changeStamp: SharedStorage.changeStamp,
                           checkRuns: try? Data(contentsOf: CheckRunStore.url),
                           pendingBegin: pending.map { $0.earliestBeginDate })
        // Sonst endete der Lauf als «Alarme eben erst geprüft», ohne Arbeits-Task
        SharedStorage.lastAlarmCheckAt = 0
        // Vermerk «wird gemeldet» eines früheren Laufs hielte die Meldung zurück
        AlarmDelivery.pairs.remove([Self.alarmId])
    }

    override func tearDown() async throws {
        _ = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId }
        }
        AlarmDelivery.pairs.remove([Self.alarmId])
        guard let saved else { return }
        SharedStorage.lastAlarmCheckAt = saved.lastAlarmCheckAt
        SharedStorage.lastRefreshAt = saved.lastRefreshAt
        SharedStorage.lastRefreshDuration = saved.lastRefreshDuration
        SharedStorage.lastRefreshReport = saved.lastRefreshReport
        SharedStorage.changeStamp = saved.changeStamp
        if let data = saved.checkRuns {
            try? data.write(to: CheckRunStore.url, options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: CheckRunStore.url)
        }
        // Vormerkung wie vorher: der Lauf ersetzt sie (und meldet ein gescheitertes Vormerken in «Letzte Prüfungen»)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: BackgroundRefresh.refreshId)
        if let begin = saved.pendingBegin {
            let request = BGAppRefreshTaskRequest(identifier: BackgroundRefresh.refreshId)
            request.earliestBeginDate = begin
            try? BGTaskScheduler.shared.submit(request)
        }
        self.saved = nil
    }

    /// Paar mit einem Alarm, der beim ersten Kurs sicher auslöste («über 1»). Datei nicht beschreibbar: übersprungen.
    private func insertArmedAlarm() throws {
        let stored = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId }
            s.watches.append(Self.watch)
            s.alarms.append(Self.alarm)
        }
        guard stored != nil else { throw XCTSkip("Merkliste der Test-App gerade nicht beschreibbar") }
    }

    nonisolated private static let watch = Watch(id: watchId, marketKey: "Binance", marketName: "Binance",
                                                 baseAsset: "BTC", quoteAsset: "USDT", pairId: "BTCUSDT")
    nonisolated private static let alarm = Alarm(id: alarmId, watchId: watchId, condition: .PRICE_ABOVE,
                                                 threshold: 1)

    private func storedAlarm() -> Alarm? {
        SharedStorage.loadSnapshot().alarms.first { $0.id == Self.alarmId }
    }

    /// Gefälschter Durchlauf (seit Build 104): Kurs 100 für das eigene Paar, sein Alarm ausgelöst und zum Melden bereit
    /// (ohne Netz und ohne Lease).
    nonisolated private static func firedOutcome() -> PriceRefresher.Outcome {
        let now = TimeUtils.nowMillis
        var fired = alarm
        fired.enabled = false
        fired.lastTriggeredAt = now
        fired.lastTriggeredPrice = 100
        var outcome = PriceRefresher.Outcome()
        outcome.prices[watchId] = PriceRefresher.PriceUpdate(price: 100, time: now)
        outcome.pendingAlarms = [PriceRefresher.PendingAlarm(
            watch: watch, alarm: alarm, price: 100,
            update: PriceRefresher.AlarmUpdate(evaluated: alarm, result: fired, setsEnabled: true))]
        outcome.checked = 1
        return outcome
    }

    /// Fälschungen für Kurse und Melde-Weg; `shows` zählt die Meldungen, `afterSave` läuft nach dem Speichern.
    nonisolated private static func fakeSeams(shows: LockedBox<Int>,
                                  afterSave: @escaping @Sendable () async -> Void) -> BackgroundRunSeams {
        var seams = BackgroundRunSeams(refresh: { _, _, _ in firedOutcome() })
        seams.afterPriceSave = afterSave
        seams.notifier = PriceRefresher.AlarmNotifier(canShow: { true }, show: { _ in
            shows.write { $0 += 1 }
            return true
        })
        seams.detachAfterExpiry = { $0() }
        return seams
    }

    func testExpiryBeforeTheEndCompletesOnceWithFalseAndKeepsAlarmArmed() async throws {
        try insertArmedAlarm()
        let task = FakeTask()
        let work = BackgroundRefresh.handleRefresh(task, seams: Self.liveInline)
        XCTAssertNotNil(work, "ohne Arbeits-Task (übersprungen?)")
        // Ablauf, bevor der Arbeits-Task überhaupt beginnt (kein await dazwischen): sofort genau ein «false»
        task.expire()
        XCTAssertEqual(task.completions, [false])
        // Der abgebrochene Arbeits-Task endet danach und meldet nicht noch einmal
        await work?.value
        XCTAssertEqual(task.completions, [false])
        // Nichts gespeichert, nichts gemeldet: der Alarm ist unverändert scharf
        let alarm = try XCTUnwrap(storedAlarm())
        XCTAssertTrue(alarm.enabled)
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
        XCTAssertNil(alarm.lastTriggeredPrice)
    }

    /// Normaler Lauf: genau ein Ende mit Erfolg, erst wenn der Arbeits-Task fertig ist. Das Paar ist deaktiviert, damit
    /// kein Netz nötig ist (die Kurs- und Mitteilungswege prüfen PriceRefresher- und Notifier-Tests; Mitteilungen
    /// erlaubt der Test-Host nicht, eine Zustellung lässt sich hier nicht beobachten).
    func testNormalRunCompletesExactlyOnceWithSuccess() async throws {
        try insertArmedAlarm()
        _ = SharedStorage.updateSnapshot { s in
            if let i = s.watches.firstIndex(where: { $0.id == Self.watchId }) { s.watches[i].disabled = true }
        }
        let task = FakeTask()
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: Self.liveInline), "ohne Arbeits-Task (übersprungen?)")
        XCTAssertEqual(task.completions, [], "Ende erst nach der Arbeit")
        await work.value
        XCTAssertEqual(task.completions, [true])
        // Ein später Ablauf (nach dem Ende) meldet nicht noch einmal
        task.expire()
        XCTAssertEqual(task.completions, [true])
    }

    /// Seit Build 104 (Review IOS2-R03): Läuft die Zeit nach dem Speichern der Kurse, aber vor dem Melden ab, wird nichts
    /// mehr gemeldet — genau ein Ende mit `false`, der Kurs bleibt gespeichert, der Alarm scharf.
    func testExpiryAfterSavingBeforeDeliveringShowsNothing() async throws {
        try insertArmedAlarm()
        let task = FakeTask()
        let shows = LockedBox(0)
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: Self.fakeSeams(shows: shows) {
            task.expire()
        }), "ohne Arbeits-Task (übersprungen?)")
        await work.value
        XCTAssertEqual(shows.current, 0, "nach dem Ablauf nicht mehr melden")
        XCTAssertEqual(task.completions, [false])
        let alarm = try XCTUnwrap(storedAlarm())
        XCTAssertTrue(alarm.enabled)
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
        let watch = SharedStorage.loadSnapshot().watches.first { $0.id == Self.watchId }
        XCTAssertEqual(watch?.lastPrice, 100, "Kurs bleibt gespeichert")
    }

    /// Gegenprobe: gleicher gefälschter Lauf ohne Ablauf meldet den Alarm genau einmal (der Test oben prüft also etwas).
    func testSameRunWithoutExpiryShowsOnce() async throws {
        guard !QuietHours.holdsAlarmsNow(SharedStorage.loadSettings()) else {
            throw XCTSkip("Nachtruhe der Test-App hält Alarme zurück")
        }
        try insertArmedAlarm()
        let task = FakeTask()
        let shows = LockedBox(0)
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: Self.fakeSeams(shows: shows) {}),
                                 "ohne Arbeits-Task (übersprungen?)")
        await work.value
        XCTAssertEqual(shows.current, 1)
        XCTAssertEqual(task.completions, [true])
    }
}
