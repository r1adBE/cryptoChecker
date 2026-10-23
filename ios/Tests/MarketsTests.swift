import XCTest
@testable import CryptoChecker

/// Wie `BulkPairChunksTest.kt` und `KrakenAssetCodesTest.kt`.
final class BulkPairChunksTests: XCTestCase {

    private let prefix = "https://api.kraken.com/0/public/Ticker?pair="

    func testFewPairsGiveOneSortedDistinctChunk() throws {
        let chunks = try XCTUnwrap(BulkPairChunks.chunks(prefix: prefix, pairIds: ["XXBTZUSD", "XETHZEUR", "XXBTZUSD", ""]))
        XCTAssertEqual(chunks, [["XETHZEUR", "XXBTZUSD"]])
        XCTAssertEqual(BulkPairChunks.url(prefix: prefix, chunk: chunks[0]), prefix + "XETHZEUR,XXBTZUSD")
    }

    func testEmptyListIsNotFilterable() {
        XCTAssertNil(BulkPairChunks.chunks(prefix: prefix, pairIds: []))
        XCTAssertNil(BulkPairChunks.chunks(prefix: prefix, pairIds: [""]))
    }

    func testIdsThatNeedEncodingAreNotFilterable() {
        XCTAssertNil(BulkPairChunks.chunks(prefix: prefix, pairIds: ["XXBTZUSD", "BTC/USD"]))
        XCTAssertNil(BulkPairChunks.chunks(prefix: prefix, pairIds: ["A B"]))
        XCTAssertNil(BulkPairChunks.chunks(prefix: prefix, pairIds: ["A&pair=B"]))
    }

    func testBitfinexColonIdsAreAllowed() {
        let bitfinex = "https://api-pub.bitfinex.com/v2/tickers?symbols="
        XCTAssertEqual(BulkPairChunks.chunks(prefix: bitfinex, pairIds: ["tDOGE:USD", "tBTCUSD"]), [["tBTCUSD", "tDOGE:USD"]])
    }

    func testLongListsAreSplitBelowTheUrlLimit() throws {
        let ids = (0..<500).map { String(format: "PAIR%04dUSD", $0) }
        let chunks = try XCTUnwrap(BulkPairChunks.chunks(prefix: prefix, pairIds: ids))
        XCTAssertGreaterThan(chunks.count, 1)
        for chunk in chunks {
            XCTAssertLessThan(BulkPairChunks.url(prefix: prefix, chunk: chunk).count, BulkPairChunks.maxURLLength)
        }
        XCTAssertEqual(chunks.flatMap { $0 }, ids.sorted())
    }

    func testChunkFillsUpToTheLimit() {
        // Präfix 10 + "AAAA" (4) + ",BBBB" (5) = 19 < 20; ",CCCC" ergäbe 24 → neue Gruppe
        XCTAssertEqual(BulkPairChunks.chunks(prefix: "0123456789", pairIds: ["AAAA", "BBBB", "CCCC"], maxURLLength: 20),
                       [["AAAA", "BBBB"], ["CCCC"]])
    }

    func testExactlyAtTheLimitStartsANewChunk() {
        XCTAssertEqual(BulkPairChunks.chunks(prefix: "0123456789", pairIds: ["AAAA", "BBBB"], maxURLLength: 19),
                       [["AAAA"], ["BBBB"]])
    }

    func testDefaultLimitReachedExactlySplits() throws {
        // Präfix so lang, dass «AAAA,BBBB» die Adresse auf genau maxURLLength Zeichen brächte (wie Android)
        let limit = BulkPairChunks.maxURLLength
        let exact = String(repeating: "p", count: limit - 9)
        XCTAssertEqual(BulkPairChunks.chunks(prefix: exact, pairIds: ["AAAA", "BBBB"]), [["AAAA"], ["BBBB"]])
        let below = String(repeating: "p", count: limit - 10)
        let one = try XCTUnwrap(BulkPairChunks.chunks(prefix: below, pairIds: ["AAAA", "BBBB"]))
        XCTAssertEqual(one, [["AAAA", "BBBB"]])
        XCTAssertEqual(BulkPairChunks.url(prefix: below, chunk: one[0]).count, limit - 1)
    }

    func testSingleOverlongIdStaysAlone() {
        let long = String(repeating: "X", count: 50)
        XCTAssertEqual(BulkPairChunks.chunks(prefix: "0123456789", pairIds: ["AAAA", long, "ZZZZ"], maxURLLength: 30),
                       [["AAAA"], [long], ["ZZZZ"]])
    }
}

