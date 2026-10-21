import XCTest
@testable import CryptoChecker

/// Fällige Paare der Aktivitäts-Auswertung (wie `ActivityDueOrderTest.kt`): am längsten nicht geprüfte zuerst, damit das
/// Zeitbudget niemanden aushungert.
final class ActivityDueOrderTests: XCTestCase {
    func testNeverCheckedFirstThenOldestStable() {
        let computed: [Int64: Int64?] = [1: 500, 2: nil, 3: 100, 4: 100, 5: nil]
        let order = ActivityDueOrder.order([Int64(1), 2, 3, 4, 5], id: { $0 }, computedAt: { computed[$0] ?? nil },
                                           attemptedAt: [:])
        XCTAssertEqual(order, [2, 5, 3, 4, 1])
    }

    func testAttemptWithoutResultCountsAsCheck() {
        let order = ActivityDueOrder.order([Int64(1), 2], id: { $0 }, computedAt: { _ in nil }, attemptedAt: [1: 900])
        XCTAssertEqual(order, [2, 1])
    }

    /// 10 Paare, das Budget reicht je Lauf für 5; nach jedem Lauf sind wieder alle fällig.
    func testTwoBudgetLimitedRunsCheckEveryPair() {
        let ids = Array(Int64(1)...10)
        var computedAt: [Int64: Int64] = [:]
        var checked = Set<Int64>()
        for now in [Int64(1_000), 2_000] {
            let due = ActivityDueOrder.order(ids, id: { $0 }, computedAt: { computedAt[$0] }, attemptedAt: [:])
            for id in due.prefix(5) {
                computedAt[id] = now
                checked.insert(id)
            }
        }
        XCTAssertEqual(checked, Set(ids))
    }
}
