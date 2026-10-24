import XCTest
@testable import CryptoChecker

/// Schnelle Wege gegen einfache Bezugsrechnungen (Testdaten `PortfolioPerformanceData` bzw. fester Zufall):
///  - FIFO-Posten: Herkunftsteile mit Platz-Index bei gemischtem Zuschlagen und Herausnehmen (auch ganz leeren, dann
///    neu füllen — der Index wird dabei zurückgesetzt) = dieselben Teile wie eine einfache Liste.
///  - FIFO-Warteschlange mit Kopf-Index: ganz leeren und aufräumen (ab 64 verbrauchten Plätzen) = einfache Liste.
///  - «Gewinn heute» aller Coins in einem Durchgang = Summe der Rechnungen je Coin (`only`).
///  - «7 T»/«1 J» mit Abbruchprüfung = ohne; abgebrochen kein Ergebnis.
final class PortfolioIndexParityTests: XCTestCase {
    private typealias D = PortfolioPerformanceData

    // MARK: FIFO-Posten

    func testLotAddAndTakeMixedLikePlainList() {
        var rng = IndexRandom(seed: 126)
        var emptied = 0
        for round in 0..<150 {
            let lot = PortfolioFifo.Lot(txId: Int64(round), acquired: 1_000, time: 1_000, kind: .BUY, amount: 10,
                                        cost: 100)
            var plain: [PortfolioCostPart] = []
            PortfolioCostParts.add(&plain, 1_000, 100)
            var cost: Decimal = 100
            var amount: Decimal = 10
            for step in 0..<80 {
                switch rng.next() % 4 {
                case 0, 1:
                    // Zuschlag zu einem von 20 Zeitpunkten (oft derselbe: über den Index addiert)
                    let time = Int64(rng.next() % 20) * 100
                    let usdt = Decimal(Int(rng.next() % 500)) / 100
                    lot.addCost(time, usdt)
                    cost += usdt
                    PortfolioCostParts.add(&plain, time, usdt)
                default:
                    // Teilmenge oder (jedes vierte Mal) alles herausnehmen
                    let whole = rng.next() % 4 == 0
                    let qty = whole ? amount : amount * Decimal(Int(1 + rng.next() % 9)) / 10
                    let removed = qty == amount ? cost : PortfolioDecimal.divide(cost * qty, amount)
                    let expectedTaken = PortfolioCostParts.take(&plain, total: cost, target: removed)
                    let taken = lot.takeCost(qty)
                    XCTAssertEqual(taken?.cost, removed, "Runde \(round) Schritt \(step)")
                    XCTAssertEqual(taken?.parts, expectedTaken, "Runde \(round) Schritt \(step)")
                    cost -= removed
                    amount -= qty
                    if plain.isEmpty { emptied += 1 }
                    // Leer: wieder auffüllen (neue Menge, Teile kommen mit den nächsten Zuschlägen)
                    if amount <= 0 { amount = 5 }
                    lot.amount = amount
                }
                XCTAssertEqual(lot.parts, plain, "Runde \(round) Schritt \(step)")
            }
            XCTAssertEqual(lot.takeCost(lot.amount)?.cost, cost, "Runde \(round) Rest")
            _ = PortfolioCostParts.take(&plain, total: cost, target: cost)
            XCTAssertEqual(lot.parts, plain, "Runde \(round) Rest")
        }
        XCTAssertGreaterThan(emptied, 100, "Index-Zurücksetzen nach dem Leeren geprüft")
    }

    // MARK: FIFO-Warteschlange

    func testQueueDrainAndCompactLikePlainList() {
        var rng = IndexRandom(seed: 59)
        let q = PortfolioFifo.Queue(key: "")
        var plain: [PortfolioFifo.Lot] = []
        var id: Int64 = 0
        var drained = 0
        for _ in 0..<120 {
            // Zugänge (teils rückdatiert), dann Abgänge vorn bis ganz leer oder einen Teil
            for _ in 0..<Int(1 + rng.next() % 200) {
                id += 1
                let acquired = Int64(rng.next() % 1_000)
                let lot = PortfolioFifo.Lot(txId: id, acquired: acquired, time: acquired, kind: .BUY, amount: 1,
                                            cost: nil)
                q.insert(lot)
                var i = plain.count
                while i > 0 && lot.isBefore(plain[i - 1]) { i -= 1 }
                plain.insert(lot, at: i)
            }
            let take = rng.next() % 3 == 0 ? plain.count : Int(rng.next() % UInt64(plain.count + 1))
            for _ in 0..<take {
                q.removeFirst()
                plain.removeFirst()
                XCTAssertEqual(q.first?.txId, plain.first?.txId)
            }
            if plain.isEmpty { drained += 1 }
            XCTAssertEqual(q.isEmpty, plain.isEmpty)
            XCTAssertEqual(q.lots.map(\.txId), plain.map(\.txId))
            // Leer weiter entfernen: nichts passiert
            if plain.isEmpty {
                q.removeFirst()
                XCTAssertTrue(q.isEmpty)
            }
        }
        XCTAssertGreaterThan(drained, 10, "ganz geleert und neu gefüllt")
    }

