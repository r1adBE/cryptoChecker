import XCTest
@testable import CryptoChecker

/// Schnellere Wege rechnen dieselben Zahlen wie vorher (Testdaten `PortfolioPerformanceData`, gemischte Arten, Gebühren
/// in drei Formen, Tausch, Umbuchungen): Ø ohne Herkunftsteile (`withParts`), Schritte aller Coins in einem Durchgang
/// (`PortfolioLedger.eventsByCoin`, `PortfolioCalculator.ledgers`) und die FIFO-Warteschlange mit Kopf-Index.
final class PortfolioSpeedParityTests: XCTestCase {
    private typealias D = PortfolioPerformanceData
    private static let size = 2_500

    private static func positions(_ list: [CoinPosition]) -> [CoinPosition] {
        list.map { p in
            var copy = p
            copy.pricedCostParts = []
            return copy
        }
    }

    private static func withoutParts(_ list: [PortfolioDisposal]) -> [PortfolioDisposal] {
        list.map { d in
            var copy = d
            copy.costParts = []
            return copy
        }
    }

    // MARK: Ø mit und ohne Herkunftsteile

    func testDurchschnittOhneHerkunftsteileGleicheZahlen() {
        let trades = D.trades(Self.size)
        let prices = D.prices()
        func summary(_ withParts: Bool) -> PortfolioSummary {
            PortfolioCalculator.summarize(trades, prices: prices, method: .AVERAGE,
                                          withdrawalPrice: { D.priceAtDouble($0, $1) }, withParts: withParts)
        }
        let with = summary(true)
        let without = summary(false)
        XCTAssertTrue(with.open.contains { !$0.pricedCostParts.isEmpty }, "Testdaten führen Herkunftsteile")
        XCTAssertTrue(without.open.allSatisfy { $0.pricedCostParts.isEmpty })
        XCTAssertEqual(Self.positions(with.open), without.open)
        XCTAssertEqual(Self.positions(with.closed), without.closed)
        XCTAssertEqual(with.totalValue, without.totalValue)
        XCTAssertEqual(with.investedExact, without.investedExact)
        XCTAssertEqual(with.realizedExact, without.realizedExact)
        XCTAssertEqual(with.unrealized, without.unrealized)
        XCTAssertEqual(with.unrealizedPercent, without.unrealizedPercent)
        XCTAssertEqual(with.withdrawnGainExact, without.withdrawnGainExact)
        XCTAssertEqual(with.withdrawnGainIncomplete, without.withdrawnGainIncomplete)
        XCTAssertEqual(with.partialCostParts, without.partialCostParts)
        XCTAssertEqual(with.unassignedFeeUsdt, without.unassignedFeeUsdt)
        XCTAssertEqual(with.missingCostCoins, without.missingCostCoins)
        XCTAssertEqual(with.realizedIncomplete, without.realizedIncomplete)
    }

    func testDurchschnittVeraeusserungenOhneTeileGleich() {
        let trades = D.trades(Self.size)
        let with = PortfolioCalculator.ledgers(trades, method: .AVERAGE, withParts: true)
        let without = PortfolioCalculator.ledgers(trades, method: .AVERAGE, withParts: false)
        XCTAssertEqual(with.count, without.count)
        XCTAssertTrue(with.contains { l in l.disposals.contains { !$0.costParts.isEmpty } })
        for (a, b) in zip(with, without) {
            XCTAssertEqual(Self.withoutParts(a.disposals), b.disposals, a.position.coin)
            XCTAssertTrue(b.disposals.allSatisfy { $0.costParts.isEmpty })
            XCTAssertEqual(a.withdrawals, b.withdrawals, a.position.coin)
            XCTAssertEqual(a.lostFeeCosts, b.lostFeeCosts, a.position.coin)
            XCTAssertEqual(a.unassignedFees, b.unassignedFees, a.position.coin)
            XCTAssertEqual(a.openLots, b.openLots, a.position.coin)
        }
        // Steuer-Export (Standard) bekommt weiter die Teile
        XCTAssertTrue(PortfolioCalculator.disposals(trades, method: .AVERAGE).contains { !$0.costParts.isEmpty })
    }

    // MARK: Schritte aller Coins in einem Durchgang

    func testSchritteJeCoinWieEinzeln() {
        let trades = D.trades(Self.size)
        let byCoin = PortfolioLedger.eventsByCoin(trades)
        XCTAssertEqual(byCoin.map(\.coin), PortfolioLedger.coins(trades))
        func key(_ e: PortfolioLedgerEvent) -> String { "\(e.trade.id)|\(e.feeLeg)|\(e.swapIn)" }
        for entry in byCoin {
            XCTAssertEqual(entry.events.map(key), PortfolioLedger.events(entry.coin, trades: trades).map(key), entry.coin)
        }
        XCTAssertTrue(byCoin.contains { $0.events.contains(where: \.feeLeg) })
        XCTAssertTrue(byCoin.contains { $0.events.contains(where: \.swapIn) })
    }

    func testBuecherAllerCoinsWieEinzeln() {
        let trades = D.trades(Self.size)
        for method in CostMethod.allCases {
            let all = PortfolioCalculator.ledgers(trades, method: method)
            let single = PortfolioLedger.coins(trades).map {
                PortfolioCalculator.ledger($0, trades: trades, currentPrice: nil, method: method)
            }
            XCTAssertEqual(all, single, "\(method)")
        }
    }

