import XCTest
@testable import CryptoChecker

/// Kette Kurs → Alarm → Meldung mit Fakes (Spiegel: AlarmChainIntegrationTest.kt): Kursquelle liefert
/// einen Kurs über der Marke, echte Auswertung (`PriceRefresher.checkAlarms`), echtes Übertragen
/// (`Outcome.apply`) und echter Melde-Ablauf (`Outcome.deliverAlarms` mit `AlarmDelivery`): zuerst melden,
/// erst nach bestätigter Mitteilung speichern. Der Fake-Notifier hält die Meldungen fest, der Fake-Speicher
/// überträgt den Zustand nach dem Auslösen (`AlarmUpdate.apply`).
final class AlarmChainIntegrationTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        super.setUp()
        suiteName = "AlarmChainIntegrationTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Kursquelle (statt Börse).
    private struct FakeMarket {
        var price: Double
        func last() -> Double { price }
    }

    /// Hält Meldungen fest; `shown` = false wie «nicht unter den zugestellten», `deliverable` = false wie
    /// «Mitteilungen aus».
    private final class FakeNotifier: @unchecked Sendable { // @unchecked: `_posts` unter `lock` (NSLock); Schalter nur vor dem Lauf gesetzt
        private let lock = NSLock()
        private var _posts: [(alarmId: Int64, price: Double)] = []
        var shown = true
        var deliverable = true

        var posts: [(alarmId: Int64, price: Double)] {
            lock.lock(); defer { lock.unlock() }
            return _posts
        }

        var notifier: PriceRefresher.AlarmNotifier {
            PriceRefresher.AlarmNotifier(canShow: { self.deliverable }, show: { item in
                self.lock.lock(); defer { self.lock.unlock() }
                self._posts.append((item.alarm.id, item.price))
                return self.shown
            })
        }
    }

    /// Gespeicherter Stand (statt Datei bzw. Stand der App).
    private final class FakeStore: @unchecked Sendable { // @unchecked: Test-Fake, nur nacheinander aus dem Testablauf benutzt (keine Sperre nötig)
        var snapshot = SharedStorage.Snapshot()
        func save(_ id: Int64, _ update: PriceRefresher.AlarmUpdate) -> Bool {
            update.apply(toAlarm: id, in: &snapshot)
        }
    }

    private func watch(lastPrice: Double) -> Watch {
        Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
              lastPrice: lastPrice)
    }

    private func alarmAbove(_ level: Double) -> Alarm {
        Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: level)
    }

    private func makeStore(previous: Double, alarm: Alarm) -> FakeStore {
        let store = FakeStore()
        store.snapshot.watches = [watch(lastPrice: previous)]
        store.snapshot.alarms = [alarm]
        return store
    }

    /// Ein Durchlauf wie App, Hintergrund und Widget: Kurs holen, Alarme prüfen, Kurse speichern, melden.
    /// - Returns: gemeldete und gespeicherte Alarme.
    private func refresh(_ market: FakeMarket, store: FakeStore, notifier: FakeNotifier, delivery: AlarmDelivery,
                         deliverableAtCheck: Bool = true, at: Int64,
                         editBeforeSave: ((inout SharedStorage.Snapshot) -> Void)? = nil,
                         diskAlarms: (@Sendable () -> [Alarm]?)? = nil) async -> Int {
        let price = market.last()
        let before = store.snapshot.watches[0]
        var updated = before
        updated.previousPrice = before.lastPrice
        updated.lastPrice = price
        var inputs = PriceRefresher.AlarmInputs()
        inputs.alarmsDeliverable = deliverableAtCheck
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(store.snapshot.alarms, watch: before, updated: updated, price: price, now: at,
                                       settings: AppSettings(), speechAllowed: false, inputs: inputs,
                                       outcome: &outcome)
        editBeforeSave?(&store.snapshot)
        outcome.apply(to: &store.snapshot)
        store.snapshot.watches[0] = updated
        let delivered = await outcome.deliverAlarms(settings: AppSettings(), current: store.snapshot.alarms,
                                                    save: { store.save($0, $1) },
                                                    reloadStored: { if let diskAlarms { return diskAlarms() }; return store.snapshot.alarms },
                                                    notifier: notifier.notifier,
                                                    delivery: delivery, now: { at })
        return delivered.count
    }

    func testCrossingPostsOnceAndMarksAlarm() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let market = FakeMarket(price: 101)

        let first = await refresh(market, store: store, notifier: notifier, delivery: delivery, at: now)
        XCTAssertEqual(first, 1)
        XCTAssertEqual(notifier.posts.map { $0.alarmId }, [7])
        XCTAssertEqual(notifier.posts.first?.price, 101)
        XCTAssertEqual(store.snapshot.alarms[0].lastTriggeredAt, now)
        XCTAssertGreaterThan(store.snapshot.alarms[0].referenceAt, 0)
        // Einmaliger Alarm: danach aus
        XCTAssertFalse(store.snapshot.alarms[0].enabled)

        // Nächster Durchlauf, Kurs bleibt über der Marke: keine zweite Meldung
        let second = await refresh(market, store: store, notifier: notifier, delivery: delivery, at: now + 60_000)
        XCTAssertEqual(second, 0)
        XCTAssertEqual(notifier.posts.count, 1)
    }

    func testNotShownLeavesAlarmArmed() async {
        let original = alarmAbove(100)
        let store = makeStore(previous: 99, alarm: original)
        let notifier = FakeNotifier()
        notifier.shown = false
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let market = FakeMarket(price: 101)

        let first = await refresh(market, store: store, notifier: notifier, delivery: delivery, at: now)
        XCTAssertEqual(first, 0)
        XCTAssertEqual(notifier.posts.count, 1)
        // Nicht gespeichert: bleibt scharf und eingeschaltet
        XCTAssertEqual(store.snapshot.alarms[0], original)

        // Gleich danach: kein erneuter Versuch (Abklingzeit), später schon — dann gespeichert
        notifier.shown = true
        let soon = await refresh(market, store: store, notifier: notifier, delivery: delivery, at: now + 60_000)
        XCTAssertEqual(soon, 0)
        XCTAssertEqual(notifier.posts.count, 1)
        let later = now + 60 * 60_000
        let retried = await refresh(market, store: store, notifier: notifier, delivery: delivery, at: later)
        XCTAssertEqual(retried, 1)
        XCTAssertEqual(notifier.posts.count, 2)
        XCTAssertEqual(store.snapshot.alarms[0].lastTriggeredAt, later)
    }

    /// iOS: App, Hintergrund und Widget sind eigene Prozesse — der unbestätigte Versuch des einen sperrt den
    /// Neuversuch des anderen (gemeinsamer App-Group-Speicher), sonst käme die Meldung doppelt.
    func testUnconfirmedAttemptBlocksOtherProcess() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()
        notifier.shown = false
        let market = FakeMarket(price: 101)

        let widget = await refresh(market, store: store, notifier: notifier,
                                   delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now)
        XCTAssertEqual(widget, 0)
        notifier.shown = true
        let app = await refresh(market, store: store, notifier: notifier,
                                delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now + 60_000)
        XCTAssertEqual(app, 0)
        XCTAssertEqual(notifier.posts.count, 1)
        XCTAssertTrue(store.snapshot.alarms[0].enabled)
    }

    func testUndeliverableNeitherPostsNorMarks() async {
        let original = alarmAbove(100)
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)

        // Schon bei der Prüfung keine Erlaubnis
        let atCheck = makeStore(previous: 99, alarm: original)
        let notifier = FakeNotifier()
        let none = await refresh(FakeMarket(price: 101), store: atCheck, notifier: notifier, delivery: delivery,
                                 deliverableAtCheck: false, at: now)
        XCTAssertEqual(none, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(atCheck.snapshot.alarms[0], original)

        // Erlaubnis zwischen Prüfung und Melden entzogen
        let atDelivery = makeStore(previous: 99, alarm: original)
        notifier.deliverable = false
        let stillNone = await refresh(FakeMarket(price: 101), store: atDelivery, notifier: notifier,
                                      delivery: delivery, at: now)
        XCTAssertEqual(stillNone, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(atDelivery.snapshot.alarms[0], original)
    }

    func testAlarmEditedDuringRefreshIsNotPostedOrMarked() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()

        // Nutzer ändert die Marke, während der Durchlauf läuft: Änderung bleibt, keine Meldung
        let count = await refresh(FakeMarket(price: 101), store: store, notifier: notifier,
                                  delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now) { s in
            s.alarms[0].threshold = 150
        }
        XCTAssertEqual(count, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(store.snapshot.alarms[0].threshold, 150)
        XCTAssertEqual(store.snapshot.alarms[0].lastTriggeredAt, 0)
        XCTAssertTrue(store.snapshot.alarms[0].enabled)
    }

    func testAlarmFiredByOtherProcessSinceEvaluationIsNotPostedAgain() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()
        // Stand im Speicher veraltet; auf der Platte hat ein anderer Prozess (übernommene Lease) den
        // Alarm seit der Auswertung schon ausgelöst → keine zweite Meldung
        var fired = store.snapshot.alarms
        fired[0].lastTriggeredAt = now - 1_000
        let onDisk = fired
        let count = await refresh(FakeMarket(price: 101), store: store, notifier: notifier,
                                  delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now,
                                  diskAlarms: { onDisk })
        XCTAssertEqual(count, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
    }

    func testUnreadableDiskFallsBackToCurrent() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()
        // Platte nicht lesbar (nil): Fail-open, gemeldet wird nach dem übergebenen Stand
        let count = await refresh(FakeMarket(price: 101), store: store, notifier: notifier,
                                  delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now,
                                  diskAlarms: { nil })
        XCTAssertEqual(count, 1)
        XCTAssertEqual(notifier.posts.map { $0.alarmId }, [7])
    }

    func testPriceBelowLevelDoesNothing() async {
        let store = makeStore(previous: 99, alarm: alarmAbove(100))
        let notifier = FakeNotifier()
        let count = await refresh(FakeMarket(price: 99.5), store: store, notifier: notifier,
                                  delivery: AlarmDelivery(storeKey: "pairs", store: defaults), at: now)
        XCTAssertEqual(count, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
    }

    /// Vermerk «wird gemeldet» schon vor dem Melden: Endet der Prozess zwischen Melden und Speichern, sperrt er den
    /// sofortigen Neuversuch (Abklingzeit); nach dem Speichern ist er wieder weg.
    func testDeliveryMarkerSetBeforeShowAndClearedAfterSave() async {
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)
        let at = now
        let seen = LockedValue<Int64?>(nil)
        let saved = await delivery.deliver(alarmId: 7, now: at, cooldownMinutes: 0, holdAlarms: false,
                                           canShow: { true },
                                           show: { seen.set(delivery.unconfirmedAt(7)); return true },
                                           mark: { true })
        XCTAssertTrue(saved)
        XCTAssertEqual(seen.get(), at)
        XCTAssertNil(delivery.unconfirmedAt(7))

        // Gemeldet, Speichern läuft noch (wie «Prozess hier beendet»): Vermerk steht, Neuversuch gesperrt
        // Speichern wartet, bis der Test es freigibt (nicht eine feste Sekunde): sonst wäre es auf einer langsamen
        // Maschine schon fertig und der Vermerk wieder weg, bevor der Test nachsieht
        let crashed = AlarmDelivery(storeKey: "pairs-crash", store: defaults)
        let saveMayFinish = LockedValue(false)
        let blocked = Task {
            await crashed.deliver(alarmId: 8, now: at, cooldownMinutes: 0, holdAlarms: false, canShow: { true },
                                  show: { true },
                                  mark: {
                                      for _ in 0..<3_000 where !saveMayFinish.get() {
                                          try? await Task.sleep(nanoseconds: 10_000_000)
                                      }
                                      return true
                                  })
        }
        // Warten, bis gemeldet ist (Vermerk gesetzt; höchstens 10 s), dann wie ein zweiter Prozess nachsehen
        for _ in 0..<1_000 where crashed.unconfirmedAt(8) == nil { try? await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertEqual(crashed.unconfirmedAt(8), at)
        XCTAssertTrue(AlarmDelivery.retryBlocked(unconfirmedAt: crashed.unconfirmedAt(8), now: at + 60_000,
                                                 cooldownMinutes: 0))
        // Speichern fertig: Vermerk weg
        saveMayFinish.set(true)
        _ = await blocked.value
        XCTAssertNil(crashed.unconfirmedAt(8))
    }

    /// Eintrag behält die Id, wird aber seit der Abfrage auf ein anderes Paar geändert (Bearbeiten, Wiederherstellung):
    /// Hintergrund und Widget filtern wie die App (`keepingCurrentPairs` mit dem frisch gespeicherten Stand) — keine
    /// Meldung, der Alarm bleibt scharf.
    func testChangedPairSameIdIsNotDeliveredAndAlarmStaysArmed() async {
        let original = alarmAbove(100)
        let store = makeStore(previous: 99, alarm: original)
        let notifier = FakeNotifier()
        let before = store.snapshot.watches[0]
        var updated = before
        updated.lastPrice = 101
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(store.snapshot.alarms, watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false, inputs: PriceRefresher.AlarmInputs(),
                                       outcome: &outcome)
        XCTAssertEqual(outcome.pendingAlarms.count, 1)
        // Inzwischen gespeichert: gleiche Id, anderes Paar
        store.snapshot.watches[0].baseAsset = "ETH"
        outcome.apply(to: &store.snapshot)
        let delivered = await outcome.keepingCurrentPairs(store.snapshot.watches)
            .deliverAlarms(settings: AppSettings(), current: store.snapshot.alarms,
                           save: { store.save($0, $1) }, reloadStored: { store.snapshot.alarms },
                           notifier: notifier.notifier,
                           delivery: AlarmDelivery(storeKey: "pairs", store: defaults), now: { self.now })
        XCTAssertTrue(delivered.isEmpty)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(store.snapshot.alarms[0], original)
        XCTAssertTrue(store.snapshot.alarms[0].enabled)
    }

    /// Speichern nach dem Melden (`saveAlarmOnDisk`, `AlarmUpdate.apply`) verweigert, wenn der Eintrag des Alarms
    /// inzwischen ein anderes Paar ist — gleiches Paar wird übertragen.
    func testAlarmUpdateRefusesChangedPair() {
        let alarm = alarmAbove(100)
        var after = alarm
        after.enabled = false
        after.lastTriggeredAt = now
        let pair = watch(lastPrice: 101).identity
        let update = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after, setsEnabled: true, pair: pair)

        let changed = makeStore(previous: 99, alarm: alarm)
        changed.snapshot.watches[0].quoteAsset = "USDC"
        XCTAssertFalse(update.apply(toAlarm: alarm.id, in: &changed.snapshot))
        XCTAssertEqual(changed.snapshot.alarms[0], alarm)

        let same = makeStore(previous: 99, alarm: alarm)
        XCTAssertTrue(update.apply(toAlarm: alarm.id, in: &same.snapshot))
        XCTAssertFalse(same.snapshot.alarms[0].enabled)
        XCTAssertEqual(same.snapshot.alarms[0].lastTriggeredAt, now)
    }

    /// Wie `NotificationBudget.retryBlocked` (Android): mindestens 5 Min., sonst die Abklingzeit; Uhr zurück: frei.
    func testRetryBlockedWindow() {
        XCTAssertFalse(AlarmDelivery.retryBlocked(unconfirmedAt: nil, now: now, cooldownMinutes: 0))
        XCTAssertTrue(AlarmDelivery.retryBlocked(unconfirmedAt: now, now: now + 4 * 60_000, cooldownMinutes: 0))
        XCTAssertFalse(AlarmDelivery.retryBlocked(unconfirmedAt: now, now: now + 5 * 60_000, cooldownMinutes: 0))
        XCTAssertTrue(AlarmDelivery.retryBlocked(unconfirmedAt: now, now: now + 20 * 60_000, cooldownMinutes: 30))
        XCTAssertFalse(AlarmDelivery.retryBlocked(unconfirmedAt: now, now: now - 1, cooldownMinutes: 30))
    }
}

/// Wert über Threads hinweg (für die @Sendable-Closures der Tests).
private final class LockedValue<T>: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ newValue: T) { lock.lock(); value = newValue; lock.unlock() }
}
