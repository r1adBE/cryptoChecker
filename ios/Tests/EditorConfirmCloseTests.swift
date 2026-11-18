import XCTest
@testable import CryptoChecker

/// Blätter mit Eingaben (Alarm-Editor, Notiz, Gruppe) fragen genau einmal (Rückfrage «nicht gespeichert»)
/// und schliessen erst danach; «Abbrechen» lässt sie mit den Eingaben offen. Die Stellen in den Blättern prüft die
/// Regel `ios-wiring` in `.github/scripts/source_rules.py` (ohne UI-Test).
@MainActor
final class EditorConfirmCloseTests: XCTestCase {

    /// Nach einem Durchlass ohne Szene könnte eine zweite Prüfung erneut fragen (`mayAsk` bleibt true) — darum ruft der
    /// Editor nach seiner einen Frage den Speicher-Kern ohne zweite auf; der Schreibweg lehnt dann nicht ab.
    func testUnaskedPassStillMayAskSoTheEditorAsksOnlyOnce() async {
        let gate = WatchlistNotSavedGate()
        let passed = await gate.confirm(notSaved: true, canAsk: false, awaitAsker: { false })
        XCTAssertTrue(passed)
        XCTAssertTrue(gate.mayAsk(notSaved: true), "zweite Prüfung würde wieder fragen")
        let refusedInAction = gate.runConsented { gate.needsConfirmation(notSaved: true, canAsk: true) }
        XCTAssertFalse(refusedInAction, "Speicher-Kern darf in dieser Aktion ohne Frage schreiben")
        // Ausserhalb der Aktion keine Zustimmung
        XCTAssertTrue(gate.needsConfirmation(notSaved: true, canAsk: true))
        XCTAssertFalse(gate.pending)
    }
}
