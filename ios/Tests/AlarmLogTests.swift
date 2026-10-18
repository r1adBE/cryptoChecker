import CryptoKit
import XCTest
@testable import CryptoChecker

/// Ringpuffer, letzte Prüfung und Speicher des Alarm-Protokolls (`AlarmLog`, `AlarmLogStore`) sowie die Einträge an den
/// echten Entscheidungsstellen (`PriceRefresher.checkAlarms` → `Outcome.deliverAlarms`). Wie `AlarmLogTest.kt`,
/// `AlarmLogStoreTest.kt` und `AlarmLogRecordingTest.kt`.
final class AlarmLogTests: XCTestCase {
    private let day: Int64 = 24 * 60 * 60_000
    private let now: Int64 = 1_760_000_000_000

    private func entry(_ id: Int64, at: Int64, _ outcome: AlarmLogOutcome = .FIRED, notable: Bool? = nil,
                       price: Double = 100) -> AlarmLogEntry {
        AlarmLogEntry(alarmId: id, at: at, pair: "BTC/CHF", exchange: "Kraken", quote: "CHF", price: price,
                      condition: .PRICE_ABOVE, threshold: 100_000, outcome: outcome,
                      notable: notable ?? (outcome == .FIRED))
    }

    // MARK: Ringpuffer und letzte Prüfung

    func testRingBufferKeepsTheNewest200() {
        var state = AlarmLogState.empty
        for i in 1...250 { state = AlarmLog.record(state, [entry(Int64(i), at: now - 250 + Int64(i))], now: now) }
        XCTAssertEqual(state.events.count, AlarmLog.maxEvents)
        XCTAssertEqual(state.events.first?.alarmId, 250)
        XCTAssertEqual(state.events.last?.alarmId, 51)
    }

    func testEntriesOlderThan14DaysArePruned() {
        let state = AlarmLog.record(AlarmLogState(events: [entry(2, at: now - 13 * day), entry(1, at: now - 15 * day)]),
                                    [], now: now)
        XCTAssertEqual(state.events.map(\.alarmId), [2])
        XCTAssertEqual(AlarmLog.prune([entry(3, at: now - 14 * day)], now: now).count, 1)
    }

    func testLastCheckIsOverwrittenEachCheck() throws {
        var state = AlarmLog.record(.empty, [entry(7, at: now - 2_000, .NOT_REACHED, price: 99_000)], now: now)
        state = AlarmLog.record(state, [entry(7, at: now - 1_000, .STALE, notable: false, price: 99_500)], now: now)
        XCTAssertEqual(state.lastChecks.count, 1)
        XCTAssertEqual(state.lastChecks[7]?.outcome, .STALE)
        XCTAssertEqual(try XCTUnwrap(state.lastChecks[7]?.price), 99_500, accuracy: 0)
        XCTAssertTrue(state.events.isEmpty)
        // Eine ältere Prüfung (anderer Prozess, später geschrieben) überschreibt die neuere nicht
        state = AlarmLog.record(state, [entry(7, at: now - 5_000, .NOT_REACHED, notable: false)], now: now)
        XCTAssertEqual(state.lastChecks[7]?.outcome, .STALE)
    }

    func testRepeatedHoldIsOneEntryWithRange() {
        var state = AlarmLogState.empty
        for i in 0..<30 {
            let at = now + Int64(i) * 900_000
            state = AlarmLog.record(state, [entry(1, at: at, .QUIET_HOURS, notable: true)], now: at)
        }
        XCTAssertEqual(state.events.count, 1)
        XCTAssertEqual(state.events.first?.at, now)
        XCTAssertEqual(state.events.first?.until, now + 29 * 900_000)
        let later = now + 31 * 900_000
        state = AlarmLog.record(state, [entry(1, at: later, .STALE, notable: true)], now: later)
        XCTAssertEqual(state.events.count, 2)
        XCTAssertEqual(state.events.first?.outcome, .STALE)
    }

