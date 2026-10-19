import XCTest
@testable import CryptoChecker

/// Jahresübersicht mit USDT = 1 an einem Tag ohne USDT/USD-Schluss (z. B. Kauf vor dem Fenster der Quellen): nie still —
/// `TaxYearSummary.usdtApproximate` und eine eigene Zeile in der CSV. Wie `TaxSummaryUsdtTest.kt`.
final class TaxSummaryUsdtTests: XCTestCase {
    private let buyDay = 20213 // 5.5.2025
    private let sellDay = 20270 // 1.7.2025
    private let trades = [
        PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 10000, time: 1_746_446_400_000),
        PortfolioTx(id: 2, coin: "BTC", type: .SELL, amountExact: 1, priceExact: 12000, time: 1_751_371_200_000),
    ]
    private let texts = TaxSummaryTexts(
        title: "T", disclaimer: "D", item: "I", usdt: "USDT", target: "CHF", note: "N", orientation: "O", labels: [:],
        below: "below", reached: "reached", unknown: "unknown", incomplete: "inc", noFx: "nofx",
        usdtAssumed: "USDT dollar rate missing")

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    private func summary(_ usdtUsd: [Int: Decimal]?) throws -> TaxYearSummary {
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        return TaxExport.yearSummary(trades, template: .NEUTRAL, method: .FIFO, startYear: 2025, timeZone: utc,
                                     currency: "CHF", rates: [buyDay: dec("0.83"), sellDay: dec("0.80")], usdtUsd: usdtUsd)
    }

    private func lines(_ s: TaxYearSummary) -> [String] {
        String(TaxExport.summaryCsv(s, texts: texts, header: []).dropFirst()).components(separatedBy: "\r\n")
    }

    func testAllDaysCoveredStaysQuiet() throws {
        let s = try summary([buyDay: dec("0.97"), sellDay: dec("0.98")])
        XCTAssertFalse(s.usdtApproximate)
        XCTAssertFalse(lines(s).contains("USDT dollar rate missing"))
    }

    func testMissingDayIsMarked() throws {
        // Kauftag ohne Schluss (vor dem Fenster): Einstand in CHF mit USDT = 1
        let s = try summary([sellDay: dec("0.98")])
        XCTAssertTrue(s.usdtApproximate)
        // Nach Titel und Hinweis, vor der Spaltenzeile
        XCTAssertEqual(Array(lines(s).prefix(4)), ["T", "D", "USDT dollar rate missing", "I;USDT;CHF;N"])
    }

    func testUsdtCurrencyNeedsNoNote() throws {
        // Ohne USDT-Umrechnung (usdtUsd nil) gibt es nichts anzunehmen
        XCTAssertFalse(try summary(nil).usdtApproximate)
    }
}
