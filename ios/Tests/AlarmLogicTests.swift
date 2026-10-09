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
        let expired = alarm(.MOVE_PERCENT_WINDOW, 5, referencePrice: 100, windowHours: 1, referenceAt: now - 7_200_000)
        XCTAssertTrue(AlarmEvaluator.needsWindowReset(alarm: expired, now: now))
        XCTAssertFalse(AlarmEvaluator.shouldTrigger(alarm: expired, price: 120, previousPrice: 100, now: now, cooldownMinutes: 0))
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