/// Kraken-Kürzel aus `wsname` und zurück. Wie `KrakenAssetCodesTest.kt`.
final class KrakenAssetCodesTests: XCTestCase {

    private func pair(_ wsName: String) -> [String]? {
        KrakenAssetCodes.fromWsName(wsName).map { [$0.base, $0.quote] }
    }

    func testWsNameGivesBaseAndQuoteWithoutLegacyPrefixes() {
        XCTAssertEqual(pair("XBT/USD"), ["BTC", "USD"])
        XCTAssertEqual(pair("ETH/EUR"), ["ETH", "EUR"])
        XCTAssertEqual(pair("XDG/USD"), ["DOGE", "USD"])
        XCTAssertEqual(pair("ETH/XBT"), ["ETH", "BTC"])
        // Kürzel, die zufällig mit X oder Z beginnen, bleiben ganz
        XCTAssertEqual(pair("XTZ/USD"), ["XTZ", "USD"])
        XCTAssertEqual(pair("ZEC/USD"), ["ZEC", "USD"])
        XCTAssertEqual(pair("ZRX/EUR"), ["ZRX", "EUR"])
    }

    func testMalformedWsNameGivesNoPair() {
        for wsName in ["", "XBTUSD", "XBT/", "/USD", "A/B/C"] { XCTAssertNil(pair(wsName), wsName) }
    }

    func testOnlyTheTwoKrakenAliasesAreMapped() {
        XCTAssertEqual(KrakenAssetCodes.common("XBT"), "BTC")
        XCTAssertEqual(KrakenAssetCodes.common("XDG"), "DOGE")
        // Frühere Einträge der alten Tabelle (XVN, ICN, NMC) gibt es bei Kraken nicht mehr
        XCTAssertEqual(KrakenAssetCodes.common("XVN"), "XVN")
        XCTAssertEqual(KrakenAssetCodes.common("XXBT"), "XXBT")
        XCTAssertEqual(KrakenAssetCodes.common("USDT"), "USDT")
    }

    func testKrakenCodeIsTheReverseForPairIds() {
        XCTAssertEqual(KrakenAssetCodes.krakenCode("BTC"), "XBT")
        XCTAssertEqual(KrakenAssetCodes.krakenCode("DOGE"), "XDG")
        XCTAssertEqual(KrakenAssetCodes.krakenCode("VEN"), "VEN")
        XCTAssertEqual(KrakenAssetCodes.krakenCode("ETH"), "ETH")
    }
}

/// Platzhalter-Börse für gespeicherte, nicht mehr bekannte Schlüssel. Wie `UnknownMarketTest.kt`.
final class UnknownMarketTests: XCTestCase {

    func testUnknownKeyGivesAnEmptyPlaceholder() {
        let market = MarketsConfig.marketOrUnknown("RemovedExchange")
        XCTAssertTrue(market is UnknownMarket)
        XCTAssertEqual(market.key, "UnknownMarket")
        // Keine Paare zum Anlegen, keine Abfrage
        XCTAssertEqual(market.currencyPairs?.isEmpty, true)
        XCTAssertEqual(market.tickerAddress(call: 0, info: PairQuery("BTC", "USD", nil, .none)), "")
    }
}

/// Binance Futures, Laufzeit-Kontrakte: Kursabfrage mit dem Symbol der Paarliste, nie aus dem Kalender
/// (der träfe nach der Abrechnung Freitag 08:00 UTC den abgelaufenen Kontrakt). Wie `BinanceFuturesUrlTest.kt`.
final class BinanceFuturesUrlTests: XCTestCase {

    func testDatedContractsUseTheExchangeSymbol() {
        let m = BinanceFutures()
        XCTAssertEqual(m.tickerAddress(call: 0, info: PairQuery("BTC", "USDT", "BTCUSDT_251226", .quarterly)),
                       "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT_251226")
        XCTAssertEqual(m.tickerAddress(call: 0, info: PairQuery("BTC", "USD", "2:BTCUSD_260327", .biquarterly)),
                       "https://dapi.binance.com/dapi/v1/ticker/24hr?symbol=BTCUSD_260327")
        XCTAssertEqual(m.tickerAddress(call: 0, info: PairQuery("BTC", "USDT", "BTCUSDT", .perpetual)),
                       "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT")
        // Ältere Kennung ohne Verfallstag: Kalender als Notbehelf
        let legacy = m.tickerAddress(call: 0, info: PairQuery("BTC", "USDT", "BTCUSDT", .quarterly))
        XCTAssertTrue(legacy.hasPrefix("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT_"), legacy)
        XCTAssertEqual(legacy.count, "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT_".count + 6)
    }

