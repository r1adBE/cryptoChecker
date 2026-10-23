import XCTest
@testable import CryptoChecker

/// Review PERF4-R1, UX4-R4, UX4-R5: Schlüssel der Paarwahl einmal je Stand; Hinweis «Schwelle ungültig»
/// nicht mitgedimmt; Wisch-Löschen mit Rückfrage ohne `role: .destructive`.
@MainActor
final class B106IosRowTests: XCTestCase {
    /// PERF4-R1: zehn Abfragen beim selben Stand rechnen den Schlüssel höchstens einmal, mit gleichem Wert wie bisher.
    func testPaarwahlSchluesselEinmalJeStand() throws {
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

    /// PERF4-R1: beide Aufrufer übergeben den gemerkten Schlüssel (nicht im `body` des Blatts gerechnet).
    func testAufruferUebergebenGemerktenSchluessel() throws {
        for file in ["Alarms/AlarmsOverviewScreen.swift", "Watchlist/WatchlistSheets.swift"] {
            let text = try feature(file)
            XCTAssertTrue(text.contains("AlarmPairPicker(watches: input.watches, sourceKey: input.sourceKey)"), file)
            XCTAssertFalse(text.contains("AlarmPairPicker(watches: data.watches)"), file)
        }
    }

    /// UX4-R4: Paar-Zeile der Übersicht dimmt nur den Schalter, nicht den Hinweis.
    func testUebersichtDimmtHinweisNicht() throws {
        let overview = try feature("Alarms/AlarmsOverviewScreen.swift")
        XCTAssertTrue(overview.contains("verticalPadding: 8, dimsLabel: false)"))
        let components = try String(contentsOf: app("Components/Components.swift"), encoding: .utf8)
        XCTAssertTrue(components.contains(".opacity(enabled || !dimsLabel ? 1 : 0.5)"))
    }

    /// FUN4-R2: Portfolio-Zeile mit ungültiger Schwelle nennt «löschen und neu anlegen» (kein Editor), nicht «anpassen».
    func testPortfolioZeileNenntLoeschenUndNeuAnlegen() throws {
        let overview = try feature("Alarms/AlarmsOverviewScreen.swift")
        let card = try XCTUnwrap(overview.range(of: "private func portfolioCard("))
        let text = overview[card.lowerBound...]
        XCTAssertTrue(text.contains("\"portfolio_alarm_threshold_invalid_recreate\""))
        XCTAssertFalse(text.contains("\"alarm_threshold_invalid_fix\""))
        XCTAssertTrue(text.contains("deletePortfolio = alarm"), "die genannte Aktion gibt es")
    }

    /// UX4-R5: Wisch-Löschen mit (asynchroner) Rückfrage ohne Rolle — die Zeile verschwindet sonst vor der Antwort.
    func testWischLoeschenMitRueckfrageOhneRolle() throws {
        for file in ["Watchlist/WatchlistScreen+Row.swift", "Portfolio/PortfolioAddressSheet.swift",
                     "Alarms/AlarmsScreen.swift"] {
            let text = try feature(file)
            let swipe = try XCTUnwrap(text.range(of: ".swipeActions(edge: .trailing"), file)
            let tint = try XCTUnwrap(text.range(of: ".tint(AppColors.destructive)", range: swipe.upperBound..<text.endIndex),
                                     file)
            XCTAssertFalse(text[swipe.upperBound..<tint.lowerBound].contains("role: .destructive"), file)
        }
    }

    private func app(_ path: String) -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("App/\(path)")
    }

    private func feature(_ path: String) throws -> String {
        try String(contentsOf: app("Features/\(path)"), encoding: .utf8)
    }
}
