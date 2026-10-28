import XCTest
@testable import CryptoChecker

/// Reihenfolge der Kurs-Schreiber (`PriceWriteOrder`, `Parity/price_write_order.json`, wie `PriceWriteOrderTest.kt`):
/// Ein älterer Kurs überschreibt keinen neueren — weder im Speicher noch auf der Platte (`Outcome.apply(to:)`), und
/// seine Alarme werden nicht gemeldet. Dazu das Schreib-Gate (`PriceWriteGate`): Ergebnisse der Reihe nach (FIFO),
/// Abbruch beim Warten ohne Drehen.
@MainActor
final class PriceWriteOrderTests: XCTestCase {
    private typealias Outcome = PriceRefresher.Outcome
    private static let start: Int64 = 1_792_000_000_000

    private static func btc(price: Double = 100, at time: Int64 = start) -> Watch {
        var w = Watch(id: 1, exchangeKey: "binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        w.lastPrice = price
        w.lastUpdate = time
        return w
    }

    private static func outcome(price: Double, at time: Int64, pair: WatchIdentity.Key) -> Outcome {
        var outcome = Outcome()
        outcome.prices[1] = PriceRefresher.PriceUpdate(price: price, time: time, error: nil, priceChangedAt: time,
                                                       pair: pair)
        return outcome
    }

    func testParityCases() throws {
        let fixture = try TestSupport.fixture("price_write_order")
        let now = TestSupport.millis(fixture["now"])
        XCTAssertEqual(TestSupport.millis(fixture["futureToleranceMillis"]), PriceWriteOrder.futureToleranceMillis)
        let cases = TestSupport.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let newTime = now - TestSupport.millis(c["newAgo"])
            let stored = TestSupport.number(c["storedAgo"]).map { now - Int64($0) } ?? 0
            XCTAssertEqual(PriceWriteOrder.accepts(newTime: newTime, storedTime: stored, now: now),
                           c["accepted"] as? Bool, name)
        }
    }

    /// Zwei Ergebnisse desselben Paars in vertauschter Reihenfolge: Der neuere Kurs bleibt (samt Vorkurs und Zeit).
    func testOlderOutcomeAfterNewerKeepsNewerPrice() {
        let watch = Self.btc()
        let older = Self.outcome(price: 101, at: Self.start + 1_000, pair: watch.identity)
        let newer = Self.outcome(price: 102, at: Self.start + 2_000, pair: watch.identity)
        let now = Self.start + 3_000

        var swapped = SharedStorage.Snapshot()
        swapped.watches = [watch]
        newer.apply(to: &swapped, now: now)
        older.apply(to: &swapped, now: now)
        XCTAssertEqual(swapped.watches[0].lastPrice, 102)
        XCTAssertEqual(swapped.watches[0].previousPrice, 100)
        XCTAssertEqual(swapped.watches[0].lastUpdate, Self.start + 2_000)
        XCTAssertEqual(swapped.watches[0].priceChangedAt, Self.start + 2_000)

        // Richtige Reihenfolge: beide übernommen
        var ordered = SharedStorage.Snapshot()
        ordered.watches = [watch]
        older.apply(to: &ordered, now: now)
        newer.apply(to: &ordered, now: now)
        XCTAssertEqual(ordered.watches[0].lastPrice, 102)
        XCTAssertEqual(ordered.watches[0].previousPrice, 101)
    }

    /// Ein gespeicherter Zeitpunkt weit in der Zukunft (Uhr zurückgestellt) sperrt keinen neuen Kurs.
    func testImplausibleFutureStoredTimeDoesNotFreezePrice() {
        let now = Self.start
        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = [Self.btc(at: now + PriceWriteOrder.futureToleranceMillis + 1)]
        Self.outcome(price: 105, at: now, pair: snapshot.watches[0].identity).apply(to: &snapshot, now: now)
        XCTAssertEqual(snapshot.watches[0].lastPrice, 105)
        XCTAssertEqual(snapshot.watches[0].lastUpdate, now)
    }

