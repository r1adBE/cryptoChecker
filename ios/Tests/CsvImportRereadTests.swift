import XCTest
@testable import CryptoChecker

/// Neu-Lesen der CSV-Vorschau mit anderer Lesart (`PortfolioCsvImporter.reread`) behält die USDT/USD-Tagesschlüsse —
/// wie Android `reparse` — und Abbruch über `onRow` von `PortfolioCsvImporter.mapPreview`.
final class CsvImportRereadTests: XCTestCase {

    /// Coinbase in USD (wie `usdtRoundTrip` › `coinbase_usd` in `csv_import.json`): Zeilen 2 und 3.
    private let coinbaseUsd = "ID,Timestamp,Transaction Type,Asset,Quantity Transacted,Price Currency,"
        + "Price at Transaction,Subtotal,Total (inclusive of fees and/or spread),Fees and/or Spread,Notes\n"
        + "cb-1,2025-03-03 10:00:00 UTC,Buy,BTC,0.5,USD,$20000.00,$10000.00,$10000.00,$0.00,Bought 0.5 BTC\n"
        + "cb-2,2025-06-02 10:00:00 UTC,Sell,BTC,0.5,USD,$24000.00,$12000.00,$12000.00,$0.00,Sold 0.5 BTC\n"

    /// USDT/USD-Schlüsse am 2025-03-03 (Tag 20150) und 2025-06-02 (Tag 20241).
    private let closes: [Int: Decimal] = [20_150: Decimal(string: "0.998") ?? 1, 20_241: Decimal(string: "1.002") ?? 1]

    /// Andere Lesart ohne neue Kurse: die USDT/USD-Schlüsse vom Laden gelten weiter (USD ÷ Schluss DES Tags), nicht
    /// die Näherung USDT = 1.
    func testRereadKeepsUsdtUsdCloses() async {
        let zone = "Europe/Zurich"
        let choice = CsvReadChoice(zone: zone)
        let kept = await PortfolioCsvImporter.reread(coinbaseUsd, choice: choice, deviceZone: zone, existingKeys: [],
                                                     known: CsvImportRates(fx: [:], usdtUsd: closes), refetch: false)
        let bare = await PortfolioCsvImporter.reread(coinbaseUsd, choice: choice, deviceZone: zone, existingKeys: [],
                                                     known: CsvImportRates(fx: [:], usdtUsd: [:]), refetch: false)
        XCTAssertEqual(kept.rates.usdtUsd, closes, "Schlüsse bleiben für das nächste Neu-Lesen")
        XCTAssertEqual(kept.preview.transactions.count, 2)
        XCTAssertTrue(kept.preview.usdtApproximate.isEmpty, "mit Schlüsseln keine Näherung")
        XCTAssertEqual(bare.preview.usdtApproximate, [2, 3], "ohne Schlüsse: USDT = 1, als Näherung markiert")
        let price = kept.preview.transactions.first?.priceUsdt
        XCTAssertEqual(bare.preview.transactions.first?.priceUsdt, Decimal(20_000))
        XCTAssertNotEqual(price, Decimal(20_000), "Preis der USD-Zeile ist nicht die Näherung")
        XCTAssertEqual(price.map(PortfolioDecimal.double) ?? 0, 20_000 / 0.998, accuracy: 1e-6)
    }

    /// Wirft `onRow` (Abbruch), endet die Vorschau dort: kein weiterer Block wird umgerechnet.
    func testMapPreviewStopsWhenOnRowThrows() {
        let rows = (0..<250).map { i in
            CsvImportRow(line: 2 + i, kind: .BUY, rawKind: "Buy", coin: "BTC", amount: 1, price: 60_000, quote: "USDT",
                         fee: nil, feeUnit: nil, timeMillis: 1_709_280_000_000 + Int64(i) * 60_000,
                         externalId: "op-\(i)", key: "k-\(i)")
        }
        let result = CsvImportResult(format: .GENERIC, rows: rows, skipped: [])
        var calls: [Int] = []
        XCTAssertThrowsError(try PortfolioCsvImporter.mapPreview(
            result, existingKeys: [], rates: [:], withdrawalsAsTransfer: false, usdtUsd: [:],
            onRow: { done, _ in
                calls.append(done)
                if done >= 200 { throw CancellationError() }
            })) { error in
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(calls, [100, 200], "nach dem werfenden Block kein weiterer")
    }
}
