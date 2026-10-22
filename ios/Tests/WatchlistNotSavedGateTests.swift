import XCTest
@testable import CryptoChecker

/// Seit Build 104 (F-08): Frage vor der ersten Änderung je Lauf — gleiche Fälle wie `WatchlistNotSavedGateTest.kt`.
@MainActor
final class WatchlistNotSavedGateTests: XCTestCase {
    private typealias S = TestSupport

    func testSharedShouldAsk() throws {
        let data = try S.fixture("watchlist_not_saved_gate")
        for c in S.list(data["shouldAsk"]) {
            XCTAssertEqual(WatchlistNotSavedGate.shouldAsk(notSaved: c["notSaved"] as? Bool ?? false,
                                                           userWrite: c["userWrite"] as? Bool ?? false,
                                                           confirmed: c["confirmed"] as? Bool ?? false,
                                                           canAsk: c["canAsk"] as? Bool ?? false),
                           c["ask"] as? Bool, "\(c)")
        }
    }

    func testSharedConfirmedAfter() throws {
        let data = try S.fixture("watchlist_not_saved_gate")
        for c in S.list(data["confirmedAfter"]) {
            XCTAssertEqual(WatchlistNotSavedGate.confirmedAfter(confirmed: c["confirmed"] as? Bool ?? false,
                                                                proceed: c["proceed"] as? Bool ?? false),
                           c["result"] as? Bool, "\(c)")
        }
    }

    /// Ein Lauf mit Oberfläche: abgefangene Änderung läuft nach «Trotzdem» erneut (`retry`), danach keine Frage mehr.
    func testSharedSequence() throws {
        let data = try S.fixture("watchlist_not_saved_gate")
        let steps = S.list((data["sequence"] as? [String: Any])?["steps"])
        XCTAssertFalse(steps.isEmpty)
        let gate = WatchlistNotSavedGate()
        for (i, step) in steps.enumerated() {
            var written = false
            let refused = gate.intercept(notSaved: true, canAsk: true, retry: { written = true })
            if !refused { written = true }
            XCTAssertEqual(refused && gate.pending, step["asked"] as? Bool, "Schritt \(i)")
            if let answer = step["answer"] as? Bool { gate.answer(proceed: answer) }
            XCTAssertEqual(written, step["written"] as? Bool, "Schritt \(i)")
            XCTAssertFalse(gate.pending, "Schritt \(i)")
        }
    }

    func testWithoutVisibleUiNeverAsks() {
        let gate = WatchlistNotSavedGate()
        XCTAssertFalse(gate.intercept(notSaved: true, canAsk: false, retry: nil))
        XCTAssertFalse(gate.pending)
    }
}
