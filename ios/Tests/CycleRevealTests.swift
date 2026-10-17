import XCTest
@testable import CryptoChecker

/// Schrittweises Erscheinen im Markt-Tab, je Register eine Kette — wie `MarketRevealTest.kt`.
final class CycleRevealTests: XCTestCase {

    private let start: Int64 = 10_000
    private let n = CycleReveal.count
    private let all = Set(CycleRevealSlot.allCases)
    private let stagger = CycleReveal.staggerMillis
    private let wait = CycleReveal.waitMillis

    /// Zeitpunkt, zu dem jeder Teil erscheint (bis alle stehen).
    private func revealTimes(_ readyAt: [Int64?]) -> [CycleRevealSlot: Int64] {
        var times: [CycleRevealSlot: Int64] = [:]
        var now = start
        while true {
            let plan = CycleReveal.plan(readyAt: readyAt, start: start, now: now)
            for slot in plan.revealed where times[slot] == nil { times[slot] = now }
            // Einmal sichtbar, bleibt sichtbar
            XCTAssertTrue(Set(times.keys).isSubset(of: plan.revealed))
            guard let next = plan.nextAt else { return times }
            now = next
        }
    }

    /// Überschriften-Teile (zeigen nichts) sind immer sofort bereit, wie im ViewModel.
    private func ready(_ value: Int64?) -> [Int64?] {
        (0..<n).map { i in
            i == CycleRevealSlot.headerContext.rawValue || i == CycleRevealSlot.headerData.rawValue ? start : value
        }
    }

    func testAllCachedAtStartAllAtOnce() {
        let ready = [Int64?](repeating: start, count: n)
        XCTAssertEqual(CycleReveal.plan(readyAt: ready, start: start, now: start), .init(revealed: all, nextAt: nil))
        XCTAssertEqual(CycleReveal.instantSlots(readyAt: ready, start: start), all)
    }

    func testNothingReadyEachRegisterWaitsForItsOwnFirst() {
        let ready = [Int64?](repeating: nil, count: n)
        XCTAssertEqual(CycleReveal.instantSlots(readyAt: ready, start: start), [])
        XCTAssertEqual(CycleReveal.plan(readyAt: ready, start: start, now: start), .init(revealed: [], nextAt: start + wait))
        XCTAssertEqual(CycleReveal.plan(readyAt: ready, start: start, now: start + wait),
                       .init(revealed: [.pulse, .headerContext, .headerData], nextAt: start + 2 * wait))
    }

    func testStaggeredWithinEachRegister() {
        let times = revealTimes([Int64?](repeating: start + 5, count: n))
        XCTAssertEqual(Set(times.keys), all)
        for section in MarketSection.allCases {
            let slots = CycleRevealSlot.allCases.filter { MarketSections.of($0) == section }
            XCTAssertEqual(times[slots[0]], start + 5)
            for i in 1..<slots.count {
                XCTAssertEqual((times[slots[i]] ?? 0) - (times[slots[i - 1]] ?? 0), stagger)
            }
        }
    }

    func testCardWaitsForPreviousInItsRegister() {
        var ready = ready(start + 300)
        ready[CycleRevealSlot.coin.rawValue] = start + 1
        let times = revealTimes(ready)
        XCTAssertEqual(times[.marketTotals], start + 300)
        XCTAssertEqual(times[.coin], start + 300 + 2 * stagger)
    }

