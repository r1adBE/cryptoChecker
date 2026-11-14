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

    /// Portfolio-Kurse: Netz, Zeitgrenze oder 5xx in der Kerzen-Kette heissen «nicht abrufbar», nicht «kein Kurs» bzw.
    /// «Kein Markt» — der Fehler kommt aus der Kette (`CandleDataSource` `attempt`), kein fester Wert. Wie Android
    /// `CandleDataSourceChainTest.attemptTellsFailureFromNoData`.
    func testCandleChainFailureIsUnavailable() throws {
        let prices = try source("Shared/Portfolio/PortfolioPriceSource.swift")
        XCTAssertTrue(prices.contains("PortfolioPriceIssue.of(failed: outcome.failed, allSourcesBlocked: sourcesBlocked,"))
        XCTAssertFalse(prices.contains("PortfolioPriceIssue.of(failed: false"))
        let requests = try source("Shared/Portfolio/PortfolioPriceSource+Requests.swift")
        XCTAssertTrue(requests.contains("identityName: identityName, attempt: attempt)"))
        let chain = try source("Shared/Insights/CandleDataSource.swift")
        XCTAssertTrue(chain.contains("case .TRANSIENT, .UNREACHABLE: attempt?.withLock { $0 = true }"))
    }
}
