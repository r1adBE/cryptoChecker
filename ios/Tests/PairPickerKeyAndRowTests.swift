import XCTest
@testable import CryptoChecker

/// Schlüssel der Paarwahl einmal je Stand; Hinweis «Schwelle ungültig»
/// nicht mitgedimmt; Wisch-Löschen mit Rückfrage ohne `role: .destructive` — die Stellen in den Ansichten prüft die
/// Regel `ios-wiring` in `.github/scripts/source_rules.py`.
@MainActor
final class PairPickerKeyAndRowTests: XCTestCase {
    /// Zehn Abfragen beim selben Stand rechnen den Schlüssel höchstens einmal, mit gleichem Wert wie ungemerkt.
    func testPairPickerKeyOncePerState() throws {
        let data = AppData.shared
        let generation = data.snapshotGeneration
        let before = AppData.pairPickerKeyComputations
        var inputs: [AppData.AlarmPairPickerInput] = []
        for _ in 0..<10 { inputs.append(data.alarmPairPickerInput) }
        guard data.snapshotGeneration == generation else { throw XCTSkip("Stand hat sich zwischendurch geändert") }
        XCTAssertLessThanOrEqual(AppData.pairPickerKeyComputations - before, 1)
        let input = try XCTUnwrap(inputs.last)
        XCTAssertEqual(input.sourceKey, AlarmPairPicker.sourceKey(data.watches))
        XCTAssertEqual(input.watches.map(\.id), data.watches.map(\.id))
    }
}
