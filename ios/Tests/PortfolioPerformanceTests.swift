import XCTest
@testable import CryptoChecker

/// Bestände eines grossen Portfolios (bis 10 000 Transaktionen, `PortfolioPerformanceData`): Gesamtsicht der Karte
/// (`PortfolioCalculator.summarize`: Bestand, Einstand, Ø bzw. FIFO-Posten, realisiert) je Einstandsmethode und der
/// Bestand zu einem Zeitpunkt (`PortfolioCalculator.holdingsAt`, Verlauf, Stichtag). Grenzen und Skalierung:
/// `PerformanceTiming` mit `transactions` (Debug-Build, darum grosszügiger als Android). Wie
/// `PortfolioPerformanceTest.kt`.
final class PortfolioPerformanceTests: XCTestCase {
    /// Unter Thread Sanitizer (CI setzt `CC_SKIP_PERFORMANCE=1`) übersprungen: die Zeiten wären verfälscht.
    override func setUpWithError() throws {
        try super.setUpWithError()
        try PerformanceTiming.skipIfRequested()
    }

    private typealias T = PerformanceTiming
    private typealias D = PortfolioPerformanceData
    /// Rundung der anteiligen Teile (34 Stellen) ist weit darunter.
    private static let tolerance = Decimal(sign: .plus, exponent: -12, significand: 1)

    private static let prices = PortfolioPerformanceData.prices()

    private static func summary(_ trades: [PortfolioTx], _ method: CostMethod) -> PortfolioSummary {
        PortfolioCalculator.summarize(trades, prices: PortfolioPerformanceTests.prices, method: method,
                                      withdrawalPrice: { PortfolioPerformanceData.priceAtDouble($0, $1) })
    }

    func testDataIsDeterministicAndMixed() {
        let n = T.transactions.large
        let trades = D.trades(n)
        XCTAssertEqual(trades, D.trades(n))
        XCTAssertEqual(trades.count, n)
        XCTAssertTrue(zip(trades, trades.dropFirst()).allSatisfy { pair in pair.0.time < pair.1.time },
                      "zeitlich aufsteigend")
        let years = Set(trades.map { LocalDay(epochMillisUTC: $0.time).year })
        XCTAssertEqual(years, Set(2019...2025))
        // Jede Art kommt vor (mindestens 0,5 %), Gebühren in allen drei Formen
        var byType: [PortfolioTxType: Int] = [:]
        for t in trades { byType[t.type, default: 0] += 1 }
        for type in PortfolioTxType.allCases { XCTAssertGreaterThanOrEqual(byType[type] ?? 0, n / 200, "\(type)") }
        XCTAssertTrue(trades.contains { $0.coinFeeExact > 0 } && trades.contains { $0.quoteFeeExact > 0 })
        XCTAssertTrue(trades.contains { $0.thirdFeeCoin == "BNB" })
        XCTAssertTrue(trades.contains { $0.acquiredTime != nil } && trades.contains { $0.priceExact == nil })
        XCTAssertEqual(Set(trades.compactMap { $0.exchange ?? $0.transferTo }), Set(D.wallets))
    }

    func testSummaryFifo() {
        // Karte mit FIFO-Posten über alle Wallets: unter 1 s für 10 000 Transaktionen (Debug, mit Abdeckung). Die Gebühr
        // USDT einer Umbuchung steht nur einmal im Gebühren-Strom der Warteschlange (PortfolioFifoParts),
        // nicht als Teil je offenem Posten (das gäbe ×7,8 bei vierfacher Menge). Nur die feste Grenze: der
        // iOS-Lauf (Debug, Abdeckung, geteilter Mac) skaliert unzuverlässig — die Skalierung prüft Android
        T.assertLimitOnly("Portfolio-Übersicht FIFO", limitMillis: 10_000, sizes: T.transactions) { n in
            let trades = D.trades(n)
            return T.medianMillis { PortfolioPerformanceTests.summary(trades, .FIFO) }
        }
        assertSameHoldings(.FIFO)
    }

