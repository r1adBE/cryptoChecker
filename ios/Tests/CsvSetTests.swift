import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus CsvImportRereadTests

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

// ---- aus CsvReadChoiceTests

/// «Importieren» nicht während des Neu-Lesens und nur mit der Vorschau der aktuellen
/// Lesart (`csv_import.json` › «confirmGate»). Gegenstück: `CsvReadChoiceParityTest.kt`.
final class CsvReadChoiceTests: XCTestCase {
    private typealias S = TestSupport

    private func choice(_ raw: Any?) -> CsvReadChoice? {
        guard let o = raw as? [String: Any] else { return nil }
        return CsvReadChoice(zone: o["zone"] as? String,
                             dateOrder: (o["dateOrder"] as? String).flatMap(CsvDateOrder.init(rawValue:)),
                             decimalMark: (o["decimalMark"] as? String)?.first)
    }

    func testKnopfWieGemeinsameTestfaelle() throws {
        let cases = S.list(try S.fixture("csv_import")["confirmGate"])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let chosen = try XCTUnwrap(choice(c["chosen"]), name)
            let actual = CsvReadChoice.canConfirm(rereading: c["rereading"] as? Bool ?? false,
                                                  builtWith: choice(c["builtWith"]), chosen: chosen)
            XCTAssertEqual(actual, c["expected"] as? Bool, name)
        }
    }
}

// ---- aus CsvTransferMatchScaleTests

/// Umbuchungs-Abgleich beim CSV-Import (`CsvTransferMatch.candidates`) für eine grosse Datei: 20 000 Ein- und
/// Auszahlungen (12 Coins über ein Jahr, je Auszahlung eine passende Einzahlung eine Stunde später mit Netzwerkgebühr).
/// Einzahlungen je Coin nach Zeit, Zeitfenster per Binärsuche — feste Grenze (die Rechnung «jede mit jeder» bräuchte
/// 10⁸ Vergleiche). Wie `CsvTransferMatchScaleTest.kt` (Android).
final class CsvTransferMatchScaleTests: XCTestCase {
    private typealias T = PerformanceTiming
    private static let moves = 20_000
    /// Erwartet rund 0,3–1,5 s (Debug: Sortieren, Fenster, Kandidaten samt Sortierung).
    private static let limitMillis = 15_000.0
    private static let startMillis: Int64 = 1_704_067_200_000
    private static let yearMillis: Int64 = 31_536_000_000
    private static let hourMillis = 3_600_000

    private static func file(_ count: Int) -> [CsvImportTx] {
        var rng = PerformanceRandom(seed: PerformanceData.seed + 20)
        var out: [CsvImportTx] = []
        out.reserveCapacity(count)
        let half = count / 2
        for i in 0..<half {
            let coin = PortfolioPerformanceData.coins[rng.nextInt(PortfolioPerformanceData.coins.count)]
            let time = startMillis + yearMillis * Int64(i) / Int64(half) + Int64(rng.nextInt(hourMillis))
            let amount = Decimal(1_000_000 + rng.nextInt(1_000_000)) / 100_000_000
            out.append(CsvImportTx(line: 2 * i + 1, coin: coin, type: .WITHDRAWAL, amount: amount, priceUsdt: nil,
                                   time: time, fee: Decimal(string: "0.0001"), feeUnit: .COIN, feeCoin: nil,
                                   feeCoinAmount: nil, note: nil, importKey: "BINANCE|w\(i)"))
            out.append(CsvImportTx(line: 2 * i + 2, coin: coin, type: .DEPOSIT, amount: amount, priceUsdt: nil,
                                   time: time + Int64(hourMillis), fee: nil, feeUnit: nil, feeCoin: nil,
                                   feeCoinAmount: nil, note: nil, importKey: "KRAKEN|d\(i)"))
        }
        return out
    }

    func testTwentyThousandMovesStayFast() {
        let txs = Self.file(Self.moves)
        var links = 0
        let millis = T.medianMillis(warmup: T.quickWarmupRuns, runs: T.quickMeasuredRuns) { () -> Int in
            let found = CsvTransferMatch.candidates(txs, place: nil, stored: [])
            links = found.count
            return found.count
        }
        print("[Performance] Umbuchungs-Abgleich: \(Self.moves) Ein-/Auszahlungen \(String(format: "%.2f", millis)) ms · "
              + "Grenze \(String(format: "%.0f", Self.limitMillis)) ms")
        XCTAssertGreaterThanOrEqual(links, Self.moves / 2, "Kandidaten")
        XCTAssertLessThanOrEqual(millis, Self.limitMillis,
                                 "Umbuchungs-Abgleich: \(millis) ms für \(Self.moves) Ein-/Auszahlungen")
    }
}
