import XCTest
@testable import CryptoChecker

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