    func testDeliveryCodeOfPairId() {
        XCTAssertEqual(FuturesContractType.deliveryCode(of: "BTCUSDT_251226"), "251226")
        XCTAssertEqual(FuturesContractType.deliveryCode(of: "2:BTCUSD_260327"), "260327")
        XCTAssertNil(FuturesContractType.deliveryCode(of: "2:BTCUSD_PERP"))
        XCTAssertNil(FuturesContractType.deliveryCode(of: "BTCUSDT"))
        XCTAssertNil(FuturesContractType.deliveryCode(of: "ETHUSDT_X"))
        XCTAssertNil(FuturesContractType.deliveryCode(of: nil))
    }
}

/// Binance Futures: TradFi-Perpetuals (Aktien, Rohstoffe, Devisen, Pre-IPO) kommen als Perpetuals mit
/// Kennzeichen in die Paarliste; unbekannte Vertragsarten bleiben draussen.
final class BinanceFuturesPairsTests: XCTestCase {

    func testTradFiPerpetualsAreKeptAndMarked() throws {
        let json = try JObject(string: """
        {"symbols": [
          {"symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "PERPETUAL",
           "underlyingType": "COIN", "underlyingSubType": ["Layer-1", "Crypto"]},
          {"symbol": "AMCUSDT", "baseAsset": "AMC", "quoteAsset": "USDT", "contractType": "TRADIFI_PERPETUAL",
           "underlyingType": "EQUITY", "underlyingSubType": ["TradFi"]},
          {"symbol": "XAUUSDT", "baseAsset": "XAU", "quoteAsset": "USDT", "contractType": "TRADIFI_PERPETUAL",
           "underlyingType": "COMMODITY", "underlyingSubType": ["TradFi"]},
          {"symbol": "BTCUSDT_261225", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "CURRENT_QUARTER",
           "underlyingType": "COIN", "underlyingSubType": []},
          {"symbol": "ETHUSDT_X", "baseAsset": "ETH", "quoteAsset": "USDT", "contractType": "SOMETHING_NEW",
           "underlyingType": "COIN", "underlyingSubType": []}
        ]}
        """)
        let pairs = try BinanceFutures().fillPairList(call: 0, json: json)
        XCTAssertEqual(pairs.map(\.base), ["BTC", "AMC", "XAU", "BTC"])
        XCTAssertEqual(pairs.map(\.isTradFi), [false, true, true, false])
        XCTAssertEqual(pairs[1].contractType, .perpetual)
        XCTAssertEqual(pairs[1].pairId, "AMCUSDT")
        // Immer gesetzt, auch bei Krypto: so erkennt der Paarspeicher Listen älterer Versionen
        XCTAssertTrue(pairs.allSatisfy { $0.tradFi != nil })
    }

    /// Nur «TRADING»-Kontrakte sind wählbar (USD-M «status», COIN-M «contractStatus»). Wie `BinanceFuturesUrlTest.kt`.
    func testOnlyTradingContractsAreListed() throws {
        let usdM = """
        {"symbols": [
          {"symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "PERPETUAL", "status": "TRADING"},
          {"symbol": "BTCUSDT_250926", "baseAsset": "BTC", "quoteAsset": "USDT", "contractType": "CURRENT_QUARTER", "status": "DELIVERING"},
          {"symbol": "ETHUSDT_250926", "baseAsset": "ETH", "quoteAsset": "USDT", "contractType": "CURRENT_QUARTER", "status": "SETTLING"},
          {"symbol": "XYZUSDT", "baseAsset": "XYZ", "quoteAsset": "USDT", "contractType": "PERPETUAL", "status": "CLOSE"},
          {"symbol": "OLDUSDT", "baseAsset": "OLD", "quoteAsset": "USDT", "contractType": "PERPETUAL"}
        ]}
        """
        let pairs = try BinanceFutures().readPairList(call: 0, body: usdM)
        XCTAssertEqual(pairs.map(\.pairId), ["BTCUSDT", "OLDUSDT"])

        let coinM = """
        {"symbols": [
          {"symbol": "BTCUSD_PERP", "baseAsset": "BTC", "quoteAsset": "USD", "contractType": "PERPETUAL", "contractStatus": "TRADING"},
          {"symbol": "BTCUSD_250926", "baseAsset": "BTC", "quoteAsset": "USD", "contractType": "CURRENT_QUARTER", "contractStatus": "DELIVERED"}
        ]}
        """
        let coinPairs = try BinanceFutures().readPairList(call: 1, body: coinM)
        XCTAssertEqual(coinPairs.map(\.pairId), ["2:BTCUSD_PERP"])
    }

