import XCTest
@testable import CryptoChecker

/// Zeilen, die das Speichern ablehnen würde, stehen schon in der Vorschau unter «übersprungen»
/// (`csv_import.json` › «unsavable», `CsvImportMapping.unsavableReason`). Dazu: die Regel deckt sich mit der
/// Bereinigung beim Speichern (`AppData.cleanedPortfolioTx`). Gegenstück: `CsvUnsavableParityTest.kt`.
final class CsvImportUnsavableTests: XCTestCase {

    private typealias S = TestSupport

    private func cases() throws -> [[String: Any]] { S.list(try S.fixture("csv_import")["unsavable"]) }

    private func decimal(_ value: Any?) -> Decimal? { (value as? String).flatMap { Decimal(string: $0, locale: Locale(identifier: "en_US_POSIX")) } }

    private func tx(_ c: [String: Any]) throws -> CsvImportTx {
        let type = try XCTUnwrap(PortfolioTxType(rawValue: c["type"] as? String ?? ""))
        return CsvImportTx(line: 2, coin: c["coin"] as? String ?? "", type: type, amount: decimal(c["amount"]) ?? -1,
                           priceUsdt: nil, time: 1_700_000_000_000, fee: nil, feeUnit: nil, feeCoin: nil,
                           feeCoinAmount: nil, note: nil, importKey: "TEST|1", toCoin: c["toCoin"] as? String,
                           toAmount: decimal(c["toAmount"]))
    }

    func testReasonMatchesSharedCases() throws {
        let all = try cases()
        XCTAssertGreaterThanOrEqual(all.count, 15)
        for c in all {
            let name = c["name"] as? String ?? "?"
            let expected = (c["expected"] as? String).flatMap(CsvImportSkipReason.init(rawValue:))
            XCTAssertEqual(CsvImportMapping.unsavableReason(try tx(c)), expected, name)
        }
    }

    /// Wie beim Speichern: bereinigen (`cleanedPortfolioTx`); nil = abgelehnt.
    func testRuleMatchesSaving() throws {
        for c in try cases() {
            let t = try tx(c)
            let saves = AppData.cleanedPortfolioTx(PortfolioCsvImporter.tx(t)) != nil
            XCTAssertEqual(CsvImportMapping.unsavableReason(t) == nil, saves, c["name"] as? String ?? "?")
        }
    }

    func testToCoinLengthAsWhenSaving() {
        XCTAssertEqual(CsvImportMapping.maxToCoin, PortfolioTx.maxFeeCoin)
        // Länge in UTF-16-Einheiten wie Kotlin: 8 Zeichen, aber 16 Einheiten → zu lang (auch beim Speichern)
        let wide = String(repeating: "\u{1F600}", count: 8)
        XCTAssertEqual(CsvImportMapping.unsavableReason(coin: "ETH", type: .SWAP, amount: 1, toCoin: wide, toAmount: 2),
                       .INVALID_SWAP)
        var swap = PortfolioTx(coin: "ETH", type: .SWAP, amountExact: 1, time: 1)
        swap.toCoin = wide
        swap.toAmountExact = 2
        XCTAssertNil(AppData.cleanedPortfolioTx(swap))
        XCTAssertEqual(PortfolioCsvImporter.skipKey(.AMOUNT_TOO_SMALL), "portfolio_csv_skip_amount_too_small")
        XCTAssertEqual(PortfolioCsvImporter.skipKey(.INVALID_SWAP), "portfolio_csv_skip_invalid_swap")
    }

    /// In der Vorschau: abgelehnte Zeilen unter den übersprungenen (nach Zeile, bei gleicher Zeile Datei-Gründe
    /// zuerst), nicht unter den neuen und nicht in missingFx, missingPrice, usdtApproximate und stableSwaps.
    func testPreviewSkipsUnsavableRows() {
        let tiny = Decimal(sign: .plus, exponent: -19, significand: 1)
        let rows = [
            row(2, amount: 1),
            row(3, amount: tiny, quote: "CHF"),
            row(4, kind: .SWAP, amount: 1, toCoin: " btc ", toAmount: 1),
            row(5, kind: .SWAP, amount: 1, toCoin: "ETH", toAmount: nil),
            row(6, kind: .SWAP, amount: 1, toCoin: "ETH", toAmount: 20),
            row(8, amount: tiny, quote: "USDT", stableSwap: true),
        ]
        let result = CsvImportResult(format: .GENERIC, rows: rows, skipped: [CsvImportSkip(line: 4, reason: .BAD_DATE),
                                                                            CsvImportSkip(line: 7, reason: .BAD_DATE)])
        // CHF ohne Tageskurs: Zeile 3 fehlte sonst unter missingFx
        let preview = CsvImportMapping.preview(result, existingKeys: []) { _, _ in nil }
        XCTAssertEqual(preview.transactions.map(\.line), [2, 6])
        XCTAssertEqual(preview.skipped, [
            CsvImportSkip(line: 3, reason: .AMOUNT_TOO_SMALL), CsvImportSkip(line: 4, reason: .BAD_DATE),
            CsvImportSkip(line: 4, reason: .INVALID_SWAP), CsvImportSkip(line: 5, reason: .INVALID_SWAP),
            CsvImportSkip(line: 7, reason: .BAD_DATE), CsvImportSkip(line: 8, reason: .AMOUNT_TOO_SMALL),
        ])
        XCTAssertTrue(preview.missingFx.isEmpty)
        XCTAssertFalse(preview.stableSwaps)
        // Gegenprobe: so, wie gespeichert würde
        let saved = CsvImport.priceInUsdt(rows) { _, _ in nil }.filter { p in
            AppData.cleanedPortfolioTx(PortfolioCsvImporter.tx(CsvImportMapping.toTx(p))) != nil
        }
        XCTAssertEqual(saved.map(\.row.line), preview.transactions.map(\.line))
    }

    private func row(_ line: Int, kind: CsvImportKind = .BUY, amount: Decimal, quote: String = "USDT",
                     toCoin: String? = nil, toAmount: Decimal? = nil, stableSwap: Bool = false) -> CsvImportRow {
        CsvImportRow(line: line, kind: kind, rawKind: kind.rawValue, coin: "BTC", amount: amount, price: 60_000,
                     quote: quote, fee: nil, feeUnit: nil, timeMillis: 1_709_280_000_000 + Int64(line) * 60_000,
                     externalId: "op-\(line)", key: "k-\(line)", toCoin: toCoin, toAmount: toAmount,
                     stableSwap: stableSwap)
    }
}
