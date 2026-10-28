import XCTest
@testable import CryptoChecker

/// Stundenkerzen der eigenen Börse (Mini-Chart, 24-h-Bezug) — wie `OwnHourlyTest.kt`.
final class OwnHourlyTests: XCTestCase {
    private let hour: Int64 = 3_600_000

    private func candles(_ n: Int) -> [MarketCandle] {
        (0..<n).map { i in
            let d = Double(i)
            return MarketCandle(openTime: Int64(i) * hour, open: 100 + d, high: 101 + d, low: 99 + d, close: 100.5 + d, volume: 1)
        }
    }

    private func at(_ hours: Int...) -> [MarketCandle] {
        hours.map { h in
            let d = Double(h)
            return MarketCandle(openTime: Int64(h) * hour, open: 100 + d, high: 101 + d, low: 99 + d, close: 100.5 + d, volume: 1)
        }
    }

    func testSeriesKeepsNewest24ClosesAndAllOpens() throws {
        let s = try XCTUnwrap(OwnHourly.series(candles(26), provider: "Kraken", now: 26 * hour))
        XCTAssertEqual(s.closes.count, 24)
        XCTAssertEqual(s.closes.first ?? 0, 102.5, accuracy: 1e-9)
        XCTAssertEqual(s.closes.last ?? 0, 125.5, accuracy: 1e-9)
        // Bezug: Schluss der Kerze vor dem Fenster (1 h–2 h)
        XCTAssertEqual(s.reference, DayReference(open: 101.5, lastClose: 125.5))
        XCTAssertEqual(s.lastClose, 125.5, accuracy: 1e-9)
        XCTAssertEqual(s.opens.count, 26)
        XCTAssertEqual(s.opens[0] ?? 0, 100, accuracy: 1e-9)
        XCTAssertEqual(s.provider, "Kraken")
    }

    func testSeriesNeedsTwoValidCandles() {
        XCTAssertNil(OwnHourly.series(nil, provider: "Kraken", now: 26 * hour))
        XCTAssertNil(OwnHourly.series(candles(1), provider: "Kraken", now: 26 * hour))
        var bad = candles(2)
        bad[0] = MarketCandle(openTime: 0, open: 100, high: 101, low: 99, close: .nan, volume: 1)
        XCTAssertNil(OwnHourly.series(bad, provider: "Kraken", now: 26 * hour))
    }

    func testRollingReferenceSpans24HoursNot24Candles() throws {
        // Nur Stunden mit Handel (Upbit): alte Kerzen 0–2 h, dann erst wieder 40–41 h; jetzt 42 h
        let gap = try XCTUnwrap(OwnHourly.series(at(0, 1, 2, 40, 41), provider: "Upbit", now: 42 * hour))
        XCTAssertEqual(gap.reference, DayReference(open: 102.5, lastClose: 141.5))
        XCTAssertEqual(gap.closes.count, 5)
        // Laufende Stunde: Fenster ab 18.5 h, Kerze ab 18 h liegt davor
        XCTAssertEqual(OwnHourly.series(at(17, 18, 19, 41), provider: "Upbit", now: 42 * hour + hour / 2)?.reference,
                       DayReference(open: 118.5, lastClose: 141.5))
        // Keine Kerze vor dem Fenster, erste startet höchstens 1 h nach Fensterbeginn: ihre Eröffnung
        XCTAssertEqual(OwnHourly.series(at(27, 30, 35), provider: "Upbit", now: 50 * hour + hour / 2)?.reference,
                       DayReference(open: 127, lastClose: 135.5))
        // Erste Kerze erst 4 h nach Fensterbeginn: kein Bezug, Chart und letzter Schluss bleiben
        let late = try XCTUnwrap(OwnHourly.series(at(30, 31, 35), provider: "Upbit", now: 50 * hour))
        XCTAssertNil(late.reference)
        XCTAssertEqual(late.closes.count, 3)
        XCTAssertEqual(late.lastClose, 135.5, accuracy: 1e-9)
        // Kein Handel seit über 24 h: letzter Kurs gilt an beiden Enden (0 %)
        XCTAssertEqual(OwnHourly.series(at(0, 5), provider: "Upbit", now: 100 * hour)?.reference,
                       DayReference(open: 105.5, lastClose: 105.5))
        // Kerze genau am Fensterbeginn gehört ins Fenster
        XCTAssertEqual(OwnHourly.rollingReference(at(1, 2, 24), now: 26 * hour), DayReference(open: 101.5, lastClose: 124.5))
    }

    func testFreshTtlFailureTtlAndDayStartHour() throws {
        let s = try XCTUnwrap(OwnHourly.series(candles(26), provider: "OKX", now: 26 * hour))
        let ttl = OwnHourly.ttlMillis
        XCTAssertTrue(OwnHourly.isFresh(time: 1_000, series: s, ttl: ttl, now: 1_000 + ttl - 1))
        XCTAssertFalse(OwnHourly.isFresh(time: 1_000, series: s, ttl: ttl, now: 1_000 + ttl))
        XCTAssertFalse(OwnHourly.isFresh(time: 1_000, series: s, ttl: ttl, now: 999))
        // Fehlschlag: kürzer gemerkt
        XCTAssertTrue(OwnHourly.isFresh(time: 0, series: nil, ttl: ttl, now: OwnHourly.failureTtlMillis - 1))
        XCTAssertFalse(OwnHourly.isFresh(time: 0, series: nil, ttl: ttl, now: OwnHourly.failureTtlMillis))
        // Kerze des Tagesbeginns fehlt, Abruf kurz nach Beginn der Stunde: neu laden
        let missing = 30 * hour
        XCTAssertFalse(OwnHourly.isFresh(time: missing + 60_000, series: s, ttl: ttl, now: missing + 120_000, needsHour: missing))
        // Viel später abgerufen und trotzdem ohne sie: die Quelle liefert sie nicht — gilt bis zum Ablauf
        let late = missing + OwnHourly.newCandleGraceMillis
        XCTAssertTrue(OwnHourly.isFresh(time: late, series: s, ttl: ttl, now: late + 1_000, needsHour: missing))
        // Enthalten: frisch
        XCTAssertTrue(OwnHourly.isFresh(time: 25 * hour, series: s, ttl: ttl, now: 25 * hour + 1_000, needsHour: 3 * hour))
    }

    func testCacheKeySeparatesExchangesAndContracts() {
        let spot = CandlePair(exchangeKey: "Kraken", exchangeName: "Kraken", base: "BTC", quote: "USD", pairId: "XXBTZUSD")
        let other = CandlePair(exchangeKey: "Bitstamp", exchangeName: "Bitstamp", base: "BTC", quote: "USD", pairId: "btcusd")
        XCTAssertNotEqual(spot.cacheKey, other.cacheKey)
        XCTAssertEqual(CandlePair(exchangeKey: "Coinbase", exchangeName: "Coinbase", base: "btc", quote: "usd").cacheKey,
                       "Coinbase|BTC/USD|NONE")
    }

    func testHostOfCandleUrl() {
        XCTAssertEqual(OwnCandleDataSource.hostOf("https://api.kraken.com/0/public/OHLC?pair=XXBTZUSD"), "api.kraken.com")
        XCTAssertEqual(OwnCandleDataSource.hostOf("https://www.okx.com/api/v5/market/candles?instId=BTC-USDT"), "www.okx.com")
    }
}
