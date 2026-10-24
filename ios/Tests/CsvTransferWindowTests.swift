import XCTest
@testable import CryptoChecker

/// Umbuchungs-Abgleich (`CsvTransferMatch.candidates`) mit Zeitfenster per Binärsuche (`CsvTransferWindow`) gegen die
/// naive Rechnung «jede Auszahlung mit jeder Einzahlung» auf Zufallsdaten mit Seed: dieselben Paare in derselben
/// Reihenfolge, dieselben Kandidaten samt «eindeutig». Wie `CsvTransferWindowTest.kt` (Android).
final class CsvTransferWindowTests: XCTestCase {
    private static let hour: Int64 = 3_600_000
    private static let day: Int64 = 86_400_000
    private static let coins = ["BTC", "ETH", "SOL"]

    private struct Item {
        let coin: String
        let time: Int64
        let value: Int
    }

    func testPairsMatchTheDoubleLoop() {
        let early = 2 * Self.hour
        let window = 72 * Self.hour
        for seed in UInt64(1)...50 {
            var rng = PerformanceRandom(seed: seed)
            func items(_ n: Int) -> [Item] {
                (0..<n).map { _ in
                    let coin = CsvTransferWindowTests.coins[rng.nextInt(CsvTransferWindowTests.coins.count)]
                    let time = Int64(rng.nextInt(Int(30 * CsvTransferWindowTests.day / CsvTransferWindowTests.hour)))
                        * CsvTransferWindowTests.hour
                    return Item(coin: coin, time: time, value: rng.nextInt(10))
                }
            }
            let outs = items(rng.nextInt(150))
            let ins = items(rng.nextInt(150))
            // Wie `CsvTransferMatch`: nur derselbe Coin im Fenster, dazu eine weitere Bedingung
            let fits: (Item, Item) -> Bool = { w, d in
                w.coin == d.coin && d.time >= w.time - early && d.time <= w.time + window && (w.value + d.value) % 3 != 0
            }
            var naive: [[Int]] = []
            for wi in outs.indices {
                for di in ins.indices where fits(outs[wi], ins[di]) { naive.append([wi, di]) }
            }
            let fast = CsvTransferWindow.pairs(outs, ins, coin: \.coin, time: \.time, early: early, window: window,
                                               fits: fits)
            XCTAssertEqual(naive, fast.map { [$0.0, $0.1] }, "Seed \(seed)")
        }
    }

    func testCandidatesMatchTheNaiveReference() {
        var links = 0
        for seed in UInt64(1)...30 {
            var rng = PerformanceRandom(seed: seed)
            let txs = (0..<(10 + rng.nextInt(60))).map { Self.tx(&rng, $0) }
            let stored = (0..<rng.nextInt(25)).map { Self.stored(&rng, $0) }
            let place: String? = seed % 2 == 0 ? "Binance" : nil
            let actual = CsvTransferMatch.candidates(txs, place: place, stored: stored)
            XCTAssertEqual(Self.naive(txs, place: place, stored: stored), actual, "Seed \(seed)")
            links += actual.count
        }
        XCTAssertGreaterThan(links, 100, "Kandidaten geprüft")
    }

