import XCTest
import UIKit
@testable import CryptoChecker

/// Haptik-Katalog: Ereignis → Generator (gleiche Tabelle wie `HapticsTest.kt`). Dass nur `Haptics.swift` die
/// Generatoren anspricht, prüft `HapticsUsageTest.kt` (liest auch ios/).
final class HapticsTests: XCTestCase {

    func testEveryEventHasItsGenerator() {
        let expected: [HapticEvent: HapticKind] = [
            .selection: .selection,
            .lightImpact: .impact(.light),
            .longPress: .impact(.medium),
            .success: .notification(.success),
            .warning: .notification(.warning),
            .error: .notification(.error),
            .destructive: .impact(.heavy),
        ]
        XCTAssertEqual(Set(expected.keys), Set(HapticEvent.allCases))
        for (event, kind) in expected {
            XCTAssertEqual(Haptics.kind(for: event), kind, "\(event)")
        }
    }

    /// Löschen fühlt sich weder wie «gespeichert» noch wie langes Drücken an.
    func testDestructiveStandsApart() {
        let destructive = Haptics.kind(for: .destructive)
        for other in HapticEvent.allCases where other != .destructive {
            XCTAssertNotEqual(Haptics.kind(for: other), destructive, "\(other)")
        }
    }

    /// «Adresse hinzufügen» wie Android `addWatchedHaptic`.
    func testAddWatchedAddressHaptics() {
        XCTAssertEqual(AddWatchedResult.added(7).haptic, .success)
        XCTAssertEqual(AddWatchedResult.duplicate.haptic, .warning)
        XCTAssertEqual(AddWatchedResult.limitReached.haptic, .warning)
        XCTAssertEqual(AddWatchedResult.locked.haptic, .error)
    }

    /// Verschlüsselte Sicherung: genau eine Fehler-Vibration je Fehlschlag (wie Android) — das Blatt spielt nur beim
    /// falschen Passwort; zu gross, neuere Version, kaputt oder zu wenig Speicher meldet der Aufrufer selbst.
    func testBackupPasswordSheetPlaysErrorOnlyForWrongPassword() {
        XCTAssertEqual(BackupPasswordCheck.wrongPassword.sheetHaptic, .error)
        XCTAssertNil(BackupPasswordCheck.handled.sheetHaptic)
        XCTAssertNil(BackupPasswordCheck.accepted.sheetHaptic)
    }
}
