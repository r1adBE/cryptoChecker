import XCTest
@testable import CryptoChecker

/// Seit Build 104 (F-08): Frage vor der ersten Änderung je Lauf — gleiche Fälle wie `WatchlistNotSavedGateTest.kt`.
/// Seit Build 105: einmal je Aktion vorher (`confirm`), Sicherung im Schreibweg, Abbruch des Wartenden räumt die Frage.
/// Seit Build 106: ohne Szene kurz auf sie warten; ein Durchlass ohne Szene wird im Schreibweg nicht abgelehnt.
/// Review F-27: dieser Durchlass gilt nur für die eigene Aktion (`runConsented`), nicht global.
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
            let asked: Bool
            if let answer = step["answer"] as? Bool {
                try await waitUntil { gate.pending }
                asked = gate.pending
                gate.answer(proceed: answer)
            } else {
                // Seit Build 106 (TST4-01): echte Prüfung statt Fixture gegen Fixture — ohne Antwort erscheint keine Frage
                // (kurz warten, bis `confirm` gelaufen ist); eine unerwartete Frage abbrechen statt hängen
                try await Task.sleep(nanoseconds: 100_000_000)
                asked = gate.pending
                if asked { gate.answer(proceed: false) }
            }
            let written = await value(of: write)
            XCTAssertEqual(asked, step["asked"] as? Bool, "Schritt \(i)")
            XCTAssertEqual(written, step["written"] as? Bool, "Schritt \(i)")
            XCTAssertFalse(gate.pending, "Schritt \(i)")
        }
    }

    func testWithoutVisibleUiNeverAsks() async {
        let gate = WatchlistNotSavedGate()
        let written = await value(of: Task { await gate.confirm(notSaved: true, canAsk: false) })
        XCTAssertEqual(written, true)
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

    /// Seit Build 106 (Review TST4-02): Ergebnis von `task` abwarten, höchstens `seconds` — sonst abbrechen und XCTFail
    /// (nil) statt den CI-Lauf bis zum Job-Timeout hängen zu lassen. Wie Android `withTimeout(5_000)`.
    private func value<T: Sendable>(of task: Task<T, Never>, within seconds: Int = 5,
                                    file: StaticString = #filePath, line: UInt = #line) async -> T? {
        let box = TaskResultBox<T>()
        let reader = Task { @MainActor in box.result = await task.value }
        for _ in 0..<(seconds * 100) {
            if let result = box.result { return result }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        if let result = box.result { return result }
        task.cancel()
        reader.cancel()
        XCTFail("Kein Ergebnis nach \(seconds) s (Rückfrage hängt?)", file: file, line: line)
        return nil
    }

    /// Eine Nutzeraktion wie im Bildschirm: einmal `confirm` vorher, danach je Schreibweg nur die Sicherung von AppData
    /// (`needsConfirmation`, fragt nie), Folgen erst nach allen Schreibwegen. Rückgabe: geschriebene Wege, Folgen gelaufen.
    /// Nach bestätigtem `confirm` laufen die Schreibwege mit Zustimmung der Aktion (`runConsented`, wie `run`).
    private func action(_ gate: WatchlistNotSavedGate, writes: Int, notSaved: Bool, canAsk: Bool,
                        skipConfirm: Bool, screenAppears: Bool = false, canAskAtWrite: Bool? = nil) async -> (Int, Bool) {
        if !skipConfirm, !(await gate.confirm(notSaved: notSaved, canAsk: canAsk, awaitAsker: { screenAppears })) {
            return (0, false)
        }
        let atWrite = canAskAtWrite ?? canAsk
        let write = { (0..<writes).filter { _ in !gate.needsConfirmation(notSaved: notSaved, canAsk: atWrite) }.count }
        let written = skipConfirm ? write() : gate.runConsented(write)
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
                let confirmed = await value(of: first)
                XCTAssertEqual(confirmed, true, name)
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
            guard case let (written, effects)? = await value(of: run) else { continue }
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
        let result = await value(of: waiting)
        XCTAssertEqual(result, false)
        try await waitUntil { !gate.pending }
        XCTAssertEqual(gate.pending, expected["pendingAfterCancel"] as? Bool)
        XCTAssertEqual(!gate.needsConfirmation(notSaved: true, canAsk: true), expected["confirmedAfterCancel"] as? Bool)
        let next = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.pending }
        XCTAssertEqual(gate.pending, expected["nextActionAsks"] as? Bool)
        gate.answer(proceed: false)
        let nextResult = await value(of: next)
        XCTAssertEqual(nextResult, false)
    }

    /// Zwei gleichzeitige Aktionen: eine Frage; einer abgebrochen → die Frage bleibt für den anderen.
    func testConcurrentActionsShareOneQuestion() async throws {
        let gate = WatchlistNotSavedGate()
        let a = Task { await gate.confirm(notSaved: true, canAsk: true) }
        let b = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.waitingCount == 2 }
        a.cancel()
        let aResult = await value(of: a)
        XCTAssertEqual(aResult, false)
        try await waitUntil { gate.waitingCount == 1 }
        XCTAssertTrue(gate.pending)
        gate.answer(proceed: true)
        let bResult = await value(of: b)
        XCTAssertEqual(bResult, true)
        XCTAssertFalse(gate.needsConfirmation(notSaved: true, canAsk: true))
    }

    // MARK: Seit Build 106 (Review ARC4-R02, Variante A): ohne Szene erst warten, Durchlass wird nicht still abgelehnt

    func testSharedLateScreen() async throws {
        let late = try XCTUnwrap(try S.fixture("watchlist_not_saved_gate")["lateScreen"] as? [String: Any])
        XCTAssertEqual((late["waitMillis"] as? NSNumber)?.uint64Value, WatchlistNotSavedGate.askerWaitMillis)
        let cases = S.list(late["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let gate = WatchlistNotSavedGate()
            let writes = (c["writes"] as? NSNumber)?.intValue ?? 0
            let appears = c["screenAppears"] as? Bool ?? false
            let atWrite = c["canAskAtWrite"] as? Bool ?? true
            let run = Task {
                await self.action(gate, writes: writes, notSaved: true, canAsk: false, skipConfirm: false,
                                  screenAppears: appears, canAskAtWrite: atWrite)
            }
            var questions = 0
            if let answer = c["answer"] as? Bool {
                try await waitUntil { gate.pending }
                questions += 1
                gate.answer(proceed: answer)
            }
            guard case let (written, effects)? = await value(of: run) else { continue }
            XCTAssertEqual(questions, (c["questions"] as? NSNumber)?.intValue, name)
            XCTAssertEqual(written, (c["written"] as? NSNumber)?.intValue, name)
            XCTAssertEqual(effects, c["effects"] as? Bool, name)
            XCTAssertFalse(gate.pending, name)
        }
        // Nach dem Durchlass: die nächste Aktion mit Szene fragt wieder, nach «Abbrechen» gilt die Sicherung wieder
        let after = try XCTUnwrap(late["afterUnaskedPass"] as? [String: Any])
        let gate = WatchlistNotSavedGate()
        let passed = await value(of: Task { await gate.confirm(notSaved: true, canAsk: false, awaitAsker: { false }) })
        XCTAssertEqual(passed, true)
        let next = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.pending }
        XCTAssertEqual(gate.pending, after["nextActionAsks"] as? Bool)
        gate.answer(proceed: false)
        let nextResult = await value(of: next)
        XCTAssertEqual(nextResult, false)
        XCTAssertEqual(gate.needsConfirmation(notSaved: true, canAsk: true), after["refusesAgainAfterCancel"] as? Bool)
    }

    // MARK: Review F-27: Zustimmung je Aktion statt globalem Durchlass

    /// Aktion A über `run` (wie `withWatchlistConfirm`) ohne Szene durchgelassen, danach ist die Szene da: A und eine von A
    /// gestartete Aufgabe schreiben, eine andere Aktion ohne Bestätigung wird abgelehnt, eine andere mit Bestätigung fragt,
    /// ihr «Abbrechen» lehnt A nicht ab; ausserhalb von A gilt die Sicherung. Gleiche Fälle wie Android.
    func testSharedUnaskedPassCoversOnlyItsAction() async throws {
        let e = try XCTUnwrap(try S.fixture("watchlist_not_saved_gate")["unaskedPassScope"] as? [String: Any])
        let gate = WatchlistNotSavedGate()
        let probe = ConsentProbe()
        gate.run(blocked: false, notSaved: true,
                 confirm: { await gate.confirm(notSaved: true, canAsk: false, awaitAsker: { false }) },
                 { probe.start(gate) }, cancelled: nil)
        try await waitUntil { probe.own != nil && probe.child != nil }
        XCTAssertFalse(gate.pending)
        XCTAssertEqual(probe.own, e["ownWrites"] as? Bool)
        XCTAssertEqual(probe.child, e["ownChildWrites"] as? Bool)
        // Andere Aktion ohne Bestätigung (ausserhalb von A), während A noch läuft
        XCTAssertEqual(gate.needsConfirmation(notSaved: true, canAsk: true), e["otherUnconfirmedRefused"] as? Bool)
        // Andere Aktion mit Bestätigung fragt; «Abbrechen»
        let other = Task { await gate.confirm(notSaved: true, canAsk: true) }
        try await waitUntil { gate.pending }
        XCTAssertEqual(gate.pending, e["otherAsks"] as? Bool)
        gate.answer(proceed: false)
        let otherResult = await value(of: other)
        XCTAssertEqual(otherResult, false)
        probe.go = true
        try await waitUntil { probe.afterCancel != nil }
        XCTAssertEqual(probe.afterCancel, e["ownWritesAfterOtherCancel"] as? Bool)
        XCTAssertEqual(gate.needsConfirmation(notSaved: true, canAsk: true), e["refusedAfterOwnEnd"] as? Bool)
    }
}

/// Messpunkte der Aktion A in `testSharedUnaskedPassCoversOnlyItsAction`: true = Schreibweg nicht abgelehnt.
@MainActor
private final class ConsentProbe {
    var own: Bool?
    var child: Bool?
    var afterCancel: Bool?
    var go = false

    /// Aktion A (synchron, wie im Bildschirm): sofort prüfen, dann eine Aufgabe, die erbt, sofort und nach `go` prüft.
    func start(_ gate: WatchlistNotSavedGate) {
        own = !gate.needsConfirmation(notSaved: true, canAsk: true)
        Task { @MainActor in
            self.child = !gate.needsConfirmation(notSaved: true, canAsk: true)
            while !self.go { try? await Task.sleep(nanoseconds: 10_000_000) }
            self.afterCancel = !gate.needsConfirmation(notSaved: true, canAsk: true)
        }
    }
}

/// Ergebnis einer Aufgabe für `value(of:within:)` (seit Build 106); nur auf dem MainActor gelesen und geschrieben.
@MainActor
private final class TaskResultBox<T> {
    var result: T?
}
