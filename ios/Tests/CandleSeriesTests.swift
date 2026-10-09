import XCTest
@testable import CryptoChecker

/// Wie `CandleSeriesTest.kt` (Prüfung der Kerzenreihen; ohne den «Warum»-Teil).
final class CandleSeriesTests: XCTestCase {

    private let h = ActivityAnalyzer.hourMillis
    private var t0: Int64 { 1_700_000_000_000 - 1_700_000_000_000 % ActivityAnalyzer.hourMillis }
    /// Mitten in der laufenden (27.) Stunde der Reihe ab `t0`.
    private var now: Int64 { t0 + 26 * h + 30 * 60_000 }

    /// 27 Stundenkerzen ab `start`; Schluss steigt je Stunde um `step`, Umsatz `volume`.
    private func series(start: Int64? = nil, step: Double = 0.1, volume: Double = 100) -> [MarketCandle] {
        let first = start ?? t0
        return (0..<27).map { i in
            let close = 100 + Double(i) * step
            return MarketCandle(openTime: first + Int64(i) * h, open: close - step, high: close, low: close - step,
                                close: close, volume: volume)
        }
    }

    func testFreshSeriesIsOk() {
        XCTAssertEqual(CandleSeries.status(series(), now: now, tickerChange24h: 2), .ok)
        XCTAssertEqual(CandleSeries.status(series(), now: now, tickerChange24h: nil), .ok)
    }

    func testMissingOrTooShort() {
        XCTAssertEqual(CandleSeries.status(nil, now: now, tickerChange24h: nil), .missing)
        XCTAssertEqual(CandleSeries.status([], now: now, tickerChange24h: nil), .missing)
        XCTAssertEqual(CandleSeries.status(Array(series().prefix(1)), now: now, tickerChange24h: nil), .missing)
    }

    func testStaleAfterTwoHours() {
        let lastOpen = series().last!.openTime
        XCTAssertEqual(CandleSeries.status(series(), now: lastOpen + 2 * h, tickerChange24h: nil), .ok)
        XCTAssertEqual(CandleSeries.status(series(), now: lastOpen + 2 * h + 60_000, tickerChange24h: nil), .stale)
        // Delisteter Spot-Markt: Kerzen enden Monate vor «jetzt»
        let delisted = series(start: t0 - 400 * 24 * h)
        XCTAssertEqual(CandleSeries.status(delisted, now: now, tickerChange24h: 4.77), .stale)
        XCTAssertNil(CandleSeries.usable(delisted, now: now, tickerChange24h: 4.77))
    }

    func testFlatWhileTickerMoves() {
        let flat = series(step: 0)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: 4.77), .flat)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: -0.5), .flat)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: 0.01), .ok)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: nil), .ok)
        XCTAssertEqual(CandleSeries.status(flat, now: now, tickerChange24h: .nan), .ok)
    }

    func testNoVolumeAtAll() {
        XCTAssertEqual(CandleSeries.status(series(volume: 0), now: now, tickerChange24h: nil), .noVolume)
    }

    func testUsableReturnsTheSameCandles() {
        let candles = series()
        XCTAssertEqual(CandleSeries.usable(candles, now: now, tickerChange24h: 1), candles)
    }

    func testIsFlatTolerance() {
        XCTAssertTrue(CandleSeries.isFlat(series(step: 0)))
        XCTAssertFalse(CandleSeries.isFlat(series(step: 0.001)))
        XCTAssertFalse(CandleSeries.isFlat([]))
    }

    func testIsLivePerInterval() {
        let candles = series()
        let lastOpen = candles.last!.openTime
        XCTAssertTrue(CandleSeries.isLive(candles, intervalMillis: h, now: lastOpen + 24 * h))
        XCTAssertFalse(CandleSeries.isLive(candles, intervalMillis: h, now: lastOpen + 24 * h + 1))
        XCTAssertTrue(CandleSeries.isLive(candles, intervalMillis: 7 * 24 * h, now: lastOpen + 27 * 24 * h))
        XCTAssertFalse(CandleSeries.isLive(candles, intervalMillis: 7 * 24 * h, now: lastOpen + 29 * 24 * h))
        XCTAssertFalse(CandleSeries.isLive(nil, intervalMillis: h, now: now))
        XCTAssertFalse(CandleSeries.isLive([], intervalMillis: h, now: now))
    }

    func testChange24hPrefersTicker() {
        XCTAssertEqual(CandleSeries.change24h(ticker: 4.77, candles: 0), 4.77)
        XCTAssertEqual(CandleSeries.change24h(ticker: nil, candles: 1.5), 1.5)
        XCTAssertEqual(CandleSeries.change24h(ticker: .nan, candles: 1.5), 1.5)
        XCTAssertNil(CandleSeries.change24h(ticker: nil, candles: nil))
    }

    private struct W {
        let base: String
        let quote: String
        let spot: Bool
        let perp: Bool
        let id: Int
    }

    private func pick(_ items: [W], _ symbol: String) -> Int? {
        CandleSeries.pickWatch(items, symbol: symbol, base: { $0.base }, quote: { $0.quote },
                               isSpot: { $0.spot }, isPerpetual: { $0.perp })?.id
    }

    func testPickWatchSpotFirstThenPerpetual() {
        let perp = W(base: "XMR", quote: "USDT", spot: false, perp: true, id: 1)
        let spotEur = W(base: "XMR", quote: "EUR", spot: true, perp: false, id: 2)
        let spotUsdt = W(base: "xmr", quote: "USDT", spot: true, perp: false, id: 3)
        let quarterly = W(base: "XMR", quote: "USDT", spot: false, perp: false, id: 4)
        XCTAssertEqual(pick([perp, spotEur, spotUsdt, quarterly], "XMR"), 3)
        XCTAssertEqual(pick([perp, spotEur, quarterly], "XMR"), 2)
        XCTAssertEqual(pick([quarterly, perp], "xmr"), 1)
        XCTAssertEqual(pick([quarterly], "XMR"), 4)
        XCTAssertNil(pick([perp], "BTC"))
    }

    func testCoinbaseOlderWindowOnlyForFullPageAndMissingCandles() {
        let day = 24 * h
        let first = t0
        // 1 Jahr Tageskerzen: 300 geladen, 65 fehlen → Fenster mit 65 Kerzen direkt davor
        let window = CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 365, granularityMillis: day)
        XCTAssertEqual(window?.upperBound, first - day)
        XCTAssertEqual(window?.lowerBound, first - 65 * day)
        // Höchstens eine Seite
        XCTAssertEqual(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 1000,
                                                        granularityMillis: day)?.lowerBound, first - 300 * day)
        // Seite nicht voll (Paar jünger) oder genug Kerzen: keine weitere Anfrage
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 120, needed: 365, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 300, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 30, granularityMillis: day))
        XCTAssertNil(CandleSeries.coinbaseOlderWindow(firstOpen: first, fetched: 300, needed: 365, granularityMillis: 0))
    }

    func testMergeAscendingSortsAndDropsDuplicates() {
        let newer = Array(series().prefix(3))
        let older = Array(series(start: t0 - 2 * h).prefix(3)) // überlappt in t0 und t0 + h
        let merged = CandleSeries.mergeAscending(older: older, newer: newer)
        XCTAssertEqual(merged.map(\.openTime), [t0 - 2 * h, t0 - h, t0, t0 + h, t0 + 2 * h])
        // Bei gleichem Beginn gewinnt die neuere Seite
        XCTAssertEqual(merged[2], newer[0])
    }
}
