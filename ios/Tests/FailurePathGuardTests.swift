import XCTest

/// Wächter für Fehlerwege, deren Regel im Ablauf steckt (Audit 232, N3/B3): liest die Quelle, weil der Weg ohne
/// Netz, Speicher und Systemdienste nicht nachzustellen ist. Gegenstücke auf Android sind Unit-Tests mit Fakes.
final class FailurePathGuardTests: XCTestCase {
    private func source(_ path: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
    }

    /// N3-01: Kurs-Mitteilung nur bei «nicht gehandelt»/stillgelegt entfernen, nicht bei jedem Fehler.
    func testPriceNotificationStaysOnOrdinaryFailure() throws {
        let steps = try source("Shared/Services/PriceRefresher+Steps.swift")
        XCTAssertTrue(steps.contains("if notTraded { cancelIds.append(watch.id) }"))
        XCTAssertFalse(steps.contains("            cancelIds.append(watch.id)\n"), "kein Entfernen ohne Bedingung")
    }
}