    func testGebuehrVermerkeImSteuerExportWieVorher() {
        let trades = D.trades(Self.size)
        let zone = TimeZone(identifier: "UTC")!
        for method in CostMethod.allCases {
            let taxMethod = TaxMethod.of(method)
            let real = trades.filter { !AddressBalance.isAddressPart($0.id) && $0.amountExact > 0 }
            let applied = TaxEngines.applyRules(real, rules: .of(taxMethod), priceAt: nil)
            let ledgers = PortfolioLedger.coins(applied).map {
                PortfolioCalculator.ledger($0, trades: applied, currentPrice: nil, method: method)
            }
            for year in 2019...2025 {
                let inYear: (Int64) -> Bool = { TaxExport.localDay($0, timeZone: zone).year == year }
                let lost = ledgers.flatMap(\.lostFeeCosts).filter { inYear($0.time) }
                    .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
                let unassigned = ledgers.flatMap(\.unassignedFees).filter { inYear($0.time) }
                    .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
                XCTAssertEqual(TaxExport.lostFeeCosts(in: trades, method: method, year: year, timeZone: zone), lost,
                               "\(method) \(year)")
                XCTAssertEqual(TaxExport.unassignedFees(in: trades, method: method, year: year, timeZone: zone),
                               unassigned, "\(method) \(year)")
            }
        }
    }

    func testBestandsverlaufAusSchrittenWieAusTransaktionen() {
        let trades = D.trades(Self.size)
        let byCoin = PortfolioLedger.eventsByCoin(trades)
        for method in CostMethod.allCases {
            for entry in byCoin {
                let a = PortfolioHistory.holdingsTimelineExact(entry.coin, trades: trades, method: method)
                let b = PortfolioHistory.holdingsTimelineExact(events: entry.events, method: method)
                XCTAssertEqual(a.map(\.time), b.map(\.time), "\(entry.coin) \(method)")
                XCTAssertEqual(a.map(\.holdings), b.map(\.holdings), "\(entry.coin) \(method)")
            }
        }
    }

    // MARK: FIFO-Warteschlange und Herkunftsteile mit Index

    func testFifoBestaendeWieUnabhaengigesBuch() {
        // Bestände der FIFO-Posten (Warteschlange mit Kopf-Index) = unabhängige Rechnung `holdingsAt`
        let trades = D.trades(Self.size)
        for method in [CostMethod.FIFO, .FIFO_WALLET] {
            let summary = PortfolioCalculator.summarize(trades, prices: D.prices(), method: method)
            var held: [String: Decimal] = [:]
            for entry in PortfolioCalculator.holdingsAt(trades, method: method) { held[entry.coin] = entry.amount }
            XCTAssertEqual(Set(held.keys), Set(summary.open.map(\.coin)), "\(method)")
            for p in summary.open {
                XCTAssertLessThanOrEqual(abs((held[p.coin] ?? 0) - p.ledgerHoldingsExact),
                                         Decimal(sign: .plus, exponent: -12, significand: 1), "\(p.coin) \(method)")
            }
            // Zweimal gerechnet: dieselben Posten
            XCTAssertEqual(summary, PortfolioCalculator.summarize(trades, prices: D.prices(), method: method))
        }
    }

    func testWarteschlangeWieEinfacheListe() {
        // Zufällige Zugänge (auch rückdatiert) und Abgänge vorn: gleiche Reihenfolge wie eine einfache Liste
        var rng = SplitMix(seed: 125)
        let q = PortfolioFifo.Queue(key: "")
        var plain: [PortfolioFifo.Lot] = []
        for step in 0..<3_000 {
            if rng.next() % 3 == 0, !plain.isEmpty {
                q.removeFirst()
                plain.removeFirst()
            } else {
                let acquired = Int64(rng.next() % 500)
                let lot = PortfolioFifo.Lot(txId: Int64(step), acquired: acquired, time: acquired, kind: .BUY,
                                            amount: 1, cost: nil)
                q.insert(lot)
                var i = plain.count
                while i > 0 && lot.isBefore(plain[i - 1]) { i -= 1 }
                plain.insert(lot, at: i)
            }
            XCTAssertEqual(q.first?.txId, plain.first?.txId)
            XCTAssertEqual(q.isEmpty, plain.isEmpty)
        }
        XCTAssertEqual(q.lots.map(\.txId), plain.map(\.txId))
    }

    func testHerkunftsteileMitIndexWieOhne() {
        var rng = SplitMix(seed: 7)
        var plain: [PortfolioCostPart] = []
        var indexed: [PortfolioCostPart] = []
        var index: [Int64: Int] = [:]
        for _ in 0..<2_000 {
            let time = Int64(rng.next() % 40)
            let usdt = Decimal(Int(rng.next() % 1_000)) / 100
            PortfolioCostParts.add(&plain, time, usdt)
            PortfolioCostParts.add(&indexed, index: &index, time, usdt)
        }
        XCTAssertEqual(indexed, plain)
    }
}

/// Einfacher, fester Zufall für die Tests (SplitMix64).
private struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
