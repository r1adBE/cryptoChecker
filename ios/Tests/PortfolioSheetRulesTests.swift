import XCTest

/// Regeln, die an jeder Stelle gelten müssen (Blätter, Datumsfelder, Meldungen) — Quelltext-Wächter wie Android
/// `PortfolioSheetRulesTest`.
final class PortfolioSheetRulesTests: XCTestCase {

    /// Jedes Datumsfeld des Portfolios beginnt am 1.1.2009 (Android `DateDialog` MIN_YEAR).
    func testPortfolioDatePickersStartIn2009() throws {
        for path in ["Features/Portfolio/PortfolioAddressLotsSheet.swift", "Features/Portfolio/PortfolioTxLedgerFields.swift"] {
            let code = try source(path)
            XCTAssertFalse(code.contains("in: ...Date()"), "\(path): Datum ohne untere Grenze")
        }
        XCTAssertTrue(try source("Features/Portfolio/PortfolioAddressLotsSheet.swift")
            .contains("in: PortfolioTxSheet.dateRange()"))
    }

    // MARK: Quelle

    private func source(_ path: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/\(path)")
        return try String(contentsOf: file, encoding: .utf8)
    }
}