    /// Verworfener älterer Kurs: weder Alarm-Bezug übernehmen noch den ausgelösten Alarm melden (wie Android: Alarme
    /// nur für geschriebene Kurse). Eine schon gezeigte Kurs-Mitteilung zählt trotzdem.
    func testAlarmsOfDroppedOlderPriceAreNotApplied() {
        let watch = Self.btc(price: 99, at: Self.start + 5_000)
        var alarm = Alarm(id: 7, watchId: 1, condition: .CHANGE_PERCENT_UP, threshold: 5)
        var after = alarm
        after.referencePrice = 101
        after.referenceAt = Self.start + 1_000
        var older = Self.outcome(price: 101, at: Self.start + 1_000, pair: watch.identity)
        older.alarms[7] = PriceRefresher.AlarmUpdate(evaluated: alarm, result: after, setsEnabled: false)
        let above = Alarm(id: 8, watchId: 1, condition: .PRICE_ABOVE, threshold: 100)
        var fired = above
        fired.enabled = false
        older.pendingAlarms = [PriceRefresher.PendingAlarm(
            watch: watch, alarm: above, price: 101,
            update: PriceRefresher.AlarmUpdate(evaluated: above, result: fired, setsEnabled: true))]
        older.notified[1] = (101, Self.start + 1_000)

        var snapshot = SharedStorage.Snapshot()
        snapshot.watches = [watch]
        alarm.referencePrice = nil
        snapshot.alarms = [alarm, above]
        older.apply(to: &snapshot, now: Self.start + 6_000)

        XCTAssertEqual(snapshot.watches[0].lastPrice, 99)
        XCTAssertNil(snapshot.alarms[0].referencePrice)
        XCTAssertEqual(snapshot.watches[0].notifiedPrice, 101)
        XCTAssertTrue(older.keepingCurrentPairs(snapshot.watches).pendingAlarms.isEmpty)

        // Übernommener Kurs: der ausgelöste Alarm bleibt zum Melden
        var fresh = SharedStorage.Snapshot()
        fresh.watches = [Self.btc(price: 99, at: Self.start)]
        fresh.alarms = [alarm, above]
        older.apply(to: &fresh, now: Self.start + 6_000)
        XCTAssertEqual(fresh.watches[0].lastPrice, 101)
        XCTAssertEqual(older.keepingCurrentPairs(fresh.watches).pendingAlarms.count, 1)
    }

    /// Einzelabfrage läuft (langsame Börse, älterer Kurs), eine vollständige Aktualisierung startet danach und ist
    /// zuerst fertig: Das Gate wendet der Reihe nach an, der neuere Kurs der vollständigen bleibt.
    func testSingleRefreshFinishingAfterFullRefreshKeepsNewerPrice() async {
        let gate = PriceWriteGate()
        let box = SnapshotBox(Self.btc())
        let pair = box.snapshot.watches[0].identity
        let one = Self.outcome(price: 101, at: Self.start + 1_000, pair: pair)
        let all = Self.outcome(price: 102, at: Self.start + 2_000, pair: pair)
        // Ein anderer Schreiber (z. B. Live-Kurse) hält das Gate: beide Ergebnisse stellen sich an
        XCTAssertTrue(gate.tryAcquire())
        let fullRefresh = Task { @MainActor in
            await PriceWriteOrderTests.applyInGate(all, "all", gate: gate, box: box)
        }
        await Self.waitUntil { gate.waitingCount == 1 }
        let singleRefresh = Task { @MainActor in
            await PriceWriteOrderTests.applyInGate(one, "one", gate: gate, box: box)
        }
        await Self.waitUntil { gate.waitingCount == 2 }
        XCTAssertFalse(gate.tryAcquire())
        gate.release()
        await fullRefresh.value
        await singleRefresh.value

        XCTAssertEqual(box.order, ["all", "one"])
        XCTAssertEqual(box.snapshot.watches[0].lastPrice, 102)
        XCTAssertEqual(box.snapshot.watches[0].lastUpdate, Self.start + 2_000)
        XCTAssertFalse(gate.isBusy)
    }