    /// Eine unlesbare Zeile kippt nicht die ganze Massenabfrage: nur dieses Paar als Fehler. Wie Android.
    func testMalformedBulkRowIsSkipped() throws {
        let body = """
        [
          {"symbol": "BTCUSDT", "volume": "10", "highPrice": "101", "lowPrice": "99", "lastPrice": "100", "quoteVolume": "1000", "priceChangePercent": "1.5"},
          {"symbol": "BADUSDT", "volume": "10", "highPrice": "x", "lowPrice": "1", "lastPrice": "1"},
          42,
          {"volume": "1"},
          {"symbol": "ETHUSDT", "volume": "5", "highPrice": "11", "lowPrice": "9", "lastPrice": "10"}
        ]
        """
        let parsed = try BinanceFutures().parseBulkTickersDetailed(call: 0, body: body)
        XCTAssertEqual(Set(parsed.tickers.keys), ["BTCUSDT", "ETHUSDT"])
        XCTAssertEqual(parsed.failed, ["BADUSDT"])
        XCTAssertEqual(parsed.tickers["BTCUSDT"]?.last, 100)
    }

    func testTradFiCategoryWithoutTheContractType() {
        XCTAssertTrue(TradFi.binance(contractType: "PERPETUAL", subTypes: ["Pre-IPO", "TradFi"]))
        XCTAssertFalse(TradFi.binance(contractType: "PERPETUAL", subTypes: ["Index", "Crypto"]))
        XCTAssertFalse(TradFi.binance(contractType: "PERPETUAL", subTypes: []))
    }

    /// Kennzeichen der anderen Börsen — wie `TradFiTest.kt`.
    func testOtherExchanges() {
        for t in ["stock", "ETF", "commodity", "forex"] { XCTAssertTrue(TradFi.bybit(symbolType: t), t) }
        for t in ["", "innovation", "adventure"] { XCTAssertFalse(TradFi.bybit(symbolType: t), t) }
        XCTAssertTrue(TradFi.okx(instCategory: "3"))
        XCTAssertTrue(TradFi.okx(instCategory: "4"))
        XCTAssertFalse(TradFi.okx(instCategory: "1"))
        XCTAssertFalse(TradFi.okx(instCategory: ""))
        XCTAssertTrue(TradFi.mexc(conceptPlates: ["mc-trade-zone-Stock", "mc-trade-zone-0fees", "mc-trade-zone-tradfi"], type: 2))
        XCTAssertTrue(TradFi.mexc(conceptPlates: ["mc-trade-zone-metals", "mc-trade-zone-tradfi"], type: 1))
        XCTAssertFalse(TradFi.mexc(conceptPlates: ["mc-trade-zone-mainly", "mc-trade-zone-layer2", "mc-trade-zone-pow"], type: 1))
        XCTAssertTrue(TradFi.bitget(isRwa: "YES"))
        XCTAssertFalse(TradFi.bitget(isRwa: "NO"))
        XCTAssertTrue(TradFi.htx(labels: ["stock"]))
        XCTAssertTrue(TradFi.htx(labels: ["", "commodity"]))
        XCTAssertFalse(TradFi.htx(labels: [""]))
        XCTAssertFalse(TradFi.htx(labels: [" "]))
        XCTAssertFalse(TradFi.htx(labels: []))
        XCTAssertTrue(TradFi.phemex(perpProductSubType: "TradFi"))
        XCTAssertFalse(TradFi.phemex(perpProductSubType: "Crypto"))
        XCTAssertFalse(TradFi.phemex(perpProductSubType: ""))
    }