    func testDeletingAnAlarmRemovesItsLastCheckButKeepsLogEntries() {
        let state = AlarmLog.record(.empty, [entry(7, at: now), entry(8, at: now)], now: now)
        let after = AlarmLog.removeAlarm(state, 7)
        XCTAssertNil(after.lastChecks[7])
        XCTAssertNotNil(after.lastChecks[8])
        XCTAssertEqual(after.events.count, 2)
        XCTAssertEqual(after.events.first { $0.alarmId == 7 }?.pair, "BTC/CHF")
    }

    func testRetainAndClear() {
        let state = AlarmLog.record(.empty, [entry(1, at: now), entry(2, at: now)], now: now)
        XCTAssertEqual(Set(AlarmLog.retainAlarms(state, [2, 99]).lastChecks.keys), [2])
        let cleared = AlarmLog.clearEvents(state)
        XCTAssertTrue(cleared.events.isEmpty)
        XCTAssertEqual(cleared.lastChecks.count, 2)
    }

    func testCoversPriceAndPercentAlarmsOnly() {
        XCTAssertEqual(Set(AlarmCondition.allCases.filter(AlarmLog.covers)),
                       [.PRICE_ABOVE, .PRICE_BELOW, .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN, .MOVE_PERCENT_WINDOW])
    }

    func testBrokenFileIsEmpty() {
        XCTAssertTrue(AlarmLog.decode("kaputt").events.isEmpty)
        XCTAssertTrue(AlarmLog.decode("" as String?).lastChecks.isEmpty)
    }

    func testOnlyFiredOrANewOutcomeStartsAnEvent() {
        let held = AlarmLog.record(.empty, [entry(1, at: now, .QUIET_HOURS, notable: true)], now: now)
        // Gleiches Ergebnis gleich danach: verlängert nur «von … bis …»
        XCTAssertFalse(AlarmLog.startsEvent(held, [entry(1, at: now + 1_000, .QUIET_HOURS, notable: true)]))
        // Nicht bemerkenswert: nie
        XCTAssertFalse(AlarmLog.startsEvent(held, [entry(1, at: now + 1_000, .NOT_REACHED)]))
        // Auslösung, anderes Ergebnis, anderer Alarm: neuer Eintrag
        XCTAssertTrue(AlarmLog.startsEvent(held, [entry(1, at: now + 1_000)]))
        XCTAssertTrue(AlarmLog.startsEvent(held, [entry(1, at: now + 1_000, .COOLDOWN, notable: true)]))
        XCTAssertTrue(AlarmLog.startsEvent(held, [entry(2, at: now + 1_000, .QUIET_HOURS, notable: true)]))
        XCTAssertTrue(AlarmLog.startsEvent(.empty, [entry(1, at: now, .QUIET_HOURS, notable: true)]))
        // Mehrere im selben Durchlauf: der zweite verlängert den ersten
        XCTAssertFalse(AlarmLog.startsEvent(held, [entry(1, at: now + 2_000, .QUIET_HOURS, notable: true),
                                                   entry(1, at: now + 1_000, .QUIET_HOURS, notable: true)]))
    }

    // MARK: Speicher