    /// Wartender im Gate bricht ab: endet sofort mit false (kein Drehen), verlässt die Reihe, das Gate bleibt beim
    /// Halter; der nächste kommt danach regulär dran.
    func testCancelledWaiterLeavesQueue() async {
        let gate = PriceWriteGate()
        XCTAssertTrue(gate.tryAcquire())
        let cancelled = Task { @MainActor in await gate.acquire() }
        await Self.waitUntil { gate.waitingCount == 1 }
        let next = Task { @MainActor in await gate.acquire() }
        await Self.waitUntil { gate.waitingCount == 2 }
        cancelled.cancel()
        let entered = await cancelled.value
        XCTAssertFalse(entered)
        XCTAssertEqual(gate.waitingCount, 1)
        XCTAssertTrue(gate.isBusy)
        gate.release()
        let nextEntered = await next.value
        XCTAssertTrue(nextEntered)
        XCTAssertTrue(gate.isBusy)
        gate.release()
        XCTAssertFalse(gate.isBusy)
    }

    /// Schon abgebrochen vor dem Warten: gar nicht anstellen. Ohne `cancellable` wartet ein geholtes Ergebnis trotzdem.
    func testCancellationModes() async {
        let gate = PriceWriteGate()
        XCTAssertTrue(gate.tryAcquire())
        // Abgebrochen, bevor der Task überhaupt läuft (er wartet auf den MainActor)
        let early = Task { @MainActor in await gate.acquire() }
        early.cancel()
        let earlyEntered = await early.value
        XCTAssertFalse(earlyEntered)
        XCTAssertEqual(gate.waitingCount, 0)

        let keeps = Task { @MainActor in await gate.acquire(cancellable: false) }
        await Self.waitUntil { gate.waitingCount == 1 }
        keeps.cancel()
        await Self.waitUntil(rounds: 50) { false } // Abbruch hätte Zeit, falsch zu wirken
        XCTAssertEqual(gate.waitingCount, 1)
        gate.release()
        let kept = await keeps.value
        XCTAssertTrue(kept)
        gate.release()
        XCTAssertFalse(gate.isBusy)
    }

    /// `AppData.waitForLiveApply` beachtet den Abbruch: Hält ein anderer Schreiber das Gate, endet das Warten nach
    /// dem Abbruch sofort mit false — statt ohne Pause auf dem Haupt-Thread zu drehen.
    func testWaitForLiveApplyHonoursCancellation() async {
        let gate = PriceWriteGate.shared
        // Belegt ein Schreiber der Test-App das Gate gerade, kurz abwarten
        let holding = await gate.acquire()
        XCTAssertTrue(holding)
        guard holding else { return }
        defer { gate.release() }
        let data = AppData.shared
        let waiting = Task { @MainActor in await data.waitForLiveApply() }
        await Self.waitUntil { gate.waitingCount == 1 }
        waiting.cancel()
        let finished = await waiting.value
        XCTAssertFalse(finished)
        XCTAssertEqual(gate.waitingCount, 0)
    }

    // MARK: Hilfen

    /// Stand im Speicher und Reihenfolge des Anwendens (nur auf dem MainActor).
    @MainActor
    private final class SnapshotBox {
        var snapshot = SharedStorage.Snapshot()
        var order: [String] = []

        init(_ watch: Watch) {
            snapshot.watches = [watch]
        }
    }

    private static func applyInGate(_ outcome: Outcome, _ name: String, gate: PriceWriteGate, box: SnapshotBox) async {
        _ = await gate.acquire(cancellable: false)
        defer { gate.release() }
        outcome.apply(to: &box.snapshot, now: start + 3_000)
        box.order.append(name)
    }

    /// Lässt andere Tasks auf dem MainActor laufen, bis `condition` gilt (höchstens `rounds` Runden).
    private static func waitUntil(rounds: Int = 2_000, _ condition: () -> Bool) async {
        for _ in 0..<rounds {
            if condition() { return }
            await Task.yield()
        }
    }
}