    func testOlderCachedPairsDecodeWithoutTheFlag() throws {
        let old = Data(#"{"base":"BTC","quote":"USDT","pairId":"BTCUSDT","contractType":1}"#.utf8)
        let pair = try JSONDecoder().decode(MarketPair.self, from: old)
        XCTAssertNil(pair.tradFi)
        XCTAssertFalse(pair.isTradFi)
    }
}

/// Gruppe beim Hinzufügen — wie `AutoGroupTest.kt`.
final class AutoGroupTests: XCTestCase {

    private func pair(_ contract: FuturesContractType = .none, tradFi: Bool? = nil) -> MarketPair {
        MarketPair("X", "USDT", "XUSDT", contract, tradFi: tradFi)
    }

    func testTradFiAndDatedFuturesGetTheirOwnGroup() {
        XCTAssertEqual(AutoGroup.forPair(pair(.perpetual, tradFi: true)), "TradFi")
        XCTAssertEqual(AutoGroup.forPair(pair(.quarterly)), "QTLY")
        XCTAssertEqual(AutoGroup.forPair(pair(.biquarterly)), "QTLY")
        XCTAssertNil(AutoGroup.forPair(pair(.perpetual, tradFi: false)))
        XCTAssertNil(AutoGroup.forPair(pair(.inversePerpetual)))
        XCTAssertNil(AutoGroup.forPair(pair()))
    }

    func testChosenGroupAlwaysWins() {
        let tsla = pair(.perpetual, tradFi: true)
        XCTAssertEqual(AutoGroup.resolve(" Meine ", pair: tsla), "Meine")
        XCTAssertEqual(AutoGroup.resolve(nil, pair: tsla), "TradFi")
        XCTAssertEqual(AutoGroup.resolve("  ", pair: tsla), "TradFi")
        XCTAssertNil(AutoGroup.resolve(nil, pair: pair()))
    }
}

/// Wie `MarketNoticeGateTest.kt`.
final class MarketNoticeGateTests: XCTestCase {

    func testStoredOnlyAfterSuccessfulPost() {
        XCTAssertTrue(MarketNoticeGate.storeAfterNotify(.store, posted: false))
        XCTAssertTrue(MarketNoticeGate.storeAfterNotify(.notify, posted: true))
        XCTAssertFalse(MarketNoticeGate.storeAfterNotify(.notify, posted: false))
        XCTAssertFalse(MarketNoticeGate.storeAfterNotify(.hold, posted: true))
    }

    func testFearGreedBelow() {
        let step = MarketNoticeGate.fearGreed(value: 15, last: 30, below: 20, above: 0, canNotify: true)
        XCTAssertEqual(step.step, .notify)
        XCTAssertEqual(step.kind, .below)
        // Schon beim letzten Lauf darunter: nicht erneut melden
        XCTAssertEqual(MarketNoticeGate.fearGreed(value: 12, last: 15, below: 20, above: 0, canNotify: true).step, .store)
    }

    func testFearGreedAboveAndFirstValue() {
        let step = MarketNoticeGate.fearGreed(value: 85, last: nil, below: 20, above: 80, canNotify: true)
        XCTAssertEqual(step.step, .notify)
        XCTAssertEqual(step.kind, .above)
        let inside = MarketNoticeGate.fearGreed(value: 50, last: nil, below: 20, above: 80, canNotify: true)
        XCTAssertEqual(inside.step, .store)
        XCTAssertNil(inside.kind)
    }

    func testFearGreedHeldWithoutDeliveryAndReportedLater() {
        let held = MarketNoticeGate.fearGreed(value: 15, last: 30, below: 20, above: 0, canNotify: false)
        XCTAssertEqual(held.step, .hold)
        XCTAssertEqual(held.kind, .below)
        XCTAssertFalse(MarketNoticeGate.storeAfterNotify(held.step, posted: false))
        // Alter Wert (30) blieb liegen: beim nächsten Lauf mit Zustellung wird gemeldet
        XCTAssertEqual(MarketNoticeGate.fearGreed(value: 14, last: 30, below: 20, above: 0, canNotify: true).step, .notify)
    }

    func testThresholdsOffNeverNotify() {
        XCTAssertEqual(MarketNoticeGate.fearGreed(value: 5, last: 50, below: 0, above: 0, canNotify: true).step, .store)
    }
}

/// Wie `BitcoinHalvingTest.kt`.
final class BitcoinHalvingTests: XCTestCase {

    func testLastHalvingIsActualDate() {
        XCTAssertEqual(BitcoinHalving.info(today: LocalDay(2026, 10, 13)).lastHalving, LocalDay(2024, 4, 20))
    }

