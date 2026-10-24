import XCTest
@testable import CryptoChecker

/// Verdrahtung «neuer Kurs → Alarm prüfen → melden» über den Produktionscode (`PriceRefresher.checkAlarms`,
/// `Outcome.apply`, `Outcome.deliverAlarms` mit `AlarmDelivery`), ergänzend zu `AlarmChainIntegrationTests`:
/// Kreuzen nach unten, Wiederscharfstellen eines wiederholenden Alarms, Nachtruhe, DEX-Pool mit zu wenig
/// Liquidität und Alarme in anderer Währung (mit und ohne Faktor). Spiegel: RefreshEffectsAlarmTest.kt und
/// RefreshResultProcessorTest.kt.
final class RefreshEffectsWiringTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private let minute: Int64 = 60_000
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        super.setUp()
        suiteName = "RefreshEffectsWiringTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    /// Hält Meldungen fest (Alarm-Id, Kurs).
    private final class FakeNotifier: @unchecked Sendable { // @unchecked: `_posts` unter `lock` (NSLock)
        private let lock = NSLock()
        private var _posts: [(alarmId: Int64, price: Double)] = []

        var posts: [(alarmId: Int64, price: Double)] {
            lock.lock(); defer { lock.unlock() }
            return _posts
        }

        var notifier: PriceRefresher.AlarmNotifier {
            PriceRefresher.AlarmNotifier(canShow: { true }, show: { item in
                // async-Closure: nur die bereichsgebundene Sperre (lock()/unlock() sind dort nicht erlaubt)
                self.lock.withLock { self._posts.append((item.alarm.id, item.price)) }
                return true
            })
        }
    }

    /// Gespeicherter Stand (statt Datei).
    private final class FakeStore: @unchecked Sendable { // @unchecked: `stored` nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var stored = SharedStorage.Snapshot()
        var snapshot: SharedStorage.Snapshot {
            get { lock.withLock { stored } }
            set { lock.withLock { stored = newValue } }
        }
        func save(_ id: Int64, _ update: PriceRefresher.AlarmUpdate) -> Bool {
            lock.withLock { update.apply(toAlarm: id, in: &stored) }
        }
    }

    private func makeStore(_ watch: Watch, _ alarm: Alarm) -> FakeStore {
        let store = FakeStore()
        store.snapshot.watches = [watch]
        store.snapshot.alarms = [alarm]
        return store
    }

    private func watch(lastPrice: Double, marketKey: String = "binance", pairId: String? = nil) -> Watch {
        Watch(id: 1, marketKey: marketKey, marketName: "Test", baseAsset: "BTC", quoteAsset: "USDT", pairId: pairId,
              lastPrice: lastPrice)
    }

    /// Nachtruhe, die gerade gilt (eine Stunde vor bis eine Stunde nach der aktuellen Minute).
    private func quietNow() -> AppSettings {
        var settings = AppSettings()
        let minute = QuietHours.minuteOfDay()
        settings.quietHoursEnabled = true
        settings.quietHoursStart = (minute + 24 * 60 - 60) % (24 * 60)
        settings.quietHoursEnd = (minute + 60) % (24 * 60)
        return settings
    }

    /// Ein Durchlauf wie App, Hintergrund und Widget: Alarme prüfen, Stand übernehmen, melden.
    /// - Returns: gemeldete und gespeicherte Alarme.
    private func refresh(price: Double, store: FakeStore, notifier: FakeNotifier, delivery: AlarmDelivery, at: Int64,
                         settings: AppSettings = AppSettings(),
                         inputs: PriceRefresher.AlarmInputs = PriceRefresher.AlarmInputs()) async -> Int {
        let before = store.snapshot.watches[0]
        var updated = before
        updated.previousPrice = before.lastPrice
        updated.lastPrice = price
        var outcome = PriceRefresher.Outcome()
        _ = PriceRefresher.checkAlarms(store.snapshot.alarms, watch: before, updated: updated, price: price, now: at,
                                       settings: settings, speechAllowed: false, inputs: inputs, outcome: &outcome)
        outcome.apply(to: &store.snapshot)
        store.snapshot.watches[0] = updated
        let delivered = await outcome.deliverAlarms(settings: settings, current: store.snapshot.alarms,
                                                    save: { store.save($0, $1) },
                                                    reloadStored: { store.snapshot.alarms },
                                                    notifier: notifier.notifier,
                                                    delivery: delivery, now: { at })
        return delivered.count
    }

    func testCrossingDownFiresOnceAndOneShotSwitchesOff() async {
        let store = makeStore(watch(lastPrice: 101), Alarm(id: 8, watchId: 1, condition: .PRICE_BELOW, threshold: 100))
        let notifier = FakeNotifier()
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)

        let first = await refresh(price: 99, store: store, notifier: notifier, delivery: delivery, at: now)
        XCTAssertEqual(first, 1)
        XCTAssertEqual(notifier.posts.map { $0.alarmId }, [8])
        XCTAssertFalse(store.snapshot.alarms[0].enabled)

        let second = await refresh(price: 98, store: store, notifier: notifier, delivery: delivery, at: now + minute)
        XCTAssertEqual(second, 0)
        XCTAssertEqual(notifier.posts.count, 1)
    }

    func testRepeatingAlarmRearmsAfterTheReturnAndFiresAgain() async {
        let store = makeStore(watch(lastPrice: 99),
                              Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 100, repeating: true))
        let notifier = FakeNotifier()
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)

        let fired = await refresh(price: 101, store: store, notifier: notifier, delivery: delivery, at: now)
        XCTAssertEqual(fired, 1)
        XCTAssertEqual(store.snapshot.alarms[0].referenceAt, now)
        XCTAssertTrue(store.snapshot.alarms[0].enabled)

        // Bleibt über der Marke: keine zweite Meldung
        let above = await refresh(price: 102, store: store, notifier: notifier, delivery: delivery, at: now + 5 * minute)
        XCTAssertEqual(above, 0)

        // Zurück unter die Marke: wieder scharf, ohne Meldung
        let back = await refresh(price: 95, store: store, notifier: notifier, delivery: delivery, at: now + 10 * minute)
        XCTAssertEqual(back, 0)
        XCTAssertEqual(store.snapshot.alarms[0].referenceAt, 0)

        // Erneut darüber: zweite Meldung
        let again = await refresh(price: 101, store: store, notifier: notifier, delivery: delivery, at: now + 20 * minute)
        XCTAssertEqual(again, 1)
        XCTAssertEqual(notifier.posts.count, 2)
    }

    func testQuietHoursHoldTheAlarmArmed() async {
        let original = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 100)
        let store = makeStore(watch(lastPrice: 99), original)
        let notifier = FakeNotifier()
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)

        let held = await refresh(price: 101, store: store, notifier: notifier, delivery: delivery, at: now,
                                 settings: quietNow())
        XCTAssertEqual(held, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(store.snapshot.alarms[0], original)

        // Nach der Nachtruhe meldet er sich normal
        let after = await refresh(price: 101, store: store, notifier: notifier, delivery: delivery, at: now + minute)
        XCTAssertEqual(after, 1)
    }

    func testDexPoolWithLowLiquidityHoldsAlarmsUntilEnoughIsMeasured() async {
        let pool = "solana/wiring-\(UUID().uuidString)"
        defer { DexLiquidityStore.put(pool, nil) }
        let original = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 100)
        let store = makeStore(watch(lastPrice: 99, marketKey: DexLiquidity.marketKey, pairId: pool), original)
        let notifier = FakeNotifier()
        let delivery = AlarmDelivery(storeKey: "pairs", store: defaults)

        DexLiquidityStore.put(pool, 500)
        let held = await refresh(price: 101, store: store, notifier: notifier, delivery: delivery, at: now)
        XCTAssertEqual(held, 0)
        XCTAssertTrue(notifier.posts.isEmpty)
        XCTAssertEqual(store.snapshot.alarms[0], original)

        DexLiquidityStore.put(pool, 1_000_000_000)
        let fired = await refresh(price: 102, store: store, notifier: notifier, delivery: delivery, at: now + minute)
        XCTAssertEqual(fired, 1)
    }

    func testAlarmInOtherCurrencyComparesConvertedPrice() {
        // Schwelle 90 CHF, Paar in USDT, 1 USDT = 0.9 CHF: 101 USDT = 90.9 CHF
        let alarm = Alarm(id: 7, watchId: 1, condition: .PRICE_ABOVE, threshold: 90, currency: "CHF")
        let before = watch(lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        var inputs = PriceRefresher.AlarmInputs()
        inputs.convertRates["1|CHF"] = 0.9
        var outcome = PriceRefresher.Outcome()

        _ = PriceRefresher.checkAlarms([alarm], watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false, inputs: inputs, outcome: &outcome)

        XCTAssertEqual(outcome.pendingAlarms.count, 1)
        XCTAssertEqual(outcome.pendingAlarms.first?.shownPrice ?? 0, 90.9, accuracy: 1e-9)
        XCTAssertEqual(outcome.pendingAlarms.first?.shownCurrency, "CHF")
        // Gespeichert wird der Kurs in der Quote
        XCTAssertEqual(outcome.pendingAlarms.first?.price, 101)
    }

    func testMissingRateSkipsTheAlarmAndMarksItUnchecked() {
        let alarmId: Int64 = 90_017
        defer { AlarmCheckStatus.shared.markChecked(alarmId) }
        let alarm = Alarm(id: alarmId, watchId: 1, condition: .PRICE_ABOVE, threshold: 90, currency: "EUR")
        let before = watch(lastPrice: 99)
        var updated = before
        updated.lastPrice = 101
        var outcome = PriceRefresher.Outcome()

        _ = PriceRefresher.checkAlarms([alarm], watch: before, updated: updated, price: 101, now: now,
                                       settings: AppSettings(), speechAllowed: false,
                                       inputs: PriceRefresher.AlarmInputs(), outcome: &outcome)

        XCTAssertTrue(outcome.pendingAlarms.isEmpty)
        XCTAssertEqual(AlarmCheckStatus.shared.unchecked(alarmId)?.reason, .noRate)
    }
}
