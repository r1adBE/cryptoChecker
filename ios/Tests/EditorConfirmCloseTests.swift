import XCTest
@testable import CryptoChecker

/// Seit Build 107 (IOS5-R01/R02): Blätter mit Eingaben (Alarm-Editor, Notiz, Gruppe) fragen genau einmal (Rückfrage F-08)
/// und schliessen erst danach; «Abbrechen» lässt sie mit den Eingaben offen. Quelltext-Prüfung, da ohne UI-Test.
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
        // Review F-27: ausserhalb der Aktion keine Zustimmung
        XCTAssertTrue(gate.needsConfirmation(notSaved: true, canAsk: true))
        XCTAssertFalse(gate.pending)
    }

    func testAlarmEditorAsksOncePerSave() throws {
        let save = try source("Features/Alarms/AlarmsScreen+Save.swift")
        for (outer, core) in [("func saveFromEditor(", "saveDraftNow("), ("func templateFromEditor(", "createFromTemplateNow(")] {
            let code = body(save, outer)
            XCTAssertEqual(code.components(separatedBy: "withWatchlistConfirm(").count, 2, "\(outer) fragt genau einmal")
            XCTAssertTrue(code.contains(core), "\(outer) ruft \(core)")
        }
        // Die Kerne fragen nicht selbst (nur «Rückgängig» des Schnell-Alarms ist eine eigene Aktion)
        XCTAssertFalse(body(save, "private func saveDraftNow(").contains("withWatchlistConfirm"))
        let template = body(save, "private func createFromTemplateNow(").components(separatedBy: "WatchlistBannerAction.undo")[0]
        XCTAssertFalse(template.contains("withWatchlistConfirm"))
        XCTAssertFalse(save.contains("confirmWatchlistWrite"))
        // Kein zweiter Weg mehr: Editor schliesst über den Rückruf des Aufrufers
        let sheet = try source("Features/Alarms/AlarmEditorSheet.swift")
        let templates = try source("Features/Alarms/AlarmEditorSheet+Templates.swift")
        XCTAssertFalse(sheet.contains("confirmClose") || templates.contains("confirmClose"))
        XCTAssertTrue(sheet.contains("onSave(saved, { dismiss() }, { createGuard.reset() })"))
        XCTAssertTrue(templates.contains("onTemplate(template, { dismiss() }, { createGuard.reset() })"))
        let screen = try source("Features/Alarms/AlarmsScreen.swift")
        XCTAssertTrue(screen.contains("onSave: saveFromEditor") && screen.contains("onTemplate: templateFromEditor"))
    }

    func testNoteAndGroupSheetsCloseOnlyAfterConfirm() throws {
        let closesFirst = try NSRegularExpression(pattern: #"on(Save|Select)\([^\n]*\)\s*[\n;]\s*dismiss\(\)"#)
        for (file, call) in [("Features/Watchlist/WatchNoteSheet.swift", "onSave(note, { dismiss() }, { saving = false })"),
                             ("Features/Watchlist/WatchGroupSheet.swift", "onSelect(group, { dismiss() }, { saving = false })")] {
            let text = try source(file)
            let range = NSRange(location: 0, length: (text as NSString).length)
            XCTAssertNil(closesFirst.firstMatch(in: text, range: range), "\(file): schliesst vor der Rückfrage")
            XCTAssertTrue(text.contains(call), "\(file): schliesst nicht über den Rückruf")
        }
        let actions = try source("Features/Watchlist/WatchActionsSheet.swift")
        XCTAssertTrue(actions.contains("onSave: saveNote") && actions.contains("onSelect: saveGroup"))
        let group = try source("Features/Watchlist/WatchSheetGroup.swift")
        for name in ["func saveNote(", "func saveGroup("] {
            let code = body(group, name)
            guard let ask = code.range(of: "withWatchlistConfirm({"), let close = code.range(of: "close()") else {
                return XCTFail("\(name): Frage bzw. close fehlt")
            }
            XCTAssertLessThan(ask.lowerBound, close.lowerBound, "\(name): close erst nach der Frage")
            XCTAssertTrue(code.contains("cancelled: cancelled"), "\(name): «Abbrechen» lässt das Blatt offen")
        }
    }

    // MARK: Quelle

    private func source(_ path: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/\(path)")
        return try String(contentsOf: file, encoding: .utf8)
    }

    /// Rumpf ab `signature` bis zur nächsten Funktion bzw. zum Kommentar davor.
    private func body(_ source: String, _ signature: String) -> String {
        guard let start = source.range(of: signature) else { XCTFail("\(signature) fehlt"); return "" }
        let rest = source[start.upperBound...]
        let ends = ["\n    func ", "\n    private func ", "\n    ///"].compactMap { rest.range(of: $0)?.lowerBound }
        return String(rest[..<(ends.min() ?? rest.endIndex)])
    }
}
