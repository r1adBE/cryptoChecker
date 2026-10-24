import XCTest
@testable import CryptoChecker

/// Durchschnittsmethode (`PortfolioCalculator.average`) und Steuer-Pool (`TaxPool`) mit `PortfolioCostPartPool` gegen die
/// frühere Rechnung (`averageReference`, `TaxPoolReference`: Liste der Herkunftsteile, Teil für Teil neu geschrieben) auf
/// zufälligen Ledgern mit Seed (Käufe mit Gebühren in USDT/im Coin, Teilverkäufe, Tausch, Belohnungen ohne Preis,
/// Umbuchungen mit Netzwerkgebühr, Auszahlungen) und den Leistungstest-Daten: alle Zahlen gleich, Herkunftsteile gleich
/// bis auf die Rundung der 34. Stelle, ihre Summen exakt. Wie `PortfolioCostPartsEquivalenceTest.kt` (Android).
final class PortfolioCostPartsEquivalenceTests: XCTestCase {
    private static let hour: Int64 = 3_600_000
    private static let day: Int64 = 86_400_000
    /// Teile weichen nur in der Rundung ab (gemessen ≈ 10⁻²⁶ bei Beträgen bis 10⁸ USDT).
    private static let tolerance = Decimal(string: "1E-18")!

    private static func sum(_ parts: [PortfolioCostPart]) -> Decimal { parts.reduce(Decimal(0)) { $0 + $1.usdt } }

    private func assertParts(_ label: String, _ expected: [PortfolioCostPart], _ actual: [PortfolioCostPart],
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(expected.count, actual.count, "\(label) Anzahl", file: file, line: line)
        for (i, pair) in zip(expected, actual).enumerated() {
            XCTAssertEqual(pair.0.time, pair.1.time, "\(label) Teil \(i) Zeitpunkt", file: file, line: line)
            XCTAssertLessThanOrEqual(abs(pair.0.usdt - pair.1.usdt), Self.tolerance,
                                     "\(label) Teil \(i) \(pair.0.usdt) / \(pair.1.usdt)", file: file, line: line)
        }
        if !expected.isEmpty {
            // Decimal hat 38 Stellen: Summen in anderer Reihenfolge runden ab der 38. Stelle anders
            XCTAssertLessThanOrEqual(abs(Self.sum(expected) - Self.sum(actual)), Self.tolerance, "\(label) Summe",
                                     file: file, line: line)
        }
    }

    private static func withoutParts(_ d: PortfolioDisposal) -> PortfolioDisposal {
        var copy = d
        copy.costParts = []
        return copy
    }

    private func compareAverage(_ label: String, _ trades: [PortfolioTx]) {
        for entry in PortfolioLedger.eventsByCoin(trades) {
            let l = "\(label) \(entry.coin)"
            let expected = PortfolioCalculator.averageReference(entry.coin, events: entry.events)
            let actual = PortfolioCalculator.average(entry.coin, events: entry.events)
            XCTAssertEqual(expected.priced, actual.priced, "\(l) priced")
            XCTAssertEqual(expected.unpriced, actual.unpriced, "\(l) unpriced")
            XCTAssertEqual(expected.cost, actual.cost, "\(l) cost")
            XCTAssertEqual(expected.realized, actual.realized, "\(l) realized")
            XCTAssertEqual(expected.unknownSold, actual.unknownSold, "\(l) unknownSold")
            XCTAssertEqual(expected.soldTotal, actual.soldTotal, "\(l) soldTotal")
            XCTAssertEqual(expected.withdrawn, actual.withdrawn, "\(l) withdrawn")
            XCTAssertEqual(expected.oversold, actual.oversold, "\(l) oversold")
            XCTAssertEqual(expected.feeValueMissing, actual.feeValueMissing, "\(l) feeValueMissing")
            XCTAssertEqual(expected.realizedFeeMissing, actual.realizedFeeMissing, "\(l) realizedFeeMissing")
            XCTAssertEqual(expected.withdrawals, actual.withdrawals, "\(l) withdrawals")
            XCTAssertEqual(expected.lostFeeCosts, actual.lostFeeCosts, "\(l) lostFeeCosts")
            XCTAssertEqual(expected.unassignedFees, actual.unassignedFees, "\(l) unassignedFees")
            XCTAssertEqual(expected.disposals.count, actual.disposals.count, "\(l) disposals")
            for (i, pair) in zip(expected.disposals, actual.disposals).enumerated() {
                XCTAssertEqual(Self.withoutParts(pair.0), Self.withoutParts(pair.1), "\(l) Veräusserung \(i)")
                assertParts("\(l) Veräusserung \(i)", pair.0.costParts.items, pair.1.costParts.items)
            }
            assertParts("\(l) offen", expected.openParts, actual.openParts)
            if !actual.openParts.isEmpty {
                let gap = abs(Self.sum(actual.openParts) - actual.cost)
                XCTAssertLessThanOrEqual(gap, Self.tolerance, "\(l) Summe offen")
            }
        }
    }