    func testNextHalvingEstimatedApril2028() {
        let next = BitcoinHalving.info(today: LocalDay(2026, 10, 13)).nextHalvingEstimate
        XCTAssertEqual(next.year, 2028)
        XCTAssertEqual(next.month, 4)
    }

    func testOnHalvingDayTheNewHalvingCounts() {
        XCTAssertEqual(BitcoinHalving.info(today: LocalDay(2020, 5, 11)).lastHalving, LocalDay(2020, 5, 11))
        XCTAssertEqual(BitcoinHalving.info(today: LocalDay(2020, 5, 10)).lastHalving, LocalDay(2016, 7, 9))
    }

    func testAfterEstimateItKeepsCounting() {
        let estimate = BitcoinHalving.info(today: LocalDay(2026, 1, 1)).nextHalvingEstimate
        let later = BitcoinHalving.info(today: estimate.plusDays(30))
        XCTAssertEqual(later.lastHalving, estimate)
        XCTAssertEqual(later.nextHalvingEstimate, estimate.plusDays(BitcoinHalving.intervalDays))
    }
}

/// Wie `BitcoinIndicatorModelTest.kt`.
final class BitcoinIndicatorModelTests: XCTestCase {

    private func inputs(price: Double = 85_000, sma200d: Double? = 100_000, sma200w: Double? = 48_200,
                        ath: Double? = 125_000, price30dAgo: Double? = 80_000, mvrv: Double? = 1.58,
                        puell: Double? = 1.03, hash30: Double? = 102, hash60: Double? = 100) -> CycleInputs {
        CycleInputs(price: price, sma200d: sma200d, sma111d: nil, sma350d: nil, price30dAgo: price30dAgo,
                    sma200w: sma200w, ath: ath, athDate: LocalDay(2025, 10, 6), mvrv: mvrv, puell: puell,
                    hash30d: hash30, hash60d: hash60, today: LocalDay(2026, 10, 13))
    }

    private func value(_ r: BitcoinReport, _ id: IndicatorId) -> Double? {
        r.indicators.first { $0.id == id }?.value ?? nil
    }

    func testPureValuesInFixedOrder() {
        let r = BitcoinIndicatorModel.evaluate(inputs())
        XCTAssertEqual(r.indicators.map(\.id), [.MAYER, .MA200W, .ATH_DISTANCE, .CHANGE_30D, .PI_CYCLE_GAP, .MVRV, .PUELL, .HASHRATE_30_60])
        XCTAssertEqual(value(r, .MAYER) ?? 0, 0.85, accuracy: 1e-9)
        XCTAssertEqual(value(r, .MA200W) ?? 0, 48_200, accuracy: 1e-9)
        XCTAssertEqual(value(r, .ATH_DISTANCE) ?? 0, 32, accuracy: 1e-9)
        XCTAssertEqual(value(r, .CHANGE_30D) ?? 0, 6.25, accuracy: 1e-9)
        XCTAssertEqual(value(r, .MVRV) ?? 0, 1.58, accuracy: 1e-9)
        XCTAssertEqual(value(r, .PUELL) ?? 0, 1.03, accuracy: 1e-9)
        XCTAssertEqual(value(r, .HASHRATE_30_60) ?? 0, 2, accuracy: 1e-9)
        XCTAssertTrue(r.onChainAvailable)
    }

    func testAboveAthDistanceIsZero() {
        XCTAssertEqual(value(BitcoinIndicatorModel.evaluate(inputs(price: 130_000)), .ATH_DISTANCE) ?? -1, 0, accuracy: 1e-9)
    }

    func testMissingDataGivesEmptyValues() {
        let r = BitcoinIndicatorModel.evaluate(inputs(sma200d: nil, sma200w: nil, ath: nil, price30dAgo: nil,
                                                      mvrv: nil, puell: nil, hash30: nil))
        XCTAssertEqual(r.indicators.count, 8)
        XCTAssertTrue(r.indicators.allSatisfy { $0.value == nil })
        XCTAssertFalse(r.onChainAvailable)
    }

    func testInvalidBaseGivesNoValue() {
        let r = BitcoinIndicatorModel.evaluate(inputs(sma200d: 0, hash60: 0))
        XCTAssertNil(value(r, .MAYER))
        XCTAssertNil(value(r, .HASHRATE_30_60))
    }