    private func tempURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("alarm_log-\(UUID().uuidString).json")
    }

    private let key = SymmetricKey(size: .bits256)

    private func keychain(_ key: SymmetricKey?) -> StorageKey {
        let fake = ProtectedFileTests.FakeKeychain()
        fake.stored = key?.withUnsafeBytes { Data($0) }
        return StorageKey(keychain: fake)
    }

    func testStoreWritesExcludedFromBackupAndReadsBack() throws {
        let url = tempURL()
        let keys = keychain(key)
        defer { try? FileManager.default.removeItem(at: url) }
        AlarmLogStore.record([entry(7, at: now)], live: false, to: url, now: now, keys: keys)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let values = try url.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
        let loaded = AlarmLogStore.load(from: url, now: now, keys: keys)
        XCTAssertEqual(loaded.lastChecks[7]?.outcome, .FIRED)
        XCTAssertEqual(loaded.events.count, 1)

        AlarmLogStore.removeAlarm(7, at: url, keys: keys)
        XCTAssertNil(AlarmLogStore.load(from: url, now: now, keys: keys).lastChecks[7])
        AlarmLogStore.clearEvents(at: url, keys: keys)
        XCTAssertTrue(AlarmLogStore.load(from: url, now: now, keys: keys).events.isEmpty)
    }

    func testWithoutKeyNothingIsWrittenInPlaintextAndEntriesWait() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        // Eigener Zeitstrahl (wartende Einträge je Prozess)
        let t0 = now + 2_000 * day
        AlarmLogStore.record([entry(31, at: t0)], live: false, to: url, now: t0, keys: keychain(nil))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "nie Klartext")
        // Für die Anzeige trotzdem da
        XCTAssertEqual(AlarmLogStore.load(from: url, now: t0, keys: keychain(nil)).lastChecks[31]?.outcome, .FIRED)

        // Schlüssel wieder da: die wartenden Einträge werden mitgeschrieben
        let keys = keychain(key)
        AlarmLogStore.record([entry(32, at: t0 + 1_000)], live: false, to: url, now: t0 + 1_000, keys: keys)
        let raw = try Data(contentsOf: url)
        XCTAssertNil(String(data: raw, encoding: .utf8)?.range(of: "Kraken"), "versiegelt")
        let state = AlarmLogStore.load(from: url, now: t0 + 1_000, keys: keys)
        XCTAssertNotNil(state.lastChecks[31])
        XCTAssertNotNil(state.lastChecks[32])
    }

    func testLiveChecksAreThrottledButNotableOnesWriteAtOnce() throws {
        let url = tempURL()
        let keys = keychain(key)
        defer { try? FileManager.default.removeItem(at: url) }
        // Eigener Zeitstrahl weit nach allen anderen Tests (Drosselung je Prozess)
        let t0 = now + 1_000 * day
        AlarmLogStore.record([entry(1, at: t0, .NOT_REACHED, notable: false)], live: true, to: url, now: t0, keys: keys)
        let first = try Data(contentsOf: url)

        AlarmLogStore.record([entry(1, at: t0 + 5_000, .NOT_REACHED, notable: false)], live: true, to: url,
                             now: t0 + 5_000, keys: keys)
        XCTAssertEqual(try Data(contentsOf: url), first, "gedrosselt")
        // Für die Anzeige trotzdem aktuell
        XCTAssertEqual(AlarmLogStore.load(from: url, now: t0 + 5_000, keys: keys).lastChecks[1]?.at, t0 + 5_000)

        AlarmLogStore.record([entry(1, at: t0 + 6_000, .FIRED)], live: true, to: url, now: t0 + 6_000, keys: keys)
        XCTAssertNotEqual(try Data(contentsOf: url), first, "bemerkenswert: sofort")
    }

    func testLiveMergedHoldsAreThrottledButNewEventsWriteAtOnce() throws {
        let url = tempURL()
        let keys = keychain(key)
        defer { try? FileManager.default.removeItem(at: url) }
        // Eigener Zeitstrahl weit nach allen anderen Tests (Drosselung je Prozess)
        let t0 = now + 3_000 * day
        AlarmLogStore.record([entry(1, at: t0, .QUIET_HOURS, notable: true)], live: true, to: url, now: t0, keys: keys)
        let first = try Data(contentsOf: url)

        // Dieselbe Sperre jede Sekunde: nur «bis» verlängert — gedrosselt
        AlarmLogStore.record([entry(1, at: t0 + 1_000, .QUIET_HOURS, notable: true)], live: true, to: url,
                             now: t0 + 1_000, keys: keys)
        XCTAssertEqual(try Data(contentsOf: url), first, "verlängert: gedrosselt")
        XCTAssertEqual(AlarmLogStore.load(from: url, now: t0 + 1_000, keys: keys).events.first?.until, t0 + 1_000)

        // Anderes Ergebnis: neuer Eintrag, sofort
        AlarmLogStore.record([entry(1, at: t0 + 2_000, .COOLDOWN, notable: true)], live: true, to: url,
                             now: t0 + 2_000, keys: keys)
        let second = try Data(contentsOf: url)
        XCTAssertNotEqual(second, first, "neuer Eintrag: sofort")

        AlarmLogStore.record([entry(1, at: t0 + 3_000, .COOLDOWN, notable: true)], live: true, to: url,
                             now: t0 + 3_000, keys: keys)
        XCTAssertEqual(try Data(contentsOf: url), second, "verlängert: gedrosselt")

        // Ausgelöst: immer sofort
        AlarmLogStore.record([entry(1, at: t0 + 4_000, .FIRED)], live: true, to: url, now: t0 + 4_000, keys: keys)
        XCTAssertNotEqual(try Data(contentsOf: url), second, "ausgelöst: sofort")
    }

    // MARK: An den Entscheidungsstellen

    private final class LogBox: @unchecked Sendable { // @unchecked: `_entries` unter `lock` (NSLock)
        private let lock = NSLock()
        private var _entries: [AlarmLogEntry] = []
        func add(_ e: [AlarmLogEntry]) { lock.lock(); _entries += e; lock.unlock() }
        var entries: [AlarmLogEntry] { lock.lock(); defer { lock.unlock() }; return _entries }
        func last(_ id: Int64) -> AlarmLogEntry? { entries.last { $0.alarmId == id } }
    }

    private final class FakeStore: @unchecked Sendable { // @unchecked: Test-Fake, nur nacheinander benutzt
        var snapshot = SharedStorage.Snapshot()
        func save(_ id: Int64, _ update: PriceRefresher.AlarmUpdate) -> Bool { update.apply(toAlarm: id, in: &snapshot) }
    }

    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        super.setUp()
        suiteName = "AlarmLogTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Ein Durchlauf wie App, Hintergrund und Widget; liefert die protokollierten Einträge.
    private func run(_ alarm: Alarm, store: FakeStore, price: Double, at: Int64, settings: AppSettings = AppSettings(),
                     inputs: PriceRefresher.AlarmInputs = PriceRefresher.AlarmInputs(), shows: Bool = true,
                     delivery: AlarmDelivery, log: LogBox) async {
        if store.snapshot.watches.isEmpty {
            store.snapshot.watches = [Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC",
                                            quoteAsset: "USDT", lastPrice: 99)]
            store.snapshot.alarms = [alarm]
        }
        let before = store.snapshot.watches[0]
        var updated = before
        updated.previousPrice = before.lastPrice
        updated.lastPrice = price
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(store.snapshot.alarms.filter(\.enabled), watch: before, updated: updated,
                                       price: price, now: at, settings: settings, speechAllowed: false, inputs: inputs,
                                       outcome: &outcome)
        outcome.apply(to: &store.snapshot)
        store.snapshot.watches[0] = updated
        let notifier = PriceRefresher.AlarmNotifier(canShow: { true }, show: { _ in shows })
        _ = await outcome.deliverAlarms(settings: settings, current: store.snapshot.alarms,
                                        save: { store.save($0, $1) }, reloadStored: { store.snapshot.alarms },
                                        notifier: notifier, delivery: delivery, now: { at },
                                        recordLog: { entries, _ in log.add(entries) })
    }

    func testFiredNotReachedAndAlreadyTriggered() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let store = FakeStore()
        let log = LogBox()
        let alarm = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 100, repeating: true)
        await run(alarm, store: store, price: 95, at: now, delivery: delivery, log: log)
        XCTAssertEqual(log.last(7)?.outcome, .NOT_REACHED)
        XCTAssertEqual(log.last(7)?.notable, false)

        await run(alarm, store: store, price: 101, at: now + 60_000, delivery: delivery, log: log)
        XCTAssertEqual(log.last(7)?.outcome, .FIRED)
        XCTAssertEqual(log.last(7)?.notable, true)
        XCTAssertEqual(log.last(7)?.pair, "BTC/USDT")

        await run(alarm, store: store, price: 102, at: now + 120_000, delivery: delivery, log: log)
        XCTAssertEqual(log.last(7)?.outcome, .ALREADY_TRIGGERED)
    }

    func testPercentAlarmInCooldownIsNotable() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        var settings = AppSettings()
        settings.alarmCooldownMinutes = 15
        let log = LogBox()
        let alarm = Alarm(id: 5, watchId: 1, condition: .CHANGE_PERCENT_UP, threshold: 5, repeating: true,
                          referencePrice: 100, lastTriggeredAt: now - 60_000)
        await run(alarm, store: FakeStore(), price: 110, at: now, settings: settings, delivery: delivery, log: log)
        XCTAssertEqual(log.last(5)?.outcome, .COOLDOWN)
        XCTAssertEqual(log.last(5)?.notable, true)
    }

    func testQuietHoursNotificationsOffAndNotShown() async {
        let alarm = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 100)
        var quiet = AppSettings()
        let minute = QuietHours.minuteOfDay()
        quiet.quietHoursEnabled = true
        quiet.quietHoursStart = (minute + 24 * 60 - 60) % (24 * 60)
        quiet.quietHoursEnd = (minute + 60) % (24 * 60)
        let log = LogBox()
        await run(alarm, store: FakeStore(), price: 101, at: now, settings: quiet,
                  delivery: AlarmDelivery(storeKey: "a", store: defaults), log: log)
        XCTAssertEqual(log.last(7)?.outcome, .QUIET_HOURS)

        var off = PriceRefresher.AlarmInputs()
        off.alarmsDeliverable = false
        await run(alarm, store: FakeStore(), price: 101, at: now, inputs: off,
                  delivery: AlarmDelivery(storeKey: "b", store: defaults), log: log)
        XCTAssertEqual(log.last(7)?.outcome, .NOTIFICATIONS_OFF)

        let store = FakeStore()
        let delivery = AlarmDelivery(storeKey: "c", store: defaults)
        await run(alarm, store: store, price: 101, at: now, shows: false, delivery: delivery, log: log)
        XCTAssertEqual(log.last(7)?.outcome, .NOT_SHOWN)
        // Gleich danach: unbestätigter Versuch, Neuversuch erst nach der Abklingzeit
        await run(alarm, store: store, price: 101.5, at: now + 60_000, delivery: delivery, log: log)
        XCTAssertEqual(log.last(7)?.outcome, .COOLDOWN)
    }

    func testConversionWithoutRateIsNoRate() async {
        let alarm = Alarm(id: 9, watchId: 1, condition: .PRICE_ABOVE, threshold: 50, currency: "JPY")
        let log = LogBox()
        await run(alarm, store: FakeStore(), price: 101, at: now,
                  delivery: AlarmDelivery(storeKey: "d", store: defaults), log: log)
        XCTAssertEqual(log.last(9)?.outcome, .NO_RATE)
    }

    func testBlockedPreviewSaysWhetherItWasDue() {
        let before = Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
                           lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        updated.exchangeTime = now - AlarmFreshness.exchangeStaleMillis - 60_000
        let due = Alarm(id: 1, watchId: 1, condition: .PRICE_ABOVE, threshold: 100)
        let notDue = Alarm(id: 2, watchId: 1, condition: .PRICE_ABOVE, threshold: 200)
        let volume = Alarm(id: 3, watchId: 1, condition: .VOLUME_SPIKE, threshold: 3)
        let entries = PriceRefresher.blockedAlarmLog([due, notDue, volume], watch: before, updated: updated,
                                                     lowLiquidity: false, markFallback: false, now: now,
                                                     cooldownMinutes: 0)
        XCTAssertEqual(entries.map(\.alarmId), [1, 2], "Volumen-Alarme nicht erfasst")
        XCTAssertEqual(entries.map(\.outcome), [.STALE, .STALE])
        XCTAssertEqual(entries.map(\.notable), [true, false])
        XCTAssertEqual(entries.first?.exchangeTime, updated.exchangeTime)
    }

    func testDeferredAlarmsAreLoggedToo() {
        // Hintergrund: Alarme mit Zusatzdaten (z. B. Schwelle in anderer Währung) werden erst später geprüft, bekommen
        // «Kein Kurs» bzw. Sperrgründe aber wie auf Android im selben Durchlauf
        let checked = Alarm(id: 1, watchId: 10, condition: .PRICE_ABOVE, threshold: 100, repeating: true)
        let deferred = Alarm(id: 2, watchId: 10, condition: .PRICE_BELOW, threshold: 90, repeating: true)
        let other = Alarm(id: 3, watchId: 20, condition: .PRICE_ABOVE, threshold: 5, repeating: true)
        let all = PriceRefresher.alarmsForLog(checked: [10: [checked]], deferred: [10: [deferred, checked], 20: [other]])
        XCTAssertEqual(all[10]?.map(\.id), [1, 2])
        XCTAssertEqual(all[20]?.map(\.id), [3])
    }
}