    func testSlowContextSourceDoesNotDelayData() {
        var ready = ready(start + 10)
        ready[CycleRevealSlot.phase.rawValue] = nil
        ready[CycleRevealSlot.halving.rawValue] = nil
        let times = revealTimes(ready)
        XCTAssertEqual(times[.marketTotals], start + stagger)
        XCTAssertEqual(times[.gas], start + 2 * stagger)
        XCTAssertEqual(times[.coin], start + 3 * stagger)
        XCTAssertEqual(times[.phase], (times[.fearGreed] ?? 0) + wait)
        XCTAssertEqual(times[.dominance], (times[.phase] ?? 0) + stagger)
        XCTAssertEqual(times[.halving], (times[.dominance] ?? 0) + wait)
        let plan = CycleReveal.plan(readyAt: ready, start: start, now: start + 500)
        XCTAssertTrue(plan.revealed.isSuperset(of: [.marketTotals, .gas, .coin]))
        XCTAssertFalse(plan.revealed.contains(.phase))
        XCTAssertFalse(MarketSections.loading(.data, revealed: plan.revealed))
        XCTAssertTrue(MarketSections.loading(.context, revealed: plan.revealed))
    }

    func testSlowNowDoesNotDelayContext() {
        var ready = ready(start + 10)
        ready[CycleRevealSlot.pulse.rawValue] = nil
        let times = revealTimes(ready)
        XCTAssertEqual(times[.fearGreed], start + stagger)
        XCTAssertEqual(times[.pulse], start + wait)
    }

    func testLateDataAfterDeadlineCountsAsTimeout() {
        var ready = [Int64?](repeating: start, count: n)
        ready[CycleRevealSlot.fearGreed.rawValue] = start + 5_000
        let plan = CycleReveal.plan(readyAt: ready, start: start, now: start + wait)
        XCTAssertEqual(plan.revealed, all.subtracting([.phase, .dominance, .halving]))
        XCTAssertEqual(plan.nextAt, start + wait + stagger)
        XCTAssertEqual(CycleReveal.instantSlots(readyAt: ready, start: start),
                       all.subtracting([.fearGreed, .phase, .dominance, .halving]))
    }

    func testFearGreedOnNowWaitsForTheCardsAboveIt() {
        var ready = ready(start + 10)
        ready[CycleRevealSlot.unusual.rawValue] = nil
        let times = revealTimes(ready)
        let pulse = times[.pulse] ?? 0
        XCTAssertEqual(times[.unusual], pulse + wait)
        XCTAssertEqual(times[.fearGreed], start + stagger)
        let early = CycleReveal.plan(readyAt: ready, start: start, now: start + 100).revealed
        XCTAssertTrue(early.contains(.fearGreed))
        XCTAssertFalse(CycleReveal.isShownOnNow(.fearGreed, revealed: early))
        XCTAssertTrue(CycleReveal.isShownOnNow(.pulse, revealed: early))
        let later = CycleReveal.plan(readyAt: ready, start: start, now: pulse + wait).revealed
        XCTAssertTrue(CycleReveal.isShownOnNow(.fearGreed, revealed: later))
    }

    func testCachedPrefixInstantRestFollows() {
        var ready = [Int64?](repeating: start, count: n)
        ready[CycleRevealSlot.halving.rawValue] = start + 800
        XCTAssertEqual(CycleReveal.instantSlots(readyAt: ready, start: start), all.subtracting([.halving]))
        var pending = ready
        pending[CycleRevealSlot.halving.rawValue] = nil
        let atStart = CycleReveal.plan(readyAt: pending, start: start, now: start)
        XCTAssertEqual(atStart.revealed, all.subtracting([.halving]))
        XCTAssertEqual(atStart.nextAt, start + wait)
        let times = revealTimes(ready)
        XCTAssertEqual(times[.halving], start + 800)
        XCTAssertEqual(times[.coin], start)
    }

    func testNeverMoreThanOneStepAheadOfNow() {
        let later = [Int64?](repeating: start + 1, count: n)
        let plan = CycleReveal.plan(readyAt: later, start: start, now: start + 1)
        XCTAssertEqual(plan.revealed, [.pulse, .headerContext, .headerData])
        XCTAssertEqual(plan.nextAt, start + 1 + stagger)
        XCTAssertNil(CycleReveal.plan(readyAt: later, start: start, now: start + 1 + 4 * stagger).nextAt)
    }
}
