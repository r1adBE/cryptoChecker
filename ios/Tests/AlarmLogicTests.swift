import XCTest
@testable import CryptoChecker

/// Wie `AlarmEvaluatorTest.kt` und `LevelAlarmTest.kt` (weitere Fälle in `ParityFixtureTests`).
final class AlarmLogicTests: XCTestCase {

    private let now: Int64 = 1_700_000_000_000

    private func alarm(_ condition: AlarmCondition, _ threshold: Double, enabled: Bool = true,
                       repeating: Bool = true, referencePrice: Double? = nil, lastTriggeredAt: Int64 = 0,
                       windowHours: Int = 1, referenceAt: Int64 = 0) -> Alarm {
        Alarm(id: 1, watchId: 1, condition: condition, threshold: threshold, enabled: enabled, repeating: repeating,
              referencePrice: referencePrice, lastTriggeredAt: lastTriggeredAt, windowHours: windowHours,
              referenceAt: referenceAt)
    }

    /// Spielt eine Kursfolge durch wie der PriceRefresher; Ergebnis = Anzahl Meldungen.
    private func run(_ start: Alarm, _ prices: [Double], cooldownMinutes: Int = 0, stepMillis: Int64 = 15_000) -> Int {
        var a = start
        var fired = 0
        for (i, price) in prices.enumerated() {
            let time = now + Int64(i) * stepMillis
            if !a.enabled { continue }
            if AlarmEvaluator.shouldRearmLevel(alarm: a, price: price) {
                a.referenceAt = 0
                continue
            }
            if AlarmEvaluator.shouldTrigger(alarm: a, price: price, previousPrice: nil, now: time,
                                            cooldownMinutes: cooldownMinutes) {
                fired += 1
                a.lastTriggeredAt = time
                a.lastTriggeredPrice = price
                a.referenceAt = time
                a.enabled = a.repeating
            }
        }
        return fired
    }

