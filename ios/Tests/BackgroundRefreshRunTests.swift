import BackgroundTasks
import XCTest
@testable import CryptoChecker

/// Ein ganzer Lauf der Hintergrund-Aufgabe (`BackgroundRefresh.handleRefresh`, Review TST-03) mit einer
/// Fälschung statt `BGTask`: Das Ende wird genau einmal gemeldet — im normalen Lauf mit Erfolg, beim Ablauf vor dem Ende
/// mit `false`, und der Alarm bleibt dann scharf. Die Reihenfolge im `expirationHandler` (abbrechen, melden, danach
/// Protokoll) prüft `BackgroundExpiryTests`; hier geht es um das Zusammenspiel mit dem Arbeits-Task.
/// Läuft auf der Merkliste der Test-App (App Group): Der Test legt ein eigenes Paar mit Alarm an und entfernt es danach.
/// Er sichert auch den übrigen geteilten Zustand (Uhrzeiten, Bericht, %-Basis, «Letzte Prüfungen»,
/// vorgemerkte Anfrage) und stellt ihn danach wieder her. Ohne Schlüsselbund (unsignierter CI-Simulator) mit
/// Test-Schlüssel (`HostStorageKey`): eine nicht beschreibbare Merkliste ist ein Fehler, kein Überspringen.
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

    /// Geteilter Zustand vor dem Test (Review TST2-04).
    private struct SavedState {
        var lastAlarmCheckAt: Int64
        var lastRefreshAt: Int64
        var lastFullRefreshAt: Int64
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
    /// Zweiter Alarm desselben Paars (Abbruch mitten in der Melde-Schleife).
    nonisolated private static let alarmId2: Int64 = 990_003
    private var saved: SavedState?

    /// Echte Wege, aber Protokoll und Vormerken nach einem Ablauf sofort: sonst schriebe es nach `tearDown`.
    private static var liveInline: BackgroundRunSeams {
        var seams = BackgroundRunSeams.live
        seams.detachAfterExpiry = { $0() }
        return seams
    }

    override func setUp() async throws {
        try await HostStorageKey.prepareAppStorage()
        let pending = await BGTaskScheduler.shared.pendingTaskRequests()
            .first { $0.identifier == BackgroundRefresh.refreshId }
        saved = SavedState(lastAlarmCheckAt: SharedStorage.lastAlarmCheckAt, lastRefreshAt: SharedStorage.lastRefreshAt,
                           lastFullRefreshAt: SharedStorage.lastFullRefreshAt,
                           lastRefreshDuration: SharedStorage.lastRefreshDuration,
                           lastRefreshReport: SharedStorage.lastRefreshReport, changeStamp: SharedStorage.changeStamp,
                           checkRuns: try? Data(contentsOf: CheckRunStore.url),
                           pendingBegin: pending.map { $0.earliestBeginDate })
        // Sonst endete der Lauf als «Alarme eben erst geprüft», ohne Arbeits-Task
        SharedStorage.lastAlarmCheckAt = 0
        // Vermerk «wird gemeldet» eines früheren Laufs hielte die Meldung zurück
        AlarmDelivery.pairs.remove([Self.alarmId, Self.alarmId2])
    }

    override func tearDown() async throws {
        _ = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId || $0.id == Self.alarmId2 }
        }
        AlarmDelivery.pairs.remove([Self.alarmId, Self.alarmId2])
        guard let saved else { return }
        SharedStorage.lastAlarmCheckAt = saved.lastAlarmCheckAt
        SharedStorage.lastRefreshAt = saved.lastRefreshAt
        SharedStorage.lastFullRefreshAt = saved.lastFullRefreshAt
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

    /// Paar mit einem Alarm, der beim ersten Kurs sicher auslöste («über 1»). Datei nicht beschreibbar: Fehler.
    private func insertArmedAlarm(second: Bool = false) throws {
        let stored = SharedStorage.updateSnapshot { s in
            s.watches.removeAll { $0.id == Self.watchId }
            s.alarms.removeAll { $0.id == Self.alarmId || $0.id == Self.alarmId2 }
            s.watches.append(Self.watch)
            s.alarms.append(Self.alarm)
            if second { s.alarms.append(Self.alarm2) }
        }
        _ = try XCTUnwrap(stored, "Merkliste der Test-App nicht beschreibbar")
    }

    nonisolated private static let watch = Watch(id: watchId, exchangeKey: "Binance", exchangeName: "Binance",
                                                 baseAsset: "BTC", quoteAsset: "USDT", pairId: "BTCUSDT")
    nonisolated private static let alarm = Alarm(id: alarmId, watchId: watchId, condition: .PRICE_ABOVE,
                                                 threshold: 1)

    nonisolated private static let alarm2 = Alarm(id: alarmId2, watchId: watchId, condition: .PRICE_ABOVE,
                                                  threshold: 2)

    private func storedAlarm(_ id: Int64 = BackgroundRefreshRunTests.alarmId) -> Alarm? {
        SharedStorage.loadSnapshot().alarms.first { $0.id == id }
    }

    /// Gefälschter Durchlauf: Kurs 100 für das eigene Paar, sein Alarm ausgelöst und zum Melden bereit
    /// (ohne Netz und ohne Lease).
    nonisolated private static func firedOutcome(_ alarms: [Alarm] = [alarm]) -> PriceRefresher.Outcome {
        let now = EpochTime.nowMillis
        var outcome = PriceRefresher.Outcome()
        outcome.prices[watchId] = PriceRefresher.PriceUpdate(price: 100, time: now)
        outcome.pendingAlarms = alarms.map { pending($0, now: now) }
        outcome.checked = 1
        return outcome
    }

    /// Ausgelöster Alarm, zum Melden bereit (auch für den Zusatzdaten-Pfad).
    nonisolated private static func pending(_ alarm: Alarm, now: Int64) -> PriceRefresher.PendingAlarm {
        var fired = alarm
        fired.enabled = false
        fired.lastTriggeredAt = now
        fired.lastTriggeredPrice = 100
        return PriceRefresher.PendingAlarm(
            watch: watch, alarm: alarm, price: 100,
            update: PriceRefresher.AlarmUpdate(evaluated: alarm, result: fired, setsEnabled: true))
    }

    /// Kurs gespeichert, Kursalarme keine, aber ein Alarm mit Zusatzdaten wartet; dessen Prüfung
    /// (gefälscht, ohne Netz) löst ihn aus.
    nonisolated private static func extraSeams(shows: LockedBox<Int>,
                                               afterExtraSave: @escaping @Sendable () async -> Void) -> BackgroundRunSeams {
        var seams = fakeSeams(shows: shows) {}
        seams.refresh = { _, _, _ in
            var outcome = firedOutcome([])
            outcome.deferredAlarms.items = [PriceRefresher.DeferredAlarms.Item(
                watch: watch, updated: watch, price: 100, fetched: .init())]
            return outcome
        }
        seams.checkDeferred = { _, _, _ in
            var extra = PriceRefresher.Outcome()
            extra.pendingAlarms = [pending(alarm, now: EpochTime.nowMillis)]
            return extra
        }
        seams.afterExtraSave = afterExtraSave
        return seams
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

    /// Review IOS2-R03: Läuft die Zeit nach dem Speichern der Kurse, aber vor dem Melden ab, wird nichts
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

    /// Review IOS3-R01: Ablauf nach dem Speichern der Zusatzdaten-Alarme → kein Melden, kein Vermerk,
    /// genau ein Ende mit `false`, der Alarm bleibt scharf.
    func testExpiryAfterSavingExtraAlarmsShowsNothing() async throws {
        try insertArmedAlarm()
        let task = FakeTask()
        let shows = LockedBox(0)
        let reached = LockedBox(false)
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: Self.extraSeams(shows: shows) {
            reached.current = true
            task.expire()
        }), "ohne Arbeits-Task (übersprungen?)")
        await work.value
        guard reached.current else { throw XCTSkip("Restzeit reichte nicht für die Zusatzdaten-Alarme") }
        XCTAssertEqual(shows.current, 0, "nach dem Ablauf nicht mehr melden")
        XCTAssertEqual(task.completions, [false])
        XCTAssertNil(AlarmDelivery.pairs.unconfirmedAt(Self.alarmId), "kein Vermerk ohne Meldung")
        let alarm = try XCTUnwrap(storedAlarm())
        XCTAssertTrue(alarm.enabled)
        XCTAssertEqual(alarm.lastTriggeredAt, 0)
    }

    /// Gegenprobe zum Zusatzdaten-Pfad: ohne Ablauf über `seams.notifier` genau einmal gemeldet.
    func testExtraAlarmsWithoutExpiryShowOnce() async throws {
        guard !QuietHours.holdsAlarmsNow(SharedStorage.loadSettings()) else {
            throw XCTSkip("Nachtruhe der Test-App hält Alarme zurück")
        }
        try insertArmedAlarm()
        let task = FakeTask()
        let shows = LockedBox(0)
        let reached = LockedBox(false)
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: Self.extraSeams(shows: shows) {
            reached.current = true
        }), "ohne Arbeits-Task (übersprungen?)")
        await work.value
        guard reached.current else { throw XCTSkip("Restzeit reichte nicht für die Zusatzdaten-Alarme") }
        XCTAssertEqual(shows.current, 1)
        XCTAssertEqual(task.completions, [true])
    }

    /// Review IOS3-R01: Ablauf während der Melde-Schleife — die Meldung des ersten Alarms löst ihn
    /// aus, der zweite wird nicht mehr gemeldet und bleibt scharf (wie Android `ensureActive` je Alarm).
    func testExpiryDuringDeliveryLoopStopsBeforeSecondAlarm() async throws {
        guard !QuietHours.holdsAlarmsNow(SharedStorage.loadSettings()) else {
            throw XCTSkip("Nachtruhe der Test-App hält Alarme zurück")
        }
        try insertArmedAlarm(second: true)
        let task = FakeTask()
        let shows = LockedBox(0)
        var seams = Self.fakeSeams(shows: shows) {}
        seams.refresh = { _, _, _ in Self.firedOutcome([Self.alarm, Self.alarm2]) }
        seams.notifier = PriceRefresher.AlarmNotifier(canShow: { true }, show: { _ in
            shows.write { $0 += 1 }
            task.expire()
            return true
        })
        let work = try XCTUnwrap(BackgroundRefresh.handleRefresh(task, seams: seams), "ohne Arbeits-Task (übersprungen?)")
        await work.value
        XCTAssertEqual(shows.current, 1, "nach dem Ablauf kein zweiter Alarm")
        XCTAssertEqual(task.completions, [false])
        let second = try XCTUnwrap(storedAlarm(Self.alarmId2))
        XCTAssertTrue(second.enabled)
        XCTAssertEqual(second.lastTriggeredAt, 0)
        XCTAssertNil(AlarmDelivery.pairs.unconfirmedAt(Self.alarmId2))
    }
}
