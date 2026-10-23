import XCTest
@testable import CryptoChecker

/// Ergebnis von Sichern/Wiederherstellen als Dialog oder Toast (`Parity/backup_result_dialog.json`) und Anzeigedauer
/// des Toasts. Gegenstück: `BackupResultDialogParityTest.kt`.
final class BackupResultDialogParityTests: XCTestCase {
    private typealias S = TestSupport

    func testEntscheidetWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("backup_result_dialog")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let name = c["description"] as? String ?? "?"
            let note = (c["addressServerNote"] as? String).flatMap { BackupRestorePolicy.AddressServerNote(rawValue: $0) }
            let actual = BackupResultPresentation.asDialog(
                key: try XCTUnwrap(c["key"] as? String, name),
                text: try XCTUnwrap(c["text"] as? String, name),
                droppedPairs: Int(S.number(c["droppedPairs"]) ?? -1),
                droppedAlarms: Int(S.number(c["droppedAlarms"]) ?? -1),
                appLockKept: try XCTUnwrap(c["appLockKept"] as? Bool, name),
                addressServerNote: note
            )
            XCTAssertEqual(actual ? "dialog" : "toast", c["expected"] as? String, name)
        }
    }

    func testGrenzeUndSchluesselWieGemeinsameTestfaelle() throws {
        let fixture = try S.fixture("backup_result_dialog")
        XCTAssertEqual(Int(S.number(fixture["toastMaxChars"]) ?? 0), BackupResultPresentation.toastMaxChars)
        XCTAssertEqual(Set(fixture["longKeys"] as? [String] ?? []), BackupResultPresentation.longKeys)
    }

    /// 0,06 s je Zeichen, mindestens 2,5 s, höchstens 8 s.
    func testToastDauerNachTextlaenge() {
        XCTAssertEqual(ToastModifier.seconds(for: "OK"), 2.5, accuracy: 0.001)
        XCTAssertEqual(ToastModifier.seconds(for: String(repeating: "a", count: 100)), 6, accuracy: 0.001)
        XCTAssertEqual(ToastModifier.seconds(for: String(repeating: "a", count: 500)), 8, accuracy: 0.001)
    }
}
