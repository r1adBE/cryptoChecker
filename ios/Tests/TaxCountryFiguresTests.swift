import XCTest
@testable import CryptoChecker

/// Länder-Schlüsse erst nach Fachprüfung (`TaxCountryFigures`); gleiche Regel wie Android `TaxCountryFiguresTest`.
final class TaxCountryFiguresTests: XCTestCase {
    private func summary(_ template: TaxTemplate) -> TaxYearSummary {
        TaxYearSummary(template: template, method: .FIFO, period: template.period(2025), currency: "EUR",
                       fxAvailable: true,
                       lines: [TaxSummaryLine(line: .NET, usdt: 10, target: 10),
                               TaxSummaryLine(line: .DE_PRIVATE_LIMIT, target: 1000, status: .below),
                               TaxSummaryLine(line: .AT_RATE, usdt: 1, target: 1)])
    }

    func testNoTemplateReviewedYet() {
        XCTAssertTrue(TaxCountryFigures.reviewed.isEmpty)
    }

    func testUnreviewedTemplateKeepsOnlyNeutralSums() {
        for t in [TaxTemplate.DE, .AT, .CH] {
            XCTAssertEqual(TaxCountryFigures.shown(summary(t)).lines.map(\.line), [.NET])
        }
    }
}
