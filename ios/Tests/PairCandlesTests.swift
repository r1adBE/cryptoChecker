import XCTest
@testable import CryptoChecker

/// Kerzen für Volumen-Alarm und «Warum?» wie der Chart (eigene Börse, Ausweich-Kette nur bei gleichem
/// Token, nie für Laufzeit-Kontrakte) und eigene Kerzen der Binance-Quartals-Kontrakte.
/// Gegenstück: `PairCandlesTest.kt`.
final class PairCandlesTests: XCTestCase {

    private final class Counter: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var values: [String] = []
        func add(_ v: String) { lock.lock(); values.append(v); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return values }
        var count: Int { all.count }
        func clear() { lock.lock(); values.removeAll(); lock.unlock() }
    }

    private func candle(_ close: Double, _ t: Int64 = 0) -> MarketCandle {
        MarketCandle(openTime: t, open: close, high: close, low: close, close: close, volume: 1)
    }

    private var series: [MarketCandle] { [candle(100, 0), candle(101, 3_600_000)] }

    private func pair(_ contract: FuturesContractType = .none, _ exchangeKey: String = "Kraken", pairId: String? = nil,
                      tradFi: Bool = false, quote: String = "USDT", base: String = "BTC") -> CandlePair {
        CandlePair(exchangeKey: exchangeKey, exchangeName: exchangeKey, base: base, quote: quote, pairId: pairId,
                   contractType: contract, tradFi: tradFi)
    }

    /// Ausweich-Kette mit Reihen `rows` nacheinander (Name der Quelle je Reihe: `names`); gibt die erste akzeptierte
    /// zurück. Wie CandleDataSource: eine Quelle mit Namen nur ohne klaren Widerspruch (`TokenIdentity.compatible`).
    /// `calls` merkt je Aufruf den übergebenen Namen («-» = keiner).
    private func chain(_ rows: [[MarketCandle]], _ calls: Counter, names: [String?] = [], base: String = "BTC")
        -> (String?, @escaping @Sendable ([MarketCandle]) async -> Bool) async -> Sourced<[MarketCandle]>? {
        { name, accept in
            calls.add(name ?? "-")
            for (i, row) in rows.enumerated() {
                let sourceName: String? = i < names.count ? names[i] : nil
                if let name, !TokenIdentity.compatible(symbol: base, name, sourceName) { continue }
                if await accept(row) { return Sourced(value: row, provider: "Binance") }
            }
            return nil
        }
    }

    func testOwnCandlesFirst() async {
        let calls = Counter()
        let own = Sourced(value: series, provider: "Kraken")
        let result = await VolumeDataSource.pairCandlesOf(pair(), price: 101, identityName: nil, ownSupported: true,
                                                          own: { own }, chain: chain([series], calls))
        XCTAssertEqual(result?.provider, "Kraken")
        XCTAssertEqual(calls.count, 0)
    }

    func testFallbackOnlyWithSameToken() async {
        // Erste Quelle: fremder Token gleichen Kürzels (Kurs 10× daneben) → nächste Quelle
        let foreign = [candle(10), candle(10.1, 3_600_000)]
        let calls = Counter()
        let result = await VolumeDataSource.pairCandlesOf(pair(), price: 120, identityName: nil, ownSupported: true,
                                                          own: { nil }, chain: chain([foreign, series], calls))
        XCTAssertEqual(result?.value, series)
        XCTAssertEqual(result?.provider, "Binance")
        // Kein Kurs: keine Ausweich-Reihe, die Kette wird gar nicht gefragt
        let noPrice = Counter()
        let none = await VolumeDataSource.pairCandlesOf(pair(), price: nil, identityName: nil, ownSupported: false,
                                                        own: { nil }, chain: chain([series], noPrice))
        XCTAssertNil(none)
        XCTAssertEqual(noPrice.count, 0)
        // Alle Reihen fremd → nil
        let allForeign = await VolumeDataSource.pairCandlesOf(pair(), price: 101, identityName: nil, ownSupported: false,
                                                              own: { nil }, chain: chain([foreign], Counter()))
        XCTAssertNil(allForeign)
    }

    func testFallbackRejectsNameMismatchLikeChart() async {
        // «Warum?»/Volumen wie der Chart: an der Börse «Simon's Cat», Binance führt «Catcoin» — Kurs im Band, trotzdem nie
        let cat = pair(base: "CAT")
        let foreign = Counter()
        let rejected = await VolumeDataSource.pairCandlesOf(cat, price: 101, identityName: "Simon's Cat", ownSupported: false,
                                                            own: { nil },
                                                            chain: chain([series], foreign, names: ["Catcoin"], base: "CAT"))
        XCTAssertNil(rejected)
        XCTAssertEqual(foreign.all, ["Simon's Cat"])
        // Gleicher Token (andere Schreibweise): wie bisher mit Kursband
        let same = await VolumeDataSource.pairCandlesOf(cat, price: 101, identityName: "Simon's Cat", ownSupported: false,
                                                        own: { nil },
                                                        chain: chain([series], Counter(), names: ["Simons Cat"], base: "CAT"))
        XCTAssertEqual(same?.value, series)
        // Name unbekannt: nur Kürzel + Kursband
        let unknown = await VolumeDataSource.pairCandlesOf(cat, price: 101, identityName: nil, ownSupported: false,
                                                           own: { nil },
                                                           chain: chain([series], Counter(), names: ["Catcoin"], base: "CAT"))
        XCTAssertEqual(unknown?.value, series)
    }

    func testNoFallbackForDatedTradFiDex() async {
        let pairs = [
            pair(.quarterly, "BinanceFutures"),
            pair(.biquarterly, "BinanceFutures", pairId: "2:BTCUSD_251226", quote: "USD"),
            pair(.perpetual, "BinanceFutures", tradFi: true),
            pair(.none, "DexScreener"),
        ]
        for p in pairs {
            let calls = Counter()
            let result = await VolumeDataSource.pairCandlesOf(p, price: 101, identityName: nil, ownSupported: false,
                                                              own: { nil }, chain: chain([series], calls))
            XCTAssertNil(result, "\(p)")
            XCTAssertEqual(calls.count, 0)
            XCTAssertFalse(OwnCandlePolicy.allowsFallback(p))
        }
        // Perpetual darf weiter auf die Kette (mit Prüfung)
        XCTAssertTrue(OwnCandlePolicy.allowsFallback(pair(.perpetual, "BinanceFutures")))
        XCTAssertTrue(OwnCandlePolicy.allowsFallback(pair(.inversePerpetual, "BybitFutures")))
    }

    func testAcceptsFallbackTolerance() {
        XCTAssertTrue(OwnCandlePolicy.acceptsFallback(series, price: 101))
        XCTAssertTrue(OwnCandlePolicy.acceptsFallback(series, price: 126))
        XCTAssertFalse(OwnCandlePolicy.acceptsFallback(series, price: 127))
        XCTAssertTrue(OwnCandlePolicy.acceptsFallback(series, price: 76))
        XCTAssertFalse(OwnCandlePolicy.acceptsFallback(series, price: 75))
        XCTAssertFalse(OwnCandlePolicy.acceptsFallback(series, price: nil))
        XCTAssertFalse(OwnCandlePolicy.acceptsFallback(series, price: 0))
        XCTAssertFalse(OwnCandlePolicy.acceptsFallback([], price: 101))
    }

    func testBinanceQuarterlyUsesOwnContractCandles() async throws {
        let code = try XCTUnwrap(BinanceFuturesCandles.deliveryCode(.quarterly))
        XCTAssertEqual(code.count, 6)
        XCTAssertNil(BinanceFuturesCandles.deliveryCode(.perpetual))
        let body = "[[1760400000000, \"62000\", \"62100\", \"61900\", \"62050\", \"10\", 1760403599999, \"620500\"],"
            + "[1760403600000, \"62050\", \"62200\", \"62000\", \"62150\", \"12\", 1760407199999, \"745800\"]]"
        let urls = Counter()
        let http = CandleHTTP(get: { url in urls.add(url); return body })
        // USDⓈ-M: Symbol der Börse aus der Paarliste, nie aus dem Kalender (Abrechnung Freitag 08:00 UTC)
        let usdM = pair(.quarterly, "BinanceFutures", pairId: "BTCUSDT_251226")
        XCTAssertTrue(ExchangeCandleRegistry.source(for: usdM) is BinanceFuturesCandles)
        let candles = await BinanceFuturesCandles().fetch(usdM, interval: .h1, limit: 2, http: http)
        XCTAssertEqual(urls.all, ["https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT_251226&interval=1h&limit=2"])
        XCTAssertEqual(candles?.count, 2)
        // COIN-M: dapi mit dem gespeicherten BTCUSD_<Verfallstag>, nie BTCUSD_PERP
        urls.clear()
        let coinM = pair(.quarterly, "BinanceFutures", pairId: "2:BTCUSD_250926", quote: "USD")
        _ = await BinanceFuturesCandles().fetch(coinM, interval: .d1, limit: 2, http: http)
        XCTAssertEqual(urls.all, ["https://dapi.binance.com/dapi/v1/klines?symbol=BTCUSD_250926&interval=1d&limit=2"])
        // Zweites Quartal: eigenes Symbol
        let nextId = BinanceFuturesCandles.id(pair(.biquarterly, "BinanceFutures", pairId: "BTCUSDT_260327"))
        XCTAssertEqual(nextId?.symbol, "BTCUSDT_260327")
        XCTAssertEqual(nextId?.coinM, false)
        // Ältere Kennung ohne Verfallstag: Kalender als Notbehelf
        let next = try XCTUnwrap(BinanceFuturesCandles.deliveryCode(.biquarterly))
        XCTAssertGreaterThan(next, code)
        XCTAssertEqual(BinanceFuturesCandles.id(pair(.quarterly, "BinanceFutures", pairId: "BTCUSDT"))?.symbol, "BTCUSDT_\(code)")
        let legacyCoinM = BinanceFuturesCandles.id(pair(.biquarterly, "BinanceFutures", pairId: "2:BTCUSD", quote: "USD"))
        XCTAssertEqual(legacyCoinM?.symbol, "BTCUSD_\(next)")
        XCTAssertEqual(legacyCoinM?.coinM, true)
        // Perpetual unverändert
        let perp = BinanceFuturesCandles.id(pair(.perpetual, "BinanceFutures", pairId: "2:BTCUSD_PERP", quote: "USD"))
        XCTAssertEqual(perp?.symbol, "BTCUSD_PERP")
        XCTAssertEqual(perp?.coinM, true)
        // Andere Börse mit Laufzeit-Kontrakt: keine eigene Quelle → kein Chart, keine Ausweich-Reihe
        let okxDated = pair(.quarterly, "OkexFutures", pairId: "BTC-USDT-251226")
        XCTAssertNil(ExchangeCandleRegistry.source(for: okxDated))
        XCTAssertFalse(OwnCandlePolicy.allowsFallback(okxDated))
    }
}