    func testPiCycleGapIsTheDistanceOfTheTwoAverages() {
        var input = inputs()
        input.sma111d = 90_000
        input.sma350d = 50_000
        // 90'000 ÷ (2 × 50'000) − 1 = −10 %
        XCTAssertEqual(value(BitcoinIndicatorModel.evaluate(input), .PI_CYCLE_GAP) ?? 0, -10, accuracy: 1e-9)
    }

    func testHistoryGivesPercentileAndFlatPriceProjection() {
        let start = LocalDay(2016, 6, 1)
        // 1000 Tage steigend von 1000 auf 2000
        let closes = (0..<1000).map { (i: Int) in (day: start.plusDays(i), price: 1000.0 + Double(i)) }
        let current: [IndicatorId: Double?] = [.MAYER: 1.0, .CHANGE_30D: 0.0]
        guard let h = IndicatorHistoryMath.compute(closes: closes, current: current, price: 2000) else {
            return XCTFail("Verlauf erwartet")
        }
        XCTAssertEqual(h.stats[.MAYER]?.percentileBelow ?? -1, 0, accuracy: 1e-9)
        XCTAssertEqual(h.stats[.CHANGE_30D]?.percentileBelow ?? -1, 0, accuracy: 1e-9)
        XCTAssertGreaterThan(h.stats[.MAYER]?.flat30 ?? 0, 1)
        XCTAssertEqual(h.since, start.plusDays(199))
        XCTAssertNil(h.stats[.PI_CYCLE_GAP])
    }

    func testPercentileNeedsAYearOfData() {
        XCTAssertNil(IndicatorHistoryMath.percentileBelow((0..<100).map(Double.init), 50))
        XCTAssertEqual(IndicatorHistoryMath.percentileBelow((0..<400).map(Double.init), 200) ?? -1, 50, accuracy: 1e-9)
    }
}

/// Wie `HalvingMathTest.kt`: nächstes Halving aus Blockhöhe und mittlerer Blockzeit, Tag in UTC.
final class HalvingMathTests: XCTestCase {
    func testNextHalvingFromBlockHeight() {
        // 2026-10-13 00:00 UTC; 630'000 + 210'000 = 840'000 … nächstes bei 1'050'000
        let now: Int64 = Int64(LocalDay(2026, 10, 13).epochDay) * 86_400_000
        guard let e = HalvingMath.estimate(tipHeight: 1_049_856, avgBlockSeconds: 600, nowMillis: now) else {
            return XCTFail("Schätzung erwartet")
        }
        XCTAssertEqual(e.height, 1_050_000)
        XCTAssertEqual(e.remainingBlocks, 144)
        // 144 Blöcke × 10 Minuten = 1 Tag
        XCTAssertEqual(e.date, LocalDay(2026, 10, 14))
    }

    func testInvalidInputGivesNoEstimate() {
        XCTAssertNil(HalvingMath.estimate(tipHeight: 0, avgBlockSeconds: 600, nowMillis: 0))
        XCTAssertNil(HalvingMath.estimate(tipHeight: 900_000, avgBlockSeconds: 30, nowMillis: 0))
        XCTAssertNil(HalvingMath.estimate(tipHeight: 900_000, avgBlockSeconds: .nan, nowMillis: 0))
    }
}

/// Wie `IndicatorAlertRulesTest.kt`.
final class IndicatorAlertRulesTests: XCTestCase {
    func testStoredIntegersBecomeThresholds() {
        XCTAssertEqual(IndicatorAlertRules.threshold(.MAYER_ABOVE, stored: 240) ?? 0, 2.4, accuracy: 1e-12)
        XCTAssertEqual(IndicatorAlertRules.threshold(.ATH_WITHIN, stored: 5) ?? 0, 5, accuracy: 1e-12)
        XCTAssertNil(IndicatorAlertRules.threshold(.MVRV_BELOW, stored: 0))
        XCTAssertEqual(Set(IndicatorAlertRules.active(["MAYER_ABOVE": 240, "MVRV_ABOVE": 0]).keys), [.MAYER_ABOVE])
    }

