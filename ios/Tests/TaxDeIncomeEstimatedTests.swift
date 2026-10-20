import XCTest
@testable import CryptoChecker

/// DE 256-€-Grenze (§ 22 Nr. 3) mit zum Tagesschluss geschätzter Belohnung: Vermerk «geschätzt» an der Grenzzeile (Karte
/// und CSV), mit erfasstem Wert keiner. Wie `TaxDeIncomeEstimatedTest.kt` (Android).
final class TaxDeIncomeEstimatedTests: XCTestCase {

    private let day = 19_783 // 1.3.2024
    private let zone = TimeZone(identifier: "UTC")!

    private var texts: TaxSummaryTexts {
        var t = TaxSummaryTexts(title: "T", disclaimer: "D", item: "I", usdt: "USDT", target: "EUR", note: "N",
                                orientation: "O", labels: [.DE_INCOME_LIMIT: "LIMIT"], below: "below", reached: "reached",
                                unknown: "unknown", incomplete: "inc", noFx: "nofx")
        t.estimated = "est"
        return t
    }

    private func limit(_ price: Decimal?) throws -> (TaxSummaryLine, String) {
        let reward = PortfolioTx(id: 1, coin: "ETH", type: .REWARD, amountExact: Decimal(string: "0.01")!, priceExact: price,
                                 time: Int64(day) * 86_400_000 + 43_200_000)
        let s = TaxExport.yearSummary([reward], template: .DE, method: .FIFO, startYear: 2024, timeZone: zone,
                                      currency: "EUR", rates: [day: Decimal(string: "0.9")!], priceAt: { _, _ in 3000 })
        let line = try XCTUnwrap(s.lines.first { $0.line == .DE_INCOME_LIMIT })
        let csv = try XCTUnwrap(TaxExport.summaryCsv(s, texts: texts, header: []).components(separatedBy: "\r\n")
            .first { $0.contains("LIMIT;") })
        return (line, csv)
    }

    func testEstimatedDayCloseIsMarkedAtTheLimit() throws {
        let (line, csv) = try limit(nil)
        XCTAssertEqual(line.status, .below)
        XCTAssertTrue(line.estimated)
        XCTAssertTrue(csv.hasSuffix("below, est"), csv)
    }

    func testRecordedValueIsNotMarked() throws {
        let (line, csv) = try limit(2900)
        XCTAssertEqual(line.status, .below)
        XCTAssertFalse(line.estimated)
        XCTAssertTrue(csv.hasSuffix("below"), csv)
    }
}
