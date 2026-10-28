import XCTest
@testable import CryptoChecker

/// Verhalten statt Quelltext-Muster. Blatt-Speichern (Alarm-Editor, Notiz, Gruppe, «Gruppe bearbeiten»)
/// über `WatchlistNotSavedGate.run` — den Kern von `AppData.withWatchlistConfirm` — mit eigenem Tor in Zustand «offen»:
/// «Abbrechen» → nur `cancelled` (Blatt bleibt, Merker wieder frei), «Trotzdem» → genau einmal schreiben und `close`;
/// ein zweiter Tipp vor der Antwort zählt nicht (Merker `saving` der Blätter bzw. `AlarmCreateGuard` im Editor).
@MainActor
final class EditorConfirmBehaviorTests: XCTestCase {

    /// Blatt mit Merker und Aufrufer wie `WatchSheetGroup.saveNote` bzw. `AlarmsScreen.saveFromEditor`.
    @MainActor
    private final class Sheet {
        let gate: WatchlistNotSavedGate
        var blocked = false
        var guardState = AlarmCreateGuard()
        var writes = 0
        var closes = 0
        var cancels = 0

        init(gate: WatchlistNotSavedGate) { self.gate = gate }

        /// Aufrufer (`ConfirmedFinish`): einmal fragen, schreiben, dann `close`; «Abbrechen» → `cancelled`.
        func finish(close: @escaping @MainActor () -> Void, cancelled: @escaping @MainActor () -> Void) {
            let gate = gate
            gate.run(blocked: blocked, notSaved: true, confirm: { await gate.confirm(notSaved: true, canAsk: true) }, {
                self.writes += 1
                close()
            }, cancelled: cancelled)
        }

        /// Tipp auf «Speichern» bzw. eine Gruppe: Merker setzen, nach «Abbrechen» wieder frei.
        func tap(creating: Bool = true) {
            guard guardState.begin(creating: creating) else { return }
            finish(close: { self.closes += 1 }, cancelled: {
                self.cancels += 1
                self.guardState.reset()
            })
        }
    }

    /// Lässt die Aufgaben auf dem Hauptfaden laufen, bis `condition` gilt (höchstens 500 Runden).
    private func settle(_ condition: () -> Bool) async {
        var rounds = 0
        while !condition() && rounds < 500 {
            await Task.yield()
            rounds += 1
        }
    }

    func testCancelKeepsTheSheetOpenAndAsksAgainOnTheNextTap() async {
        let gate = WatchlistNotSavedGate()
        let sheet = Sheet(gate: gate)
        sheet.tap()
        await settle { gate.pending }
        XCTAssertTrue(gate.pending)
        // Zweiter Tipp, solange die Frage offen ist: keine zweite Aktion
        sheet.tap()
        await settle { gate.waitingCount > 1 }
        XCTAssertEqual(gate.waitingCount, 1)

        gate.answer(proceed: false)
        await settle { sheet.cancels > 0 }
        XCTAssertEqual([sheet.writes, sheet.closes, sheet.cancels], [0, 0, 1])

        // Merker frei: der nächste Tipp fragt erneut
        sheet.tap()
        await settle { gate.pending }
        XCTAssertTrue(gate.pending)
        gate.answer(proceed: true)
        await settle { sheet.closes > 0 }
        XCTAssertEqual([sheet.writes, sheet.closes, sheet.cancels], [1, 1, 1])
    }

    func testDoubleTapWhileEditingWritesOnce() async {
        let gate = WatchlistNotSavedGate()
        let sheet = Sheet(gate: gate)
        // Bestehender Alarm: auch beim Bearbeiten nur ein Speichern
        sheet.tap(creating: false)
        sheet.tap(creating: false)
        await settle { gate.pending }
        gate.answer(proceed: true)
        await settle { sheet.closes > 0 }
        await settle { sheet.writes > 1 }
        XCTAssertEqual([sheet.writes, sheet.closes, sheet.cancels], [1, 1, 0])
    }

    func testLockedWatchlistRunsAtOnceAndCloses() {
        let gate = WatchlistNotSavedGate()
        let sheet = Sheet(gate: gate)
        sheet.blocked = true
        sheet.tap()
        // Synchron, ohne Frage: der Schreibweg lehnt selbst ab und zeigt den Hinweis, das Blatt schliesst
        XCTAssertEqual([sheet.writes, sheet.closes, sheet.cancels], [1, 1, 0])
        XCTAssertFalse(gate.pending)
    }

    func testAfterProceedTheNextSheetDoesNotAskAgain() async {
        let gate = WatchlistNotSavedGate()
        let first = Sheet(gate: gate)
        first.tap()
        await settle { gate.pending }
        gate.answer(proceed: true)
        await settle { first.closes > 0 }
        let second = Sheet(gate: gate)
        second.tap()
        XCTAssertEqual([second.writes, second.closes], [1, 1], "nach «Trotzdem» läuft die nächste Aktion sofort")
        XCTAssertFalse(gate.pending)
    }
}