    func testAverageMatchesFormerCalculationOnRandomLedgers() {
        for seed in UInt64(1)...30 { compareAverage("Seed \(seed)", Self.randomLedger(seed: seed, count: 400)) }
    }

    func testAverageMatchesFormerCalculationOnPerformanceData() {
        compareAverage("Leistungsdaten", PortfolioPerformanceData.trades(2_500))
    }

    func testTaxPoolMatchesFormerPool() {
        for seed in UInt64(1)...30 {
            var rng = PerformanceRandom(seed: seed)
            let zone: TimeZone? = seed % 3 == 0 ? nil : TimeZone(identifier: "UTC")
            var pool = TaxPool(zone: zone)
            var former = TaxPoolReference(zone: zone)
            // Ziel der fortgeführten Teile (Umbuchung in ein anderes Wallet): ein eigener Pool wie in der App — derselbe
            // Pool würde seine Teile bei jedem Zurückbuchen verdoppeln
            var dest = TaxPool(zone: zone)
            var destFormer = TaxPoolReference(zone: zone)
            var time: Int64 = 1_650_000_000_000
            var carried: (former: TaxTaken, pool: TaxTaken)?
            for step in 0..<500 {
                let l = "Seed \(seed) Schritt \(step)"
                time += Self.hour + Int64(rng.nextInt(Int(2 * Self.day - Self.hour)))
                let qty = Self.amount(&rng, scale: 6)
                switch rng.nextInt(10) {
                case 0, 1, 2:
                    let cost: Decimal? = rng.nextInt(6) == 0 ? nil : Self.amount(&rng, scale: 4)
                    pool.add(qty, cost: cost, time: time)
                    former.add(qty, cost: cost, time: time)
                case 3, 4, 5:
                    let expected = former.take(min(qty, former.held * Decimal(string: "0.6")!))
                    let actual = pool.take(min(qty, pool.held * Decimal(string: "0.6")!))
                    XCTAssertEqual(expected.removed, actual.removed, "\(l) removed")
                    XCTAssertEqual(expected.removedPriced, actual.removedPriced, "\(l) removedPriced")
                    XCTAssertEqual(expected.cost, actual.cost, "\(l) cost")
                    assertParts(l, expected.parts.items, actual.parts.items)
                    carried = (expected, actual)
                case 6:
                    // Fortgeführter Einstand mit Herkunft (Umbuchung) — Teile eines Abgangs, je einmal in den Ziel-Pool
                    if let taken = carried {
                        destFormer.addCarried(taken.former.removedPriced, from: taken.former.parts.items)
                        dest.addCarried(taken.pool.removedPriced, from: taken.pool.parts)
                        XCTAssertEqual(destFormer.cost, dest.cost, "\(l) Ziel Einstand")
                        carried = nil
                    }
                case 7:
                    let expected = former.dropQty(min(qty, former.held))
                    XCTAssertEqual(expected, pool.dropQty(min(qty, pool.held)), "\(l) Netzwerkgebühr")
                case 8:
                    let fee = Self.amount(&rng, scale: 2)
                    let expected = former.addFee(time, fee, amount: qty, coinFee: 0)
                    XCTAssertEqual(expected, pool.addFee(time, fee, amount: qty, coinFee: 0), "\(l) Gebühr")
                default:
                    // Tages-Pool übernehmen (UK): Zugänge eines früheren Tags
                    let day = time - Int64(rng.nextInt(Int(10 * Self.day)))
                    var otherFormer = TaxPoolReference()
                    var other = TaxPool()
                    for _ in 0..<(1 + rng.nextInt(3)) {
                        let q = Self.amount(&rng, scale: 6)
                        let c = Self.amount(&rng, scale: 4)
                        otherFormer.add(q, cost: c, time: day)
                        other.add(q, cost: c, time: day)
                    }
                    former.absorb(&otherFormer)
                    pool.absorb(&other)
                }
                XCTAssertEqual(former.priced, pool.priced, "\(l) priced")
                XCTAssertEqual(former.unpriced, pool.unpriced, "\(l) unpriced")
                XCTAssertEqual(former.cost, pool.cost, "\(l) Einstand")
                if step % 50 == 0 { assertParts("\(l) offen", former.parts, pool.parts) }
            }
            assertParts("Seed \(seed) offen", former.parts, pool.parts)
            assertParts("Seed \(seed) Ziel", destFormer.parts, dest.parts)
        }
    }

