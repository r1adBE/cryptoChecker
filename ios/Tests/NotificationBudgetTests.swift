import XCTest
@testable import CryptoChecker

/// Obergrenze der Kurs-Mitteilungen (`Parity/notification_budget.json`, gleich in `NotificationBudgetParityTest.kt`):
/// 50 Paare ergeben höchstens 20 Einzelmeldungen und eine Sammelmeldung; dazu das Lesen der zugestellten Mitteilungen.
final class NotificationBudgetTests: XCTestCase {
    private typealias S = TestSupport

    private func ids(_ value: Any?) -> [Int64] {
        (value as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } }
    }

    func testMaxPrice() throws {
        XCTAssertEqual(NotificationBudget.maxPrice, Int(S.number(try S.fixture("notification_budget")["maxPrice"]) ?? 0))
    }

    func testVerifyWait() throws {
        let verify = try XCTUnwrap(try S.fixture("notification_budget")["verify"] as? [String: Any])
        XCTAssertEqual(Int(S.number(verify["attempts"]) ?? 0), NotificationBudget.verifyAttempts)
        XCTAssertEqual(Int64(S.number(verify["stepMillis"]) ?? 0), NotificationBudget.verifyStepMillis)
        XCTAssertEqual(Int64(S.number(verify["toleranceMillis"]) ?? 0), NotificationBudget.postTimeToleranceMillis)
    }

    func testConfirmedPost() throws {
        let cases = S.list(try S.fixture("notification_budget")["confirmedPost"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let postTime = S.number(c["postTime"]).map { Int64($0) }
            let times: [Int64]? = c["queryable"] as? Bool == true ? (postTime.map { [$0] } ?? []) : nil
            XCTAssertEqual(NotificationBudget.confirmedPost(postTimes: times, postedAt: S.millis(c["postedAt"])),
                           c["confirmed"] as? Bool, c["name"] as? String ?? "")
        }
    }

    func testPriceSlots() throws {
        for c in S.list(try S.fixture("notification_budget")["priceSlots"]) {
            XCTAssertEqual(NotificationBudget.priceSlots(otherActive: Int(S.number(c["other"]) ?? 0)),
                           Int(S.number(c["slots"]) ?? -1), "\(c)")
        }
    }

    func testPlanPrices() throws {
        for c in S.list(try S.fixture("notification_budget")["planPrices"]) {
            let name = c["name"] as? String ?? ""
            let plan = NotificationBudget.planPrices(shown: ids(c["shown"]), overflowBefore: ids(c["overflowBefore"]),
                                                     posts: ids(c["posts"]), slots: Int(S.number(c["slots"]) ?? 0))
            XCTAssertEqual(plan.individual, ids(c["individual"]), name)
            XCTAssertEqual(plan.overflow, ids(c["overflow"]), name)
            XCTAssertEqual(plan.demoted, ids(c["demoted"]), name)
            XCTAssertLessThanOrEqual(plan.individual.count, NotificationBudget.maxPrice, name)
        }
    }

    func testOverflowBodyShowsFiveLinesThenEllipsis() {
        XCTAssertEqual(NotificationBudget.overflowBody(["a", "b"]), "a\nb")
        XCTAssertEqual(NotificationBudget.overflowBody(["1", "2", "3", "4", "5", "6"]), "1\n2\n3\n4\n5\n…")
    }

    func testOverflowBodyParity() throws {
        for c in S.list(try S.fixture("notification_budget")["overflowBody"]) {
            let lines = (c["lines"] as? [Any] ?? []).compactMap { $0 as? String }
            XCTAssertEqual(NotificationBudget.overflowBody(lines), c["body"] as? String, c["name"] as? String ?? "")
        }
    }

    /// Zugestellte Mitteilungen: einzelne Kurse (neueste zuerst), Sammelmeldung mit Paaren und Zeilen, Rest = andere.
    func testDeliveredStateReadsPricesOverflowAndOthers() {
        typealias E = PriceDeliveredState.Entry
        let state = PriceDeliveredState(entries: [
            E(identifier: "price-1", date: Date(timeIntervalSince1970: 10), title: "BTC", userInfo: [:]),
            E(identifier: "price-2", date: Date(timeIntervalSince1970: 20), title: "ETH", userInfo: [:]),
            E(identifier: Notifier.priceOverflowId, date: Date(timeIntervalSince1970: 5), title: "+2",
              userInfo: [Notifier.userInfoOverflowIds: [NSNumber(value: 7), NSNumber(value: 8)],
                         Notifier.userInfoOverflowLines: ["SOL", "ADA"]]),
            E(identifier: "alarm-3", date: Date(timeIntervalSince1970: 30), title: "Alarm", userInfo: [:]),
        ])
        XCTAssertEqual(state.prices, [2, 1])
        XCTAssertEqual(state.titles[1], "BTC")
        XCTAssertTrue(state.overflowShown)
        XCTAssertEqual(state.overflowIds, [7, 8])
        XCTAssertEqual(state.overflowLines[8], "ADA")
        XCTAssertEqual(state.other, 1)
    }

    /// Ablauf über zwei Durchläufe mit 50 Paaren: nie mehr als 20 einzeln plus eine Sammelmeldung.
    func testFiftyPairsStayAtTwentyPlusOne() {
        let posts = Array(Int64(1)...50)
        let first = NotificationBudget.planPrices(shown: [], overflowBefore: [], posts: posts,
                                                  slots: NotificationBudget.priceSlots(otherActive: 0))
        XCTAssertEqual(first.individual.count, 20)
        XCTAssertEqual(first.overflow.count, 30)
        let second = NotificationBudget.planPrices(shown: first.individual.reversed(), overflowBefore: first.overflow,
                                                   posts: posts, slots: NotificationBudget.priceSlots(otherActive: 1))
        XCTAssertEqual(Set(second.individual), Set(first.individual))
        XCTAssertEqual(second.overflow.count, 30)
        XCTAssertTrue(second.demoted.isEmpty)
    }
}