    func testNotifyOnceWhenCrossingRearmWhenBack() {
        let first = IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.5, threshold: 2.4, wasCrossed: false)
        XCTAssertEqual(first, .init(notify: true, crossed: true))
        let again = IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.6, threshold: 2.4, wasCrossed: true)
        XCTAssertEqual(again, .init(notify: false, crossed: true)) // erneut schreiben (samt Grenze), nicht melden
        let back = IndicatorAlertRules.decide(.MAYER_ABOVE, value: 2.3, threshold: 2.4, wasCrossed: true)
        XCTAssertEqual(back, .init(notify: false, crossed: false))
        XCTAssertNil(IndicatorAlertRules.decide(.MAYER_ABOVE, value: nil, threshold: 2.4, wasCrossed: true).crossed)
    }

    func testBelowAndDistanceToTheHigh() {
        XCTAssertTrue(IndicatorAlertRules.crossed(.MVRV_BELOW, value: 0.8, threshold: 0.8))
        XCTAssertFalse(IndicatorAlertRules.crossed(.MVRV_BELOW, value: 0.81, threshold: 0.8))
        XCTAssertTrue(IndicatorAlertRules.crossed(.ATH_WITHIN, value: 3, threshold: 5))
    }
}

/// Wie `CoinIndicatorModelTest.kt`.
final class CoinIndicatorModelTests: XCTestCase {

    func testPureValuesInFixedOrder() {
        let input = CoinInputs(price: 100, sma50d: 110, sma200d: 80, sma111d: nil, sma350d: nil, price30dAgo: 125,
                               sma200w: 50, ath: 200, athDate: nil, rsiDaily: 41.6, rsiWeekly: 58.2,
                               vsBtc90d: -12.5, historyDays: 1500)
        let r = CoinIndicatorModel.evaluate(symbol: "ETH", input: input)
        XCTAssertEqual(r.symbol, "ETH")
        XCTAssertEqual(r.indicators.map(\.id), [.MAYER, .MA200W, .ATH_DISTANCE, .CHANGE_30D, .SMA50_VS_200,
                                                 .RSI_WEEKLY, .RSI_DAILY, .VS_BTC])
        let values = r.indicators.map { $0.value ?? .nan }
        let expected: [Double] = [1.25, 50, 50, -20, 37.5, 58.2, 41.6, -12.5]
        for (v, e) in zip(values, expected) { XCTAssertEqual(v, e, accuracy: 1e-9) }
    }
}

/// User-Agent aller Netzabfragen aus der Bundle-Version — wie `HttpUserAgentTest.kt`.
final class HttpUserAgentTests: XCTestCase {
    func testVersionFromBundle() {
        XCTAssertEqual(HttpUserAgent.ios("16.2.2"), "cryptoChecker-iOS/16.2.2")
        XCTAssertEqual(HttpUserAgent.ios(" 16.2.2 "), "cryptoChecker-iOS/16.2.2")
        // Ohne lesbare Version: nur die Hauptversion (wie bisher fest)
        XCTAssertEqual(HttpUserAgent.ios(nil), "cryptoChecker-iOS/16")
        XCTAssertEqual(HttpUserAgent.ios(""), "cryptoChecker-iOS/16")
        XCTAssertEqual(HttpUserAgent.header, "User-Agent")
        XCTAssertTrue(HttpUserAgent.value.hasPrefix("cryptoChecker-iOS/"))
    }
}

/// Bitfinex-Alt-Kürzel werden übersetzt (Suche «ATOM» findet «ATOUSD»), die Kennung bleibt. Wie `BitfinexPairsTest.kt`.
final class BitfinexPairsTests: XCTestCase {

    func testLegacyCodesAreTranslated() throws {
        let body = """
        [["BTCUST","ATOUSD","DSHBTC","IOTUSD","WBTUSD","ALGUSD","QTMUSD","YYWUSD","DATUSD","MNAUSD","AMPUSD",
          "TSDUSD","BTCUDC","XCHUSD","MATIC:USD","TESTBTC:TESTUSD","BTCUSD"]]
        """
        let pairs = try Bitfinex().readPairList(call: 0, body: body)
        XCTAssertEqual(pairs.map { "\($0.base)/\($0.quote)" }, [
            "BTC/USDT", "ATOM/USD", "DASH/BTC", "IOTA/USD", "WBTC/USD", "ALGO/USD", "QTUM/USD", "YOYOW/USD",
            "DATA/USD", "MANA/USD", "AMPL/USD", "TUSD/USD", "BTC/USDC", "XCH/USD", "MATIC/USD", "BTC/USD",
        ])
        XCTAssertEqual(pairs[1].pairId, "tATOUSD")
        XCTAssertEqual(pairs[14].pairId, "tMATIC:USD")
    }
}
