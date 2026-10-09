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

    func testSingleOverlongIdStaysAlone() {
        let long = String(repeating: "X", count: 50)
        XCTAssertEqual(BulkPairChunks.chunks(prefix: "0123456789", pairIds: ["AAAA", long, "ZZZZ"], maxURLLength: 30),
                       [["AAAA"], [long], ["ZZZZ"]])
    }
}

final class KrakenAssetCodesTests: XCTestCase {

    private func n(_ code: String) -> String { KrakenAssetCodes.normalize(code) }

    func testLegacyCryptoCodesAreStripped() {
        let cases = ["XXBT": "BTC", "XETH": "ETH", "XLTC": "LTC", "XXRP": "XRP", "XXLM": "XLM", "XXMR": "XMR",
                     "XZEC": "ZEC", "XETC": "ETC", "XMLN": "MLN", "XREP": "REP", "XXDG": "DOGE"]
        for (code, expected) in cases { XCTAssertEqual(n(code), expected, code) }
    }

    func testLegacyFiatCodesAreStripped() {
        for code in ["USD", "EUR", "GBP", "CAD", "JPY", "CHF", "AUD"] { XCTAssertEqual(n("Z" + code), code) }
    }

    func testKrakenAliasesAreMapped() {
        XCTAssertEqual(n("XBT"), "BTC")
        XCTAssertEqual(n("XDG"), "DOGE")
    }

    func testOtherCodesStayUntouched() {
        for code in ["XTZ", "ZRX", "ZEC", "DOT", "ZK", "ZETA", "XCN", "USDT", "XUSD", "ZETH", "X", ""] {
            XCTAssertEqual(n(code), code, code)
        }
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
        let pairs = try BinanceFutures().parseCurrencyPairs(requestId: 0, json: json)
        XCTAssertEqual(pairs.map(\.base), ["BTC", "AMC", "XAU", "BTC"])
        XCTAssertEqual(pairs.map(\.isTradFi), [false, true, true, false])
        XCTAssertEqual(pairs[1].contractType, .perpetual)
        XCTAssertEqual(pairs[1].pairId, "AMCUSDT")
        // Immer gesetzt, auch bei Krypto: so erkennt der Paarspeicher Listen älterer Versionen
        XCTAssertTrue(pairs.allSatisfy { $0.tradFi != nil })
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
    }

    func testOlderCachedPairsDecodeWithoutTheFlag() throws {
        let old = Data(#"{"base":"BTC","quote":"USDT","pairId":"BTCUSDT","contractType":1}"#.utf8)
        let pair = try JSONDecoder().decode(CurrencyPairInfo.self, from: old)
        XCTAssertNil(pair.tradFi)
        XCTAssertFalse(pair.isTradFi)
    }
}

/// Gruppe beim Hinzufügen — wie `AutoGroupTest.kt`.
final class AutoGroupTests: XCTestCase {

    private func pair(_ contract: FuturesContractType = .none, tradFi: Bool? = nil) -> CurrencyPairInfo {
        CurrencyPairInfo("X", "USDT", "XUSDT", contract, tradFi: tradFi)
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
