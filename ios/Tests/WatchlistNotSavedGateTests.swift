import XCTest
@testable import CryptoChecker

/// Seit Build 104 (F-08): Frage vor der ersten Änderung je Lauf — gleiche Fälle wie `WatchlistNotSavedGateTest.kt`.
/// Seit Build 105: einmal je Aktion vorher (`confirm`), Sicherung im Schreibweg, Abbruch des Wartenden räumt die Frage.
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

    /// Ein Lauf mit Oberfläche: gefragt wird bis zum ersten «Trotzdem», danach nicht mehr (seit Build 105 per `confirm`).
    func testSharedSequence() async throws {
        let data = try S.fixture("watchlist_not_saved_gate")
        let steps = S.list((data["sequence"] as? [String: Any])?["steps"])
        XCTAssertFalse(steps.isEmpty)
        let gate = WatchlistNotSavedGate()
        for (i, step) in steps.enumerated() {
            let write = Task { await gate.confirm(notSaved: true, canAsk: true) }
            let answer = step["answer"] as? Bool
            if let answer {
                try await waitUntil { gate.pending }
                gate.answer(proceed: answer)
            }
            let written = await write.value
            XCTAssertEqual(answer != nil, step["asked"] as? Bool, "Schritt \(i)")
            XCTAssertEqual(written, step["written"] as? Bool, "Schritt \(i)")
            XCTAssertFalse(gate.pending, "Schritt \(i)")
        }
    }

    func testWithoutVisibleUiNeverAsks() async {
        let gate = WatchlistNotSavedGate()
        let written = await gate.confirm(notSaved: true, canAsk: false)
        XCTAssertTrue(written)
        XCTAssertFalse(gate.pending)
        XCTAssertFalse(gate.needsConfirmation(notSaved: true, canAsk: false))
    }

    // MARK: Seit Build 105: einmal je Aktion vorher, Sicherung im Schreibweg

    /// Bedingung auf dem MainActor abwarten (höchstens 5 s).
    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<500 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Bedingung nicht erreicht")
    }

    /// Eine Nutzeraktion wie im Bildschirm: einmal `confirm` vorher, danach je Schreibweg nur die Sicherung von AppData
    /// (`needsConfirmation`, fragt nie), Folgen erst nach allen Schreibwegen. Rückgabe: geschriebene Wege, Folgen gelaufen.
    private func action(_ gate: WatchlistNotSavedGate, writes: Int, notSaved: Bool, canAsk: Bool,
                        skipConfirm: Bool) async -> (Int, Bool) {
        if !skipConfirm, !(await gate.confirm(notSaved: notSaved, canAsk: canAsk)) { return (0, false) }
        var written = 0
        for _ in 0..<writes where !gate.needsConfirmation(notSaved: notSaved, canAsk: canAsk) { written += 1 }
        return (written, written == writes)
    }

    func testSharedActionsAskOnceBeforeTheAction() async throws {
        let data = try S.fixture("watchlist_not_saved_gate")
        let cases = S.list((data["actions"] as? [String: Any])?["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let gate = WatchlistNotSavedGate()
            if c["confirmed"] as? Bool == true {
                let first = Task { await gate.confirm(notSaved: true, canAsk: true) }
                try await waitUntil { gate.pending }
                gate.answer(proceed: true)
                let confirmed = await first.value
                XCTAssertTrue(confirmed, name)
            }
            let writes = (c["writes"] as? NSNumber)?.intValue ?? 0
            let notSaved = c["notSaved"] as? Bool ?? true, canAsk = c["canAsk"] as? Bool ?? true
            let skip = c["skipConfirm"] as? Bool ?? false
            let run = Task { await self.action(gate, writes: writes, notSaved: notSaved, canAsk: canAsk, skipConfirm: skip) }
            var questions = 0
            if let answer = c["answer"] as? Bool {
                try await waitUntil { gate.pending }
                questions += 1
                gate.answer(proceed: answer)
            }
            let (written, effects) = await run.value
            XCTAssertEqual(questions, (c["questions"] as? NSNumber)?.intValue, name)
            XCTAssertEqual(written, (c["written"] as? NSNumber)?.intValue, name)
            XCTAssertEqual(effects, c["effects"] as? Bool, name)
            XCTAssertFalse(gate.pending, name)
        }
    }

    /// Wartende Aktion abgebrochen (Bildschirm weg) → keine offene Frage, nichts bestätigt; die nächste fragt neu.
    func testSharedCancelledWaiterClearsTheQuestion() async throws {
        let expected = try XCTUnwrap(try S.fixture("watchlist_not_saved_gate")["cancelledWaiter"] as? [String: Any])
        let gate = WatchlistNotSavedGate()
        let waiting = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.pending }
        waiting.cancel()
        let result = await waiting.value
        XCTAssertFalse(result)
        try await waitUntil { !gate.pending }
        XCTAssertEqual(gate.pending, expected["pendingAfterCancel"] as? Bool)
        XCTAssertEqual(!gate.needsConfirmation(notSaved: true, canAsk: true), expected["confirmedAfterCancel"] as? Bool)
        let next = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.pending }
        XCTAssertEqual(gate.pending, expected["nextActionAsks"] as? Bool)
        gate.answer(proceed: false)
        let nextResult = await next.value
        XCTAssertFalse(nextResult)
    }

    /// Zwei gleichzeitige Aktionen: eine Frage; einer abgebrochen → die Frage bleibt für den anderen.
    func testConcurrentActionsShareOneQuestion() async throws {
        let gate = WatchlistNotSavedGate()
        let a = Task { await gate.confirm(notSaved: true, canAsk: true) }
        let b = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.waitingCount == 2 }
        a.cancel()
        let aResult = await a.value
        XCTAssertFalse(aResult)
        try await waitUntil { gate.waitingCount == 1 }
        XCTAssertTrue(gate.pending)
        gate.answer(proceed: true)
        let bResult = await b.value
        XCTAssertTrue(bResult)
        XCTAssertFalse(gate.needsConfirmation(notSaved: true, canAsk: true))
    }
}
