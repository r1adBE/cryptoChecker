import XCTest
@testable import CryptoChecker

/// Volle Namen aus den Paarlisten (gleicher Token) — wie `BaseNamesTest.kt`.
final class BaseNamesTests: XCTestCase {

    func testGateBaseAndQuoteNamesOfTradablePairs() throws {
        let body = """
        [
            {"id":"QTUM_ETH","base":"QTUM","base_name":"Quantum","quote":"ETH","quote_name":"Ethereum","trade_status":"tradable"},
            {"id":"CAT_USDT","base":"CAT","base_name":"Simon's Cat","quote":"USDT","quote_name":"Tether","trade_status":"tradable"},
            {"id":"OLD_USDT","base":"OLD","base_name":"Old Coin","quote":"USDT","quote_name":"Tether","trade_status":"untradable"},
            {"id":"NN_USDT","base":"NN","base_name":null,"quote":"USDT","trade_status":"tradable"}
        ]
        """
        XCTAssertEqual(try GateIo().parseBaseNames(call: 0, body: body),
                       ["QTUM": "Quantum", "ETH": "Ethereum", "CAT": "Simon's Cat", "USDT": "Tether"])
    }

    func testUpbitAndBithumbEnglishNames() throws {
        let body = """
        [
            {"market":"KRW-BTC","korean_name":"비트코인","english_name":"Bitcoin"},
            {"market":"BTC-TON","korean_name":"톤","english_name":"Tokamak Network"},
            {"market":"broken","english_name":"X"}
        ]
        """
        let expected = ["BTC": "Bitcoin", "TON": "Tokamak Network"]
        XCTAssertEqual(try Upbit().parseBaseNames(call: 0, body: body), expected)
        XCTAssertEqual(try Bithumb().parseBaseNames(call: 0, body: body), expected)
    }

    func testLatokenCurrencyNames() throws {
        let currencies = """
        [
            {"id":"a","tag":"MCB","name":"MyCryptoBank"},
            {"id":"b","tag":"USDT","name":"Tether USD"},
            {"id":"c","tag":"","name":"Nameless"}
        ]
        """
        XCTAssertEqual(try Latoken().parseBaseNamesCombined(bodies: [currencies, "[]"]),
                       ["MCB": "MyCryptoBank", "USDT": "Tether USD"])
    }
}
