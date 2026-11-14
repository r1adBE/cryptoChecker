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

    /// Gespeicherter Stand nicht lesbar bzw. wartet aufs Speichern: stehender Hinweis im Portfolio-Tab wie in der Merkliste
    /// (Android `PortfolioScreen`); CSV-Import nennt den Grund «gesperrt» (Android `CsvImportState.Locked`).
    func testPortfolioStorageNoticeStandsAndCsvNamesLock() throws {
        let screen = try source("Features/Portfolio/PortfolioScreen.swift")
        XCTAssertTrue(screen.contains("WatchlistStorageNotice(notice: WatchlistStorageLock.notice(locked: storageLocked,"))
        XCTAssertTrue(screen.contains("keptKey: \"portfolio_storage_kept_in_memory\", notSavedKey: \"portfolio_save_pending\""))
        XCTAssertTrue(screen.contains("L(PortfolioCsvImportSheet.failureKey(data))"))
        XCTAssertTrue(try source("Features/Portfolio/PortfolioCsvImport.swift").contains("\"portfolio_csv_import_locked\""))
    }

    /// «Beträge verbergen» an der Wurzel (U3-01): jedes Blatt erbt den Schalter; kein zweiter Setzer weiter innen, der
    /// Blätter aussen vergessen könnte.
    func testHideAmountsIsSetAtTheRootOnly() throws {
        let app = try source("CryptoCheckerApp.swift")
        XCTAssertTrue(app.contains(".environment(\\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)"))
        for path in ["Features/Portfolio/PortfolioScreen.swift", "Features/Portfolio/PortfolioCoinDetail.swift",
                     "Features/Portfolio/PortfolioPositionCard.swift"] {
            XCTAssertFalse(try source(path).contains(".environment(\\.hidePortfolioAmounts"), path)
        }
    }

    // MARK: Quelle

    private func source(_ path: String) throws -> String {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/\(path)")
        return try String(contentsOf: file, encoding: .utf8)
    }
}