    /// Menge/Betrag mit `scale` Nachkommastellen (> 0).
    private static func amount(_ rng: inout PerformanceRandom, scale: Int) -> Decimal {
        Decimal(1 + rng.nextInt(10_000_000)) * Decimal(sign: .plus, exponent: -scale, significand: 1)
    }

    /// Zufälliges Ledger mit Seed: zwei Coins, alle Abgangsarten, Gebühren in USDT und im Coin.
    private static func randomLedger(seed: UInt64, count: Int) -> [PortfolioTx] {
        var rng = PerformanceRandom(seed: seed)
        var out: [PortfolioTx] = []
        var time: Int64 = 1_600_000_000_000
        for i in 1...count {
            time += hour + Int64(rng.nextInt(Int(3 * day - hour)))
            let coin = rng.nextInt(3) == 0 ? "ETH" : "BTC"
            let other = coin == "BTC" ? "ETH" : "BTC"
            let price = amount(&rng, scale: 2)
            let qty = amount(&rng, scale: 8)
            let id = Int64(i)
            var t: PortfolioTx
            switch rng.nextInt(20) {
            case 0...6:
                t = PortfolioTx(id: id, coin: coin, type: .BUY, amountExact: qty, priceExact: price, time: time)
                if rng.nextInt(2) == 0 {
                    t.feeExact = amount(&rng, scale: 2)
                    t.feeUnit = .QUOTE
                }
            case 7:
                t = PortfolioTx(id: id, coin: coin, type: .BUY, amountExact: qty, priceExact: price, time: time,
                                feeExact: qty / 1_000, feeUnit: .COIN)
            case 8:
                let priced = rng.nextInt(2) == 0
                t = PortfolioTx(id: id, coin: coin, type: .REWARD, amountExact: qty, priceExact: priced ? price : nil,
                                time: time)
            case 9...12:
                var third = Decimal()
                var raw = qty / 3
                NSDecimalRound(&third, &raw, 8, .down)
                t = PortfolioTx(id: id, coin: coin, type: .SELL, amountExact: third, priceExact: price, time: time,
                                feeExact: amount(&rng, scale: 2), feeUnit: .QUOTE)
            case 13:
                t = PortfolioTx(id: id, coin: coin, type: .SWAP, amountExact: qty / 4, time: time)
                t.toCoin = other
                t.toAmountExact = qty
                t.swapValueExact = price
            case 14:
                t = PortfolioTx(id: id, coin: coin, type: .WITHDRAWAL, amountExact: qty / 5, priceExact: price, time: time,
                                feeExact: 1, feeUnit: .QUOTE)
            case 15:
                t = PortfolioTx(id: id, coin: coin, type: .TRANSFER, amountExact: qty / 2, time: time,
                                feeExact: qty / 2_000, feeUnit: .COIN)
                t.transferFrom = "A"
                t.transferTo = "B"
            case 16:
                t = PortfolioTx(id: id, coin: coin, type: .TRANSFER, amountExact: qty, time: time, feeExact: 1,
                                feeUnit: .QUOTE)
                t.transferFrom = "B"
                t.transferTo = "A"
            case 17:
                let priced = rng.nextInt(2) == 0
                t = PortfolioTx(id: id, coin: coin, type: .DEPOSIT, amountExact: qty, priceExact: priced ? price : nil,
                                time: time)
            case 18:
                t = PortfolioTx(id: id, coin: coin, type: .PAYMENT, amountExact: qty / 10, priceExact: price, time: time)
            default:
                t = PortfolioTx(id: id, coin: coin, type: .SELL, amountExact: qty * 5, priceExact: price, time: time)
            }
            out.append(t)
        }
        return out
    }
}