    // MARK: «Gewinn heute» aller Coins = Summe je Coin

    func testTodayAllCoinsEqualsSumOfSingleCoins() throws {
        let trades = D.trades(1_500)
        let current = D.prices()
        var coinChanges: [String: Double] = [:]
        for (i, coin) in D.coins.enumerated() { coinChanges[coin] = Double(i) - 4.5 }
        let windowStart = D.endMillis - 25 * 86_400_000
        for method in CostMethod.allCases {
            let summary = PortfolioCalculator.summarize(trades, prices: current, method: method)
            var holdings: [String: Double] = [:]
            for p in summary.open { holdings[p.coin] = p.holdings }
            let raw = try XCTUnwrap(PortfolioHistory.todayFromCoinChanges(holdings: holdings, current: current,
                                                                          coinChanges: coinChanges))
            let all = try XCTUnwrap(PortfolioPeriodChange.todayAdjusted(raw, trades: trades, current: current,
                                                                        coinChanges: coinChanges,
                                                                        windowStart: windowStart, method: method))
            var sum = 0.0
            var skipped: [String] = []
            for coin in PortfolioLedger.coins(trades) {
                let single = PortfolioHistory.todayFromCoinChanges(holdings: [coin: holdings[coin] ?? 0],
                                                                   current: current, coinChanges: coinChanges)
                    ?? PortfolioChange(amount: 0, percent: nil)
                guard let adjusted = PortfolioPeriodChange.todayAdjusted(single, trades: trades, current: current,
                                                                         coinChanges: coinChanges,
                                                                         windowStart: windowStart, only: coin,
                                                                         method: method) else {
                    // Coin ganz ausgelassen (Zufluss ohne Preis): seine Kursänderung fällt auch aus der Summe
                    skipped.append(coin)
                    continue
                }
                if adjusted.skipped.contains(coin) { skipped.append(coin) }
                sum += adjusted.amount
            }
            XCTAssertEqual(all.amount, sum, accuracy: 1e-6 * max(1, abs(sum)), "\(method)")
            XCTAssertEqual(all.skipped, skipped.sorted(), "\(method)")
        }
    }

    // MARK: «7 T»/«1 J» abbrechbar

    private static func periodInput() -> PortfolioPeriodInput {
        let trades = D.trades(400)
        let today = PortfolioHistory.epochDay(utcMillis: D.endMillis) - 1
        var closes: [String: [Int: Double]] = [:]
        for coin in D.coins {
            var series: [Int: Double] = [:]
            for day in (today - 400)...today {
                series[day] = D.priceAtDouble(coin, Int64(day) * 86_400_000 + 43_200_000)
            }
            closes[coin] = series
        }
        var current: [String: Double] = [:]
        for coin in D.coins { current[coin] = closes[coin]?[today] }
        return PortfolioPeriodInput(trades: trades, current: current, closes: closes, addresses: [], method: .FIFO,
                                    today: today, fxNow: nil, viaFx: false, fxSeries: nil,
                                    usdt: UsdtUsdRates(live: nil, daily: [:]))
    }

    func testPeriodFiguresCancellableSameResult() throws {
        let input = PortfolioIndexParityTests.periodInput()
        let plain = PortfolioPeriodFigures.compute(input)
        let checked = try XCTUnwrap(PortfolioPeriodFigures.compute(input, cancelled: { false }))
        XCTAssertNotNil(plain.week, "Testdaten ergeben «7 T»")
        XCTAssertEqual(checked.week, plain.week)
        XCTAssertEqual(checked.weekUsdt, plain.weekUsdt)
        XCTAssertEqual(checked.year, plain.year)
        // Sofort abgebrochen bzw. nach «7 T» (vor «1 J»): kein Ergebnis
        XCTAssertNil(PortfolioPeriodFigures.compute(input, cancelled: { true }))
        for stop in 0...2 {
            var asked = 0
            let result = PortfolioPeriodFigures.compute(input, cancelled: {
                asked += 1
                return asked > stop
            })
            XCTAssertNil(result, "Abbruch bei Prüfung \(stop + 1)")
        }
    }
}

/// Fester Zufall für diese Tests (SplitMix64).
private struct IndexRandom {
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
