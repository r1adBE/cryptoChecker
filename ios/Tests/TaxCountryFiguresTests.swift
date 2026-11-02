import XCTest
@testable import CryptoChecker

/// Länder-Schlüsse erst nach Fachprüfung (`TaxCountryFigures`); gleiche Regel wie Android `TaxCountryFiguresTest`.
final class TaxCountryFiguresTests: XCTestCase {
    private func summary(_ template: TaxTemplate) -> TaxYearSummary {
        var s = TaxYearSummary(template: template, method: .FIFO, period: template.period(2025), currency: "EUR",
                               fxAvailable: true,
                               lines: [TaxSummaryLine(line: .NET, usdt: 10, target: 10),
                                       TaxSummaryLine(line: .DE_PRIVATE_LIMIT, target: 1000, status: .below),
                                       TaxSummaryLine(line: .AT_RATE, usdt: 1, target: 1),
                                       // «Vermögenswert 31.12. (Orientierung)» trägt immer den ESTV-Vermerk,
                                       // ohne orientation-Flag
                                       TaxSummaryLine(line: .CH_WEALTH_VALUE, usdt: 1, target: 1,
                                                      note: .CH_WEALTH_ORIENTATION)])
        s.wealth = TaxWealth(date: LocalDay(2025, 12, 31), rows: [], totalUsdt: 1, totalTarget: 1)
        return s
    }

    func testNoTemplateReviewedYet() {
        XCTAssertTrue(TaxCountryFigures.reviewed.isEmpty)
    }

    func testUnreviewedTemplateKeepsOnlyNeutralSums() {
        for t in [TaxTemplate.DE, .AT, .CH] {
            let shown = TaxCountryFigures.shown(summary(t))
            XCTAssertEqual(shown.lines.map(\.line), [.NET])
            // Ohne CH-Vermögenswert auch keine Werte je Coin (Karte und Übersicht-CSV)
            XCTAssertNil(shown.wealth)
        }
    }

    /// Gewinne, Einkünfte, Bestand, Übersicht: keine Kopfzeile «Kursliste ESTV», kein ESTV-Hinweis im Bestand.
    @MainActor
    func testUnreviewedChCsvHasNoEstvLines() {
        let header = ["Title", "Info", "Disclaimer"]
        let note = "Wealth tax not calculated – ESTV price list: https://example"
        XCTAssertEqual(TaxCountryFigures.withChWealthNote(.CH, header: header, chWealthNote: note), header)
        XCTAssertNil(TaxCountryFigures.holdingsTexts(.CH, CutoffCsvTexts.localized(currency: "CHF")).estvNoteFormat)
        XCTAssertTrue(TaxCountryFigures.isCountryLine(.CH_WEALTH_VALUE))
        XCTAssertTrue(TaxCountryFigures.isCountryLine(.CH_WEALTH))
        XCTAssertFalse(TaxCountryFigures.isCountryLine(.NET))
    }
}