    func testRepeatingAboveFiresTwiceOverTheCrossingSequence() {
        // unter → über → über → über → unter (Hysterese) → über
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000), [99_000, 100_500, 101_000, 100_200, 99_700, 100_100]), 2)
    }

    func testRepeatingBelowFiresTwiceOverTheMirroredSequence() {
        XCTAssertEqual(run(alarm(.PRICE_BELOW, 100_000), [101_000, 99_500, 99_000, 99_800, 100_300, 99_900]), 2)
    }

    func testDipInsideTheHysteresisDoesNotRearm() {
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000), [99_000, 100_500, 99_850, 100_500, 99_850, 100_500]), 1)
    }

    func testAlreadyBeyondOnFirstEvaluationFiresOnce() {
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000), Array(repeating: 105_000, count: 10)), 1)
    }

    func testOneShotFiresOnceAndSwitchesOff() {
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000, repeating: false), [99_000, 100_500, 99_000, 100_500]), 1)
    }

    func testCooldownAppliesOnTopOfRearming() {
        let prices: [Double] = [99_000, 100_500, 99_000, 100_500]
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000), prices, cooldownMinutes: 30), 1)
        XCTAssertEqual(run(alarm(.PRICE_ABOVE, 100_000), prices, cooldownMinutes: 30, stepMillis: 20 * 60_000), 2)
    }

    func testFiredAlarmRearmsOnlyBeyondTheHysteresis() {
        let fired = alarm(.PRICE_ABOVE, 100, referenceAt: now)
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: fired, price: 150, previousPrice: nil, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldRearmLevel(alarm: fired, price: 99.85))
        XCTAssertTrue(AlarmEvaluator.shouldRearmLevel(alarm: fired, price: 99.7))
        let firedBelow = alarm(.PRICE_BELOW, 100, referenceAt: now)
        XCTAssertFalse(AlarmEvaluator.shouldRearmLevel(alarm: firedBelow, price: 100.15))
        XCTAssertTrue(AlarmEvaluator.shouldRearmLevel(alarm: firedBelow, price: 100.3))
        XCTAssertFalse(AlarmEvaluator.shouldRearmLevel(alarm: alarm(.PRICE_ABOVE, 100), price: 50))
        XCTAssertFalse(AlarmEvaluator.shouldRearmLevel(alarm: alarm(.CHANGE_PERCENT_UP, 5, referenceAt: now), price: 1))
    }

    func testPriceThresholdsAndCooldown() {
        let above = alarm(.PRICE_ABOVE, 100)
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: above, price: 100, previousPrice: 90, now: now, cooldownMinutes: 30))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: above, price: 99.99, previousPrice: 90, now: now, cooldownMinutes: 30))
        let recent = alarm(.PRICE_ABOVE, 100, lastTriggeredAt: now - 5 * 60_000)
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: recent, price: 120, previousPrice: 90, now: now, cooldownMinutes: 30))
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: recent, price: 120, previousPrice: 90, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: alarm(.PRICE_ABOVE, 1, enabled: false), price: 1000,
                                                    previousPrice: 1, now: now, cooldownMinutes: 30))
    }

    func testPercentAndMoveWindow() {
        let up = alarm(.CHANGE_PERCENT_UP, 5, referencePrice: 100)
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: up, price: 105, previousPrice: nil, now: now, cooldownMinutes: 30))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: up, price: 104.9, previousPrice: nil, now: now, cooldownMinutes: 30))
        let down = alarm(.CHANGE_PERCENT_DOWN, 10)
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: down, price: 90, previousPrice: 100, now: now, cooldownMinutes: 30))
        let move = alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, windowHours: 4, referenceAt: now - 3_600_000)
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: move, price: 95, previousPrice: 100, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: move, price: 103, previousPrice: 100, now: now, cooldownMinutes: 0))
        // Bezug 2 h alt, Fenster 1 h (Spielraum bis 1,5 h): kein Vergleich, aber auch kein Neubeginn
        let expired = alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, windowHours: 1, referenceAt: now - 7_200_000)
        XCTAssertFalse(AlarmEvaluator.needsWindowReset(alarm: expired, now: now))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: expired, price: 120, previousPrice: 100, now: now, cooldownMinutes: 0))
    }

    /// Wie `AlarmEvaluatorTest.move window checks the old reference after the window ran out`.
    func testMoveWindowChecksTheOldReferenceAfterTheWindowRanOut() {
        let a = alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, windowHours: 1, referenceAt: now - 61 * 60_000)
        XCTAssertFalse(AlarmEvaluator.needsReference(alarm: a, now: now))
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: a, price: 106, previousPrice: 100, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: a, price: 104, previousPrice: 100, now: now, cooldownMinutes: 0))
    }

    /// Wie `AlarmEvaluatorTest.move window slides over the price history`.
    func testMoveWindowSlidesOverThePriceHistory() {
        let minute: Int64 = 60_000
        let a = alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, windowHours: 1, referenceAt: now - 200 * minute)
        let history = [
            MoveWindow.PricePoint(price: 100, time: now - 200 * minute),
            MoveWindow.PricePoint(price: 100, time: now - 50 * minute),
            MoveWindow.PricePoint(price: 101, time: now - 20 * minute),
            MoveWindow.PricePoint(price: 103, time: now - 10 * minute),
        ]
        XCTAssertTrue(AlarmEvaluator.shouldTrigger(alarm: a, price: 105.5, previousPrice: nil, now: now, cooldownMinutes: 0,
                                                   moveHistory: history))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: a, price: 104.5, previousPrice: nil, now: now, cooldownMinutes: 0,
                                                    moveHistory: history))
        // Punkte vor referenceAt (letzte Meldung) zählen nicht
        var reported = a
        reported.referencePrice = 103
        reported.referenceAt = now - 10 * minute
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: reported, price: 105.5, previousPrice: nil, now: now,
                                                    cooldownMinutes: 0, moveHistory: history))
    }

    /// Wie `MoveWindowTest.hourlyBackgroundRunsReportTheMove`.
    func testMoveWindowHourlyBackgroundRunsReportTheMove() {
        var a = alarm(.MOVE_PERCENT_WINDOW, 5, repeating: false)
        var history: [MoveWindow.PricePoint] = []
        var fired = 0
        let times: [Int64] = [0, 55, 118, 175].map { now + $0 * 60_000 }
        let prices: [Double] = [100, 101, 104, 109.5]
        for (t, p) in zip(times, prices) {
            if AlarmEvaluator.needsReference(alarm: a, now: t) {
                a.referencePrice = p
                a.referenceAt = t
            } else if AlarmEvaluator.shouldTrigger(alarm: a, price: p, previousPrice: nil, now: t, cooldownMinutes: 0,
                                                   moveHistory: history) {
                fired += 1
                a = AlarmEvaluator.triggered(a, price: p, time: t)
            }
            history = MoveWindow.append(history, MoveWindow.PricePoint(price: p, time: t), now: t)
        }
        XCTAssertEqual(fired, 1)
        XCTAssertFalse(a.enabled)
    }

    /// Wie `MoveWindowTest.historyOverADayStaysSmall`.
    func testMoveWindowHistoryOverADayStaysSmall() {
        var history: [MoveWindow.PricePoint] = []
        var time = now
        var price = 1_000.0
        for _ in 0..<(30 * 60) {
            history = MoveWindow.append(history, MoveWindow.PricePoint(price: price, time: time), now: time)
            price += 0.1
            time += 60_000
        }
        let last = time - 60_000
        XCTAssertLessThan(history.count, 200)
        for hours in [1, 4, 12, 24] {
            let change = MoveWindow.changePercent(history: history, reference: nil, price: price, hours: hours, since: 0, now: last)
            XCTAssertGreaterThan(change ?? 0, 0, "window \(hours)")
        }
        XCTAssertTrue(MoveWindow.prune(history, now: last + MoveWindow.retentionMillis + 60_000 * 3).isEmpty)
        XCTAssertEqual(MoveWindow.maxAgeMillis(1), 5_400_000)
        XCTAssertEqual(MoveWindow.maxAgeMillis(24), 30 * 3_600_000)
    }

    /// Wie `AlarmEvaluatorTest.missing or future reference needs a new one`.
    func testMissingOrFutureReferenceNeedsANewOne() {
        let move = alarm(.MOVE_PERCENT_WINDOW, 5)
        XCTAssertTrue(AlarmEvaluator.needsReference(alarm: move, now: now))
        XCTAssertTrue(AlarmEvaluator.needsReference(alarm: alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100), now: now))
        XCTAssertTrue(AlarmEvaluator.needsReference(
            alarm: alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, referenceAt: now + 600_000), now: now))
        XCTAssertFalse(AlarmEvaluator.needsReference(
            alarm: alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, referenceAt: now - 1), now: now))
        XCTAssertTrue(AlarmEvaluator.needsReference(alarm: alarm(.CHANGE_PERCENT_UP, 5), now: now))
        XCTAssertTrue(AlarmEvaluator.needsReference(alarm: alarm(.CHANGE_PERCENT_DOWN, 5, referencePrice: 0), now: now))
        XCTAssertFalse(AlarmEvaluator.needsReference(alarm: alarm(.CHANGE_PERCENT_DOWN, 5, referencePrice: 90), now: now))
        XCTAssertFalse(AlarmEvaluator.needsReference(alarm: alarm(.PRICE_ABOVE, 5), now: now))
    }

    /// Wie `AlarmEvaluatorTest.fields after triggering`.
    func testFieldsAfterTriggering() {
        let percent = AlarmEvaluator.triggered(alarm(.CHANGE_PERCENT_UP, 5, repeating: false, referencePrice: 100, referenceAt: 7),
                                               price: 105, time: now)
        XCTAssertEqual(percent.referencePrice, 105)
        XCTAssertEqual(percent.referenceAt, 7)
        XCTAssertFalse(percent.enabled)
        XCTAssertEqual(percent.lastTriggeredAt, now)
        let move = AlarmEvaluator.triggered(alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100), price: 106, time: now)
        XCTAssertEqual(move.referencePrice, 106)
        XCTAssertEqual(move.referenceAt, now)
        XCTAssertTrue(move.enabled)
        let volume = AlarmEvaluator.triggered(alarm(.VOLUME_SPIKE, 3), price: 50, time: now, candleOpenTime: 123)
        XCTAssertEqual(volume.referenceAt, 123)
        XCTAssertNil(volume.referencePrice)
        let near = AlarmEvaluator.triggered(alarm(.NEAR_HIGH, 0), price: 101, time: now, nearLevel: 101)
        XCTAssertEqual(near.referencePrice, 101)
        XCTAssertEqual(near.referenceAt, now)
        let level = AlarmEvaluator.triggered(alarm(.PRICE_ABOVE, 100), price: 101, time: now)
        XCTAssertNil(level.referencePrice)
        XCTAssertEqual(level.referenceAt, now)
    }

    /// Wie `NearExtremeTest.newHighDoesNotRepeatTheSameDayAfterRearming`.
    func testNewHighDoesNotRepeatTheSameDayAfterRearming() {
        let range = NearExtreme.Range(high: 100, low: 50)
        let reportedAt = now - 2 * 3_600_000
        func decide(_ side: NearExtreme.Side = .high, _ price: Double, armed: Bool, lastLevel: Double?) -> NearExtreme.Decision {
            NearExtreme.decide(side: side, price: price, range: range, thresholdPercent: 0, armed: armed, lastLevel: lastLevel,
                               inCooldown: false, lastTriggeredAt: reportedAt, now: now)
        }
        XCTAssertEqual(decide(.high, 99.4, armed: false, lastLevel: 101), .rearm)
        let mark = NearExtreme.reportedMark(lastLevel: 101, lastTriggeredAt: reportedAt, windowDays: 30, now: now)
        XCTAssertEqual(mark, 101)
        XCTAssertEqual(decide(.high, 100.1, armed: true, lastLevel: mark), .idle)
        if case .fire = decide(.high, 101.2, armed: true, lastLevel: mark) {} else { XCTFail("101.2 ist ein neues Hoch") }
        XCTAssertEqual(decide(.low, 49.9, armed: true, lastLevel: 49), .idle)
        if case .fire = decide(.low, 48.9, armed: true, lastLevel: 49) {} else { XCTFail("48.9 ist ein neues Tief") }
        // Marke verfällt mit dem Zeitraum
        let day: Int64 = 24 * 3_600_000
        XCTAssertEqual(NearExtreme.reportedMark(lastLevel: 101, lastTriggeredAt: now - 29 * day, windowDays: 30, now: now), 101)
        XCTAssertNil(NearExtreme.reportedMark(lastLevel: 101, lastTriggeredAt: now - 30 * day, windowDays: 30, now: now))
        XCTAssertNil(NearExtreme.reportedMark(lastLevel: 101, lastTriggeredAt: 0, windowDays: 30, now: now))
        XCTAssertNil(NearExtreme.reportedMark(lastLevel: nil, lastTriggeredAt: now - 3_600_000, windowDays: 30, now: now))
    }

    func testVolumeSpikeOncePerCandle() {
        let candle = now - 2 * 3_600_000
        let a = alarm(.VOLUME_SPIKE, 3)
        XCTAssertTrue(AlarmEvaluator.shouldTriggerVolumeSpike(alarm: a, ratio: 3, candleOpenTime: candle, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTriggerVolumeSpike(alarm: a, ratio: 2.99, candleOpenTime: candle, now: now, cooldownMinutes: 0))
        let reported = alarm(.VOLUME_SPIKE, 3, referenceAt: candle)
        XCTAssertFalse(AlarmEvaluator.shouldTriggerVolumeSpike(alarm: reported, ratio: 10, candleOpenTime: candle, now: now, cooldownMinutes: 0))
        XCTAssertTrue(AlarmEvaluator.shouldTriggerVolumeSpike(alarm: reported, ratio: 10, candleOpenTime: candle + 3_600_000,
                                                              now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTriggerVolumeSpike(alarm: a, ratio: .nan, candleOpenTime: candle, now: now, cooldownMinutes: 0))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: a, price: 1000, previousPrice: 1, now: now, cooldownMinutes: 0))
    }
}
