import XCTest
@testable import CryptoChecker

/// Lighter-Paarliste aus zwei gemeinsam gelesenen Antworten (`orderBooks` + `tokenlist`) und die Namen daraus.
/// Kombinierte Paarlisten sind keine Fallart der Börsen-Fixtures (wie LATOKEN), darum hier. Zeilen gekürzt nach
/// echten Antworten vom 19.10.2026. Wie `LighterPairListTest.kt`.
final class LighterPairListTests: XCTestCase {

    private let orderBooks = """
    {"code":200,"order_books":[
        {"symbol":"BTC","market_id":1,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"COIN","market_id":110,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"ETH/USDC","market_id":2048,"market_type":"spot","status":"active","is_frozen":false},
        {"symbol":"NEWX","market_id":300,"market_type":"perp","status":"active","is_frozen":false},
        {"symbol":"FOGO","market_id":124,"market_type":"perp","status":"inactive","is_frozen":false},
        {"symbol":"MRNA","market_id":228,"market_type":"perp","status":"active","is_frozen":true}
    ]}
    """

    private let tokenList = """
    {"tokens":[
        {"symbol":"BTC","name":"Bitcoin","gecko_id":"bitcoin","market":"PERPS","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"COIN","name":"Coinbase","gecko_id":"","market":"PERPS","asset_type":"RWA","categories":["STOCK"]},
        {"symbol":"ETH","name":"Ethereum","gecko_id":"ethereum","market":"PERPS","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"ETH/USDC","name":"Ethereum","gecko_id":"ethereum","market":"SPOT","asset_type":"CRYPTO","categories":["MAJOR","LAYER_1"]},
        {"symbol":"META","name":"Meta","gecko_id":"","market":"PERPS","asset_type":"RWA","categories":["STOCK"]},
        {"symbol":"AAVE/USDC","name":"Aave","gecko_id":"aave","market":"SPOT","asset_type":"CRYPTO","categories":["DEFI"]},
        {"symbol":"EMPTY","name":" ","market":"PERPS","asset_type":"CRYPTO"}
    ]}
    """

    private func pairs(_ bodies: [String]) throws -> [String: MarketPair] {
        var byId: [String: MarketPair] = [:]
        for pair in try Lighter().readPairListCombined(bodies: bodies) { byId[pair.pairId ?? ""] = pair }
        return byId
    }

    func testCombinedListMarksRwaAsTradFi() throws {
        let byId = try pairs([orderBooks, tokenList])
        XCTAssertEqual(Set(byId.keys), ["BTC", "COIN", "ETH/USDC", "NEWX"])
        let btc = try XCTUnwrap(byId["BTC"])
        XCTAssertEqual(btc.quote, "USDC")
        XCTAssertEqual(btc.contractType, .perpetual)
        XCTAssertFalse(btc.isTradFi)
        XCTAssertTrue(try XCTUnwrap(byId["COIN"]).isTradFi)
        let eth = try XCTUnwrap(byId["ETH/USDC"])
        XCTAssertEqual(eth.base, "ETH")
        XCTAssertEqual(eth.quote, "USDC")
        XCTAssertEqual(eth.contractType, FuturesContractType.none)
        XCTAssertFalse(eth.isTradFi)
        // ohne tokenlist-Eintrag (neu gelistet): unmarkiert
        XCTAssertFalse(try XCTUnwrap(byId["NEWX"]).isTradFi)
    }

    func testUnreadableTokenListKeepsPairsUnmarked() throws {
        for broken in ["", "<html>502</html>", #"{"code":200}"#] {
            let byId = try pairs([orderBooks, broken])
            XCTAssertEqual(Set(byId.keys), ["BTC", "COIN", "ETH/USDC", "NEWX"])
            XCTAssertFalse(byId.values.contains { $0.isTradFi })
        }
    }

    func testNamesPerBaseFromTokenList() throws {
        XCTAssertEqual(try Lighter().parseBaseNamesCombined(bodies: [orderBooks, tokenList]),
                       ["BTC": "Bitcoin", "COIN": "Coinbase", "ETH": "Ethereum", "META": "Meta", "AAVE": "Aave"])
    }

    func testTradFiHelper() {
        XCTAssertTrue(TradFi.lighter(assetType: "RWA"))
        XCTAssertTrue(TradFi.lighter(assetType: "rwa"))
        XCTAssertFalse(TradFi.lighter(assetType: "CRYPTO"))
        XCTAssertFalse(TradFi.lighter(assetType: ""))
    }
}
