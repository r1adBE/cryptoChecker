import XCTest
@testable import CryptoChecker

/// Wie `BtcCorrelationTest.kt`: Gleichlauf mit Bitcoin im «Warum?»-Blatt (gleiche Reihen).
final class BtcCorrelationTests: XCTestCase {

    private let hour: Int64 = 3_600_000
    private var start: Int64 { 1_700_000_000_000 - (1_700_000_000_000 % hour) }

    /// Kerzen aus Log-Renditen (erste Kerze `startPrice`); letzte Kerze = laufende Stunde.
    private func series(_ returns: [Double], startPrice: Double = 100) -> [MarketCandle] {
        var price = startPrice
        var out = [MarketCandle(openTime: start, open: price, high: price, low: price, close: price, volume: 1)]
        for (i, r) in returns.enumerated() {
            price *= exp(r)
            out.append(MarketCandle(openTime: start + Int64(i + 1) * hour, open: price, high: price, low: price,
                                    close: price, volume: 1))
        }
        return out
    }

    /// Wechselnde, ungleichmässige Renditen (deterministisch).
    private func wave(_ n: Int, scale: Double = 0.01) -> [Double] {
        (0..<n).map { i in scale * Double(((i * 7) % 11) - 5) / 5.0 }
    }

    func testIdenticalMovesAreTight() throws {
        let btc = series(wave(30))
        let sol = series(wave(30).map { $0 * 1.6 }, startPrice: 150)
        let r = try XCTUnwrap(BtcCorrelation.correlation(base: "SOL", candles: sol, btc: btc))
        XCTAssertEqual(r, 1, accuracy: 1e-9)
        XCTAssertEqual(BtcCorrelation.link(base: "SOL", candles: sol, btc: btc), .tight)
    }

    func testOppositeOrUnrelatedMovesAreIndependent() throws {
        let btc = series(wave(30))
        let inverse = series(wave(30).map { -$0 })
        XCTAssertEqual(BtcCorrelation.link(base: "XRP", candles: inverse, btc: btc), .independent)
        let other = series((0..<30).map { i in 0.01 * (Double((i * 3) % 4) - 1.5) })
        let r = try XCTUnwrap(BtcCorrelation.correlation(base: "XRP", candles: other, btc: btc))
        XCTAssertEqual(BtcCorrelation.link(r), .independent)
    }

    func testMiddleBandGivesNoSentence() {
        XCTAssertNil(BtcCorrelation.link(0.5))
        XCTAssertNil(BtcCorrelation.link(0.79))
        XCTAssertEqual(BtcCorrelation.link(0.8), .tight)
        XCTAssertEqual(BtcCorrelation.link(0.3), .independent)
        XCTAssertEqual(BtcCorrelation.link(-0.4), .independent)
        XCTAssertNil(BtcCorrelation.link(nil))
        XCTAssertNil(BtcCorrelation.link(.nan))
    }

    func testBitcoinItselfAndMissingSeriesGiveNothing() {
        let btc = series(wave(30))
        XCTAssertNil(BtcCorrelation.correlation(base: "BTC", candles: btc, btc: btc))
        XCTAssertNil(BtcCorrelation.correlation(base: " btc ", candles: btc, btc: btc))
        XCTAssertNil(BtcCorrelation.correlation(base: "SOL", candles: nil, btc: btc))
        XCTAssertNil(BtcCorrelation.correlation(base: "SOL", candles: btc, btc: nil))
    }

    func testNeedsAtLeast24CommonReturnsWithoutRunningCandle() {
        let btc25 = series(wave(25))
        XCTAssertNotNil(BtcCorrelation.correlation(base: "ETH", candles: btc25, btc: btc25))
        let btc24 = series(wave(24))
        XCTAssertNil(BtcCorrelation.correlation(base: "ETH", candles: btc24, btc: btc24))
    }

    func testOnlyCommonConsecutiveHoursCount() throws {
        let btc = series(wave(30))
        let full = series(wave(30))
        let coin = full.enumerated().filter { $0.offset != 10 }.map { $0.element }
        let r = try XCTUnwrap(BtcCorrelation.correlation(base: "ADA", candles: coin, btc: btc))
        XCTAssertEqual(r, 1, accuracy: 1e-9)
        let sparse = full.enumerated().filter { $0.offset % 5 != 0 }.map { $0.element }
        XCTAssertNil(BtcCorrelation.correlation(base: "ADA", candles: sparse, btc: btc))
    }

    func testFlatSeriesHasNoCorrelation() {
        let btc = series(wave(30))
        let flat = series(Array(repeating: 0, count: 30))
        XCTAssertNil(BtcCorrelation.correlation(base: "USDC", candles: flat, btc: btc))
    }

    func testPearsonBasics() throws {
        XCTAssertEqual(try XCTUnwrap(BtcCorrelation.pearson([1, 2, 3], [2, 4, 6])), 1, accuracy: 1e-12)
        XCTAssertEqual(try XCTUnwrap(BtcCorrelation.pearson([1, 2, 3], [3, 2, 1])), -1, accuracy: 1e-12)
        XCTAssertNil(BtcCorrelation.pearson([1], [1]))
    }
}