    /// Naive Referenz: jedes Paar (Auszahlung, Einzahlung) in der Reihenfolge der Rechnung einzeln abgleichen (ein Paar
    /// ergibt höchstens einen Kandidaten), «eindeutig» über alle Paare, sortiert wie `CsvTransferMatch.candidates`.
    private static func naive(_ txs: [CsvImportTx], place: String?, stored: [CsvStoredMove]) -> [CsvTransferLink] {
        let free = stored.filter { $0.linkedImportKey == nil }
        typealias Side = (txs: [CsvImportTx], stored: [CsvStoredMove])
        let outs: [Side] = txs.filter { $0.type == .WITHDRAWAL }.map { ([$0], []) }
            + free.filter { $0.type == .WITHDRAWAL || ($0.type == .TRANSFER && $0.transferTo == nil) }.map { ([], [$0]) }
        let ins: [Side] = txs.filter { $0.type == .DEPOSIT }.map { ([$0], []) }
            + free.filter { $0.type == .DEPOSIT }.map { ([], [$0]) }
        var found: [CsvTransferLink] = []
        for w in outs {
            for d in ins {
                found += CsvTransferMatch.candidates(w.txs + d.txs, place: place, stored: w.stored + d.stored)
            }
        }
        var perOut: [String: Int] = [:]
        var perIn: [String: Int] = [:]
        for l in found {
            perOut[l.withdrawalKey, default: 0] += 1
            perIn[l.depositKey, default: 0] += 1
        }
        let marked = found.map { l -> CsvTransferLink in
            CsvTransferLink(
                key: l.key, coin: l.coin, withdrawalKey: l.withdrawalKey, depositKey: l.depositKey,
                withdrawalId: l.withdrawalId, depositId: l.depositId, sent: l.sent, received: l.received, fee: l.fee,
                feeUnit: l.feeUnit, feeCoin: l.feeCoin, feeCoinAmount: l.feeCoinAmount, withdrawalTime: l.withdrawalTime,
                depositTime: l.depositTime, from: l.from, to: l.to,
                unambiguous: perOut[l.withdrawalKey] == 1 && perIn[l.depositKey] == 1, stored: l.stored,
                absorbed: l.absorbed)
        }
        return marked.enumerated().sorted { a, b in
            if a.element.withdrawalTime != b.element.withdrawalTime {
                return a.element.withdrawalTime < b.element.withdrawalTime
            }
            if a.element.depositTime != b.element.depositTime { return a.element.depositTime < b.element.depositTime }
            if a.element.key != b.element.key {
                return a.element.key.unicodeScalars.lexicographicallyPrecedes(b.element.key.unicodeScalars) {
                    $0.value < $1.value
                }
            }
            return a.offset < b.offset
        }.map(\.element)
    }

    private static func tx(_ rng: inout PerformanceRandom, _ i: Int) -> CsvImportTx {
        let type: PortfolioTxType = rng.nextInt(2) == 0 ? .WITHDRAWAL : .DEPOSIT
        let amount = Decimal(100 + rng.nextInt(5)) / 100
        var fee: Decimal?
        var unit: PortfolioFeeUnit?
        switch rng.nextInt(4) {
        case 0:
            fee = Decimal(string: "0.01")
            unit = .COIN
        case 1:
            fee = 1
            unit = .QUOTE
        default:
            break
        }
        let coin = coins[rng.nextInt(coins.count)]
        let time = Int64(rng.nextInt(Int(10 * day / hour))) * hour
        return CsvImportTx(line: i + 1, coin: coin, type: type, amount: amount, priceUsdt: nil, time: time, fee: fee,
                           feeUnit: unit, feeCoin: nil, feeCoinAmount: nil, note: nil, importKey: "BINANCE|\(i)")
    }

    private static func stored(_ rng: inout PerformanceRandom, _ i: Int) -> CsvStoredMove {
        let type: PortfolioTxType = [.WITHDRAWAL, .DEPOSIT, .TRANSFER][rng.nextInt(3)]
        let coin = coins[rng.nextInt(coins.count)]
        let amount = Decimal(100 + rng.nextInt(5)) / 100
        let fee: Decimal? = rng.nextInt(2) == 0 ? Decimal(string: "0.01") : nil
        let time = Int64(rng.nextInt(Int(10 * day / hour))) * hour
        let linked: String? = rng.nextInt(8) == 0 ? "COINBASE|\(i)" : nil
        return CsvStoredMove(id: 1_000 + Int64(i), coin: coin, type: type, amount: amount, fee: fee, feeUnit: .COIN,
                             feeCoin: nil, feeCoinAmount: nil, time: time, importKey: "KRAKEN|\(i)",
                             transferFrom: type == .TRANSFER ? "Kraken" : nil, transferTo: nil,
                             linkedImportKey: linked)
    }
}
