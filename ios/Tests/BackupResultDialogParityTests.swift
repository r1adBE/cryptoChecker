import XCTest
@testable import CryptoChecker

/// Ergebnis von Sichern/Wiederherstellen als Dialog oder Toast (`Parity/backup_result_dialog.json`) und Anzeigedauer
/// des Toasts. Gegenstück: `BackupResultDialogParityTest.kt`.
final class BackupResultDialogParityTests: XCTestCase {
    private typealias S = TestSupport

    func testEntscheidetWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("backup_result_dialog")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 35)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let note = (c["addressServerNote"] as? String).flatMap { BackupRestorePolicy.AddressServerNote(rawValue: $0) }
            let actual = BackupResultPresentation.asDialog(
                key: try XCTUnwrap(c["key"] as? String, name),
                text: try XCTUnwrap(c["text"] as? String, name),
                droppedPairs: Int(S.number(c["droppedPairs"]) ?? -1),
                droppedAlarms: Int(S.number(c["droppedAlarms"]) ?? -1),
                appLockKept: try XCTUnwrap(c["appLockKept"] as? Bool, name),
                addressServerNote: note,
                fontScale: S.number(c["fontScale"]) ?? 1
            )
            XCTAssertEqual(actual ? "dialog" : "toast", c["expected"] as? String, name)
        }
    }

    func testGrenzeUndSchluesselWieGemeinsameTestfaelle() throws {
        let fixture = try S.fixture("backup_result_dialog")
        XCTAssertEqual(Int(S.number(fixture["toastMaxChars"]) ?? 0), BackupResultPresentation.toastMaxChars)
        XCTAssertEqual(Set(fixture["longKeys"] as? [String] ?? []), BackupResultPresentation.longKeys)
        let ranges = (fixture["wideRanges"] as? [[String]] ?? []).map { $0.compactMap { UInt32($0, radix: 16) } }
        XCTAssertEqual(ranges, BackupResultPresentation.wideRanges.map { [$0.lowerBound, $0.upperBound] })
    }

    /// Breite: schmal 1, breit 2 (auch ausserhalb der BMP); Grenze sinkt mit grösserer Schrift, steigt nie über 80.
    func testBreiteUndGrenze() {
        XCTAssertEqual(BackupResultPresentation.displayWidth("abc"), 3)
        XCTAssertEqual(BackupResultPresentation.displayWidth("日本"), 4)
        XCTAssertEqual(BackupResultPresentation.displayWidth("\u{20000}"), 2)
        XCTAssertEqual(BackupResultPresentation.displayWidth("\u{FF71}"), 1)
        XCTAssertEqual(BackupResultPresentation.toastLimit(fontScale: 0.85), 80, accuracy: 0.0001)
        XCTAssertEqual(BackupResultPresentation.toastLimit(fontScale: 2), 40, accuracy: 0.0001)
    }

    /// Ergebnis-Dialog erst, wenn Rückfrage, Blatt, Dateiauswahl und voriges Ergebnis zu sind (statt fester Wartezeit).
    func testErgebnisErstWennNichtsAnderesOffen() {
        XCTAssertTrue(BackupResultPresentation.canPresent(confirmOpen: false, sheetOpen: false, pickerOpen: false,
                                                          resultOpen: false))
        XCTAssertFalse(BackupResultPresentation.canPresent(confirmOpen: true, sheetOpen: false, pickerOpen: false,
                                                           resultOpen: false))
        XCTAssertFalse(BackupResultPresentation.canPresent(confirmOpen: false, sheetOpen: true, pickerOpen: false,
                                                           resultOpen: false))
        XCTAssertFalse(BackupResultPresentation.canPresent(confirmOpen: false, sheetOpen: false, pickerOpen: true,
                                                           resultOpen: false))
        XCTAssertFalse(BackupResultPresentation.canPresent(confirmOpen: false, sheetOpen: false, pickerOpen: false,
                                                           resultOpen: true))
    }

    /// 0,06 s je Zeichen, mindestens 2,5 s, höchstens 8 s.
    func testToastDauerNachTextlaenge() {
        XCTAssertEqual(ToastModifier.seconds(for: "OK"), 2.5, accuracy: 0.001)
        XCTAssertEqual(ToastModifier.seconds(for: String(repeating: "a", count: 100)), 6, accuracy: 0.001)
        XCTAssertEqual(ToastModifier.seconds(for: String(repeating: "a", count: 500)), 8, accuracy: 0.001)
    }

    /// Ansage erst nach 400 ms (nach dem Einblenden, abgebrochen → keine); gleicher Text erneut = neuer Toast.
    func testToastAnsageVerzoegertUndGleicherTextStartetNeu() throws {
        XCTAssertEqual(ToastModifier.announceDelay, 0.4, accuracy: 0.0001)
        // Eigene Kennung: `.task(id:)` startet auch bei gleichem Text neu
        XCTAssertNotEqual(ToastMessage("OK"), ToastMessage("OK"))
        let same = ToastMessage("OK")
        XCTAssertEqual(same, same)
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/Components/EmptyStateView.swift")
        let source = try String(contentsOf: file, encoding: .utf8)
        let wait = try XCTUnwrap(source.range(of: "try await Task.sleep(nanoseconds: UInt64(Self.announceDelay"))
        let announce = try XCTUnwrap(source.range(of: "AccessibilityNotification.Announcement(shown.text).post()"))
        XCTAssertLessThan(wait.lowerBound, announce.lowerBound)
        XCTAssertEqual(source.components(separatedBy: "AccessibilityNotification.Announcement(").count, 2)
    }
}