    func testSummaryFifoPerWallet() {
        // FIFO je Wallet (Umbuchungen verschieben Posten): rund 0,5 s (Debug). Nur die feste Grenze, gleicher Grund wie
        // oben (Teilungen je Herkunftsteil der verschobenen und abgegebenen Posten)
        T.assertLimitOnly("Portfolio-Übersicht FIFO je Wallet", limitMillis: 12_000, sizes: T.transactions) { n in
            let trades = D.trades(n)
            return T.medianMillis { PortfolioPerformanceTests.summary(trades, .FIFO_WALLET) }
        }
        assertSameHoldings(.FIFO_WALLET)
    }

    func testSummaryAverage() {
        // Ø der Karte und des Widgets ohne Herkunftsteile (`withParts` false, Anzeige in USDT): je Schritt gleich viel
        // Arbeit; erwartet rund 0,3–1 s für 10 000 Transaktionen (Debug). Nur die feste Grenze wie bei FIFO: der
        // iOS-Lauf (Debug, Abdeckung, geteilter Mac) skaliert unzuverlässig — die Skalierung prüft Android
        T.assertLimitOnly("Portfolio-Übersicht Ø", limitMillis: 10_000, sizes: T.transactions) { n in
            let trades = D.trades(n)
            return T.medianMillis(warmup: T.quickWarmupRuns, runs: T.quickMeasuredRuns) {
                PortfolioCalculator.summarize(trades, prices: PortfolioPerformanceTests.prices, method: .AVERAGE,
                                              withdrawalPrice: { PortfolioPerformanceData.priceAtDouble($0, $1) },
                                              withParts: false)
            }
        }
        assertSameHoldings(.AVERAGE)
    }

    func testSummaryAverageWithParts() {
        // Ø mit Herkunftsteilen (Anzeigewährung ≠ USDT, wie der Steuer-Export): ein Abgang merkt sich nur seinen Anteil
        // (`PortfolioCostPartPool`, O(1) bzw. O(log n) je Abgang), die abgegebenen Teile werden erst beim Auslesen
        // gerechnet — die Karte liest nur die offenen Teile. Erwartet rund 0,4–1,5 s für 10 000 Transaktionen (Debug).
        // Nur die feste Grenze wie bei den übrigen Übersichten: der iOS-Lauf (Debug, Abdeckung, geteilter Mac) skaliert
        // unzuverlässig — die Skalierung desselben Wegs prüft Android (`summaryAverageWithParts`)
        T.assertLimitOnly("Portfolio-Übersicht Ø mit Herkunftsteilen", limitMillis: 15_000, sizes: T.transactions) { n in
            let trades = D.trades(n)
            return T.medianMillis(warmup: T.quickWarmupRuns, runs: T.quickMeasuredRuns) {
                PortfolioPerformanceTests.summary(trades, .AVERAGE)
            }
        }
    }

    func testHoldingsAtTime() {
        // Bestand per Ende 2024 nach allen drei Methoden (Verlauf, Stichtag): erwartet rund 0,2–0,6 s (Debug)
        let end2024: Int64 = 1_735_689_599_999
        T.assertScales("Bestand zum Zeitpunkt (3 Methoden)", limitMillis: 6_000, sizes: T.transactions) { n in
            let trades = D.trades(n)
            return T.medianMillis {
                CostMethod.allCases.map { PortfolioCalculator.holdingsAt(trades, time: end2024, method: $0).count }
            }
        }
    }

    /// Die Karte nennt dieselben Mengen wie `PortfolioCalculator.holdingsAt` (eine Regel für alle Bestände).
    private func assertSameHoldings(_ method: CostMethod, file: StaticString = #filePath, line: UInt = #line) {
        let trades = D.trades(T.transactions.large)
        let s = PortfolioPerformanceTests.summary(trades, method)
        var held: [String: Decimal] = [:]
        for entry in PortfolioCalculator.holdingsAt(trades, method: method) { held[entry.coin] = entry.amount }
        XCTAssertGreaterThanOrEqual(s.open.count, D.coins.count / 2, "\(method)", file: file, line: line)
        XCTAssertEqual(Set(held.keys), Set(s.open.map(\.coin)), "\(method)", file: file, line: line)
        for p in s.open {
            let diff = abs((held[p.coin] ?? 0) - p.ledgerHoldingsExact)
            XCTAssertLessThanOrEqual(diff, PortfolioPerformanceTests.tolerance, "\(p.coin) \(method)",
                                     file: file, line: line)
        }
    }
}
