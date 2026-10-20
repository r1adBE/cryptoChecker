import XCTest
@testable import CryptoChecker

/// Wie `DayChangeSourceTest.kt`: Hinweis «Veränderung aus …-Kerzen» nur bei Kerzen einer anderen Börse.
final class DayChangeSourceTests: XCTestCase {

    func testForeignOnlyForOtherExchange() {
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Kraken", "Kraken"], provider: "Binance"), "Binance")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Bitstamp"], provider: "Coinbase"), "Coinbase")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Binance"], provider: "Binance.US"), "Binance.US")
        // eigener Markt: kein Hinweis; Spot und Futures derselben Börse sind verschiedene Märkte
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Binance"], provider: "Binance"))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["BinanceFutures", "Binance Futures"], provider: "Binance Futures"))
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["BinanceFutures", "Binance Futures"], provider: "Binance"), "Binance Spot")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Binance"], provider: "Binance Futures"), "Binance Futures")
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["BinanceUS", "Binance.US"], provider: "Binance.US"))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["coinbase"], provider: "Coinbase"))
        // keine Kerzen (Ticker) oder unbekannter Anbieter
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Kraken"], provider: nil))
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Kraken"], provider: " "))
    }

    func testUsdtSeriesOfFiatPairIsAlwaysMarked() {
        // Binance BTC/EUR ohne 24-h-Wert, Pille aus Binance-USDT-Kerzen: andere Quote → Hinweis
        let name = DayChange.candleSourceName(provider: "Binance", series: .usdt)
        XCTAssertEqual(name, "Binance USDT")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["binance", "Binance"], provider: name), "Binance USDT")
        XCTAssertEqual(DayChange.foreignCandleSource(markets: ["Binance"],
                                                     provider: DayChange.candleSourceName(provider: " Kraken ", series: .usdt)),
                       "Kraken USDT")
        // eigene Reihe: wie bisher nur bei fremder Börse
        XCTAssertNil(DayChange.foreignCandleSource(markets: ["Binance"],
                                                   provider: DayChange.candleSourceName(provider: "Binance", series: .pair)))
        XCTAssertNil(DayChange.candleSourceName(provider: "Binance", series: DayChange.CandleSeries.none))
        XCTAssertNil(DayChange.candleSourceName(provider: nil, series: .usdt))
        XCTAssertNil(DayChange.candleSourceName(provider: " ", series: .usdt))
    }

    func testSeriesFollowsSelectOrder() {
        let ref = DayReference(open: 100, lastClose: 110)
        XCTAssertEqual(DayChange.series(pairReference: ref, usdtReference: ref, quoteIsFiat: true), .pair)
        XCTAssertEqual(DayChange.series(pairReference: nil, usdtReference: ref, quoteIsFiat: true), .usdt)
        XCTAssertEqual(DayChange.series(pairReference: nil, usdtReference: ref, quoteIsFiat: false), DayChange.CandleSeries.none)
        XCTAssertEqual(DayChange.series(pairReference: nil, usdtReference: nil, quoteIsFiat: true), DayChange.CandleSeries.none)
    }

    func testUsdtSeriesPriceGap() {
        let usdt = DayReference(open: 50, lastClose: 60)
        // CHF je USD 0.9 → letzter Schluss 54 CHF
        XCTAssertEqual(DayChange.fromUsdtSeries(54, usdt, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9) ?? .nan, 20, accuracy: 1e-9)
        XCTAssertNil(DayChange.fromUsdtSeries(70.2, usdt, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9))
        XCTAssertNil(DayChange.fromUsdtSeries(37.8, usdt, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9))
        // Wechselkursbewegung zählt mit: 60 × 0.9 gegen 50 × 1.0 = +8 %
        XCTAssertEqual(DayChange.fromUsdtSeries(54, usdt, usdToQuote: 0.9, usdToQuoteAtOpen: 1.0) ?? .nan, 8, accuracy: 1e-9)
        // ohne Kurs an einem Ende: unbekannt, nie der USDT-Prozentwert
        XCTAssertNil(DayChange.fromUsdtSeries(5000, usdt, usdToQuote: nil, usdToQuoteAtOpen: 0.9))
        XCTAssertNil(DayChange.fromUsdtSeries(54, usdt, usdToQuote: 0.9, usdToQuoteAtOpen: nil))
        XCTAssertNil(DayChange.fromUsdtSeries(5000, usdt, usdToQuote: .nan, usdToQuoteAtOpen: 0.9))
        XCTAssertNil(DayChange.fromUsdtSeries(54, nil, usdToQuote: 0.9, usdToQuoteAtOpen: 0.9))
        XCTAssertNil(DayChange.select(price: 70.2, pairReference: nil, usdtReference: usdt, quoteIsFiat: true,
                                      usdToQuote: 0.9, usdToQuoteAtOpen: 0.9))
    }

    func testRowSourceNamesChartEvenWhenChangeComesFromAnotherExchange() {
        // Phemex-Paar: nur der Chart fällt auf Binance zurück
        XCTAssertEqual(DayChange.rowSource(change: nil, chart: "Binance"), DayChange.RowSource(kind: .chart, provider: "Binance"))
        XCTAssertEqual(DayChange.rowSource(change: "Binance", chart: "Binance"),
                       DayChange.RowSource(kind: .changeAndChart, provider: "Binance"))
        // Änderung und Chart von zwei verschiedenen fremden Börsen: der Chart wird nicht verschwiegen
        XCTAssertEqual(DayChange.rowSource(change: "Coinbase", chart: "Binance"),
                       DayChange.RowSource(kind: .change, provider: "Coinbase", chartProvider: "Binance"))
        XCTAssertEqual(DayChange.rowSource(change: "Coinbase", chart: nil), DayChange.RowSource(kind: .change, provider: "Coinbase"))
        XCTAssertNil(DayChange.rowSource(change: nil, chart: nil))
    }

    /// Gemeinsame Fälle mit Android (`DayChangeSourceTest.rowChangeMarkerOnlyForShownForeignCandleValue`).
    func testRowChangeMarker() throws {
        let cases = TestSupport.list(try TestSupport.fixture("day_change")["rowChangeMarker"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let actual = DayChange.rowChangeMarker(foreignSource: c["foreignSource"] as? String,
                                                   shownChange: TestSupport.number(c["shownChange"]),
                                                   sinceLast: c["sinceLast"] as? Bool ?? false)
            XCTAssertEqual(actual, c["expected"] as? String, "rowChangeMarker: \(c["name"] ?? "")")
        }
    }

    /// Gemeinsame Fälle mit Android (`DayChangeSourceTest.rowMarkerLabelIsShortExchangeName`).
    func testRowMarkerLabel() throws {
        let cases = TestSupport.list(try TestSupport.fixture("day_change")["rowMarkerLabel"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(DayChange.rowMarkerLabel(c["provider"] as? String ?? ""), c["expected"] as? String,
                           "rowMarkerLabel: \(c["name"] ?? "")")
        }
    }
}
