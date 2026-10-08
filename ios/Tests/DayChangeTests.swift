import XCTest
@testable import CryptoChecker

/// Wie `DayChangeTest.kt` und `Change24hTest.kt` (24-h-Auswahl und Umrechnung der Börsenwerte).
final class DayChangeTests: XCTestCase {

    func testReferenceNeedsPositiveFiniteValues() {
        XCTAssertNotNil(DayReference.of(open: 100, lastClose: 110))
        XCTAssertNil(DayReference.of(open: nil, lastClose: 110))
        XCTAssertNil(DayReference.of(open: 0, lastClose: 110))
        XCTAssertNil(DayReference.of(open: 100, lastClose: -1))
        XCTAssertNil(DayReference.of(open: .nan, lastClose: 110))
        XCTAssertNil(DayReference.of(open: 100, lastClose: .infinity))
    }

    func testLivePriceAgainstThe24hOpen() throws {
        let ref = DayReference(open: 100, lastClose: 101)
        XCTAssertEqual(try XCTUnwrap(DayChange.fromPrice(102.3, ref)), 2.3, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(DayChange.fromPrice(96, ref)), -4, accuracy: 1e-9)
        let flat = DayReference(open: 100, lastClose: 100)
        XCTAssertNil(DayChange.fromPrice(nil, flat))
        XCTAssertNil(DayChange.fromPrice(.nan, flat))
        XCTAssertNil(DayChange.fromPrice(130, flat))
        XCTAssertNil(DayChange.fromPrice(70, flat))
        XCTAssertNotNil(DayChange.fromPrice(124, flat))
    }

    func testSelectPrefersThePairSeriesAndFallsBackOnlyForFiat() throws {
        let pair = DayReference(open: 100, lastClose: 105)
        let usdt = DayReference(open: 50, lastClose: 60)
        XCTAssertEqual(try XCTUnwrap(DayChange.select(price: 105, pairReference: pair, usdtReference: usdt, quoteIsFiat: true)),
                       5, accuracy: 1e-9)
        XCTAssertNil(DayChange.select(price: 500, pairReference: pair, usdtReference: usdt, quoteIsFiat: true))
        XCTAssertEqual(try XCTUnwrap(DayChange.select(price: 55, pairReference: nil, usdtReference: usdt, quoteIsFiat: true)),
                       20, accuracy: 1e-9)
        XCTAssertNil(DayChange.select(price: 55, pairReference: nil, usdtReference: usdt, quoteIsFiat: false))
        XCTAssertNil(DayChange.select(price: 55, pairReference: nil, usdtReference: nil, quoteIsFiat: true))
    }

    func testTickerValueAndChoose() {
        XCTAssertEqual(DayChange.fromTicker(3.5), 3.5)
        XCTAssertEqual(DayChange.fromTicker(9_999), 9_999)
        XCTAssertNil(DayChange.fromTicker(10_000))
        XCTAssertNil(DayChange.fromTicker(.nan))
        XCTAssertFalse(DayChange.needsCandles(1.2))
        XCTAssertTrue(DayChange.needsCandles(nil))
        var evaluated = false
        let chosen = DayChange.choose(tickerChange: 2) {
            evaluated = true
            return 5
        }
        XCTAssertEqual(chosen, 2)
        XCTAssertFalse(evaluated)
        let ref = DayReference(open: 100, lastClose: 101)
        let fromCandles = DayChange.choose(tickerChange: 1e6) { DayChange.fromPrice(102, ref) }
        XCTAssertEqual(fromCandles ?? .nan, 2, accuracy: 1e-9)
        XCTAssertNil(DayChange.choose(tickerChange: nil) { DayChange.fromPrice(200, ref) })
    }

    func testCandleQuote() {
        XCTAssertEqual(DayChange.candleQuote("usd"), "USDT")
        XCTAssertEqual(DayChange.candleQuote("FDUSD"), "USDT")
        XCTAssertEqual(DayChange.candleQuote(" eur "), "EUR")
        XCTAssertEqual(DayChange.candleQuote("BTC"), "BTC")
    }

    func testExchangeFieldsToPercent() throws {
        XCTAssertEqual(Change24h.percent(1.24), 1.24)
        XCTAssertEqual(try XCTUnwrap(Change24h.fraction(0.0068)), 0.68, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Change24h.fraction(-0.0055)), -0.55, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Change24h.fromOpen(last: 26_216, open: 25_895)), 1.2396215485615, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Change24h.fromAbsolute(last: 36_599.54, change: -105.64)),
                       -105.64 / 36_705.18 * 100, accuracy: 1e-9)
        XCTAssertNil(Change24h.percent(.nan))
        XCTAssertNil(Change24h.fromOpen(last: 100, open: -1))
        XCTAssertNil(Change24h.fromOpen(last: -1, open: 100))
        XCTAssertNil(Change24h.fromAbsolute(last: 100, change: 100))
        XCTAssertNil(Ticker().change24hPercent)
    }
}
