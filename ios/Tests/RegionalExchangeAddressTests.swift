import XCTest
@testable import CryptoChecker

/// Adressen von bitbank, Coincheck, HashKey Exchange und BTC Markets: Kennung aus der Paarliste oder aus Basis und
/// Gegenwährung gebildet, dazu die gefilterte Sammelabfrage von BTC Markets (je Markt ein «marketId»). Ab Build 187
/// auch Bitkub, Coinone (Pfad «KRW/BTC»), BitoPro und MAX (Auswahl je «markets[]»). Wie
/// `RegionalExchangeAddressTest.kt`.
final class RegionalExchangeAddressTests: XCTestCase {

    func testTickerAddressesUseStoredOrDerivedIds() {
        XCTAssertEqual(Bitbank().tickerAddress(call: 0, info: PairQuery("BTC", "JPY", nil)),
                       "https://public.bitbank.cc/btc_jpy/ticker")
        XCTAssertEqual(Bitbank().tickerAddress(call: 0, info: PairQuery("X", "Y", "eth_jpy")),
                       "https://public.bitbank.cc/eth_jpy/ticker")
        // Bitcoin Cash heisst bei bitbank «bcc»; angezeigt wird BCH
        XCTAssertEqual(Bitbank().tickerAddress(call: 0, info: PairQuery("BCH", "JPY", nil)),
                       "https://public.bitbank.cc/bcc_jpy/ticker")
        XCTAssertEqual(Coincheck().tickerAddress(call: 0, info: PairQuery("BTC", "JPY", nil)),
                       "https://coincheck.com/api/ticker?pair=btc_jpy")
        XCTAssertEqual(HashKey().tickerAddress(call: 0, info: PairQuery("BTC", "HKD", nil)),
                       "https://api-pro.hashkey.com/quote/v1/ticker/24hr?symbol=BTCHKD")
        XCTAssertEqual(BtcMarkets().tickerAddress(call: 0, info: PairQuery("BTC", "AUD", nil)),
                       "https://api.btcmarkets.net/v3/markets/BTC-AUD/ticker")
    }

    func testBtcMarketsBulkIsASortedSelection() {
        let exchange = BtcMarkets()
        let ids = ["XRP-AUD", "BTC-AUD", "BTC-AUD", "", "BAD ID"]
        XCTAssertEqual(exchange.bulkTickersURL(call: 0, pairIds: ids),
                       "https://api.btcmarkets.net/v3/markets/tickers?marketId=BTC-AUD&marketId=XRP-AUD")
        // Nur eine Anfrage; ohne (brauchbare) Kennung und ungefiltert gibt es keine Sammelabfrage
        XCTAssertNil(exchange.bulkTickersURL(call: 1, pairIds: ids))
        XCTAssertNil(exchange.bulkTickersURL(call: 0, pairIds: ["BAD ID"]))
        XCTAssertNil(exchange.bulkTickersURL(call: 0, pairIds: []))
        XCTAssertNil(exchange.bulkTickersURL(call: 0))
        XCTAssertEqual(exchange.bulkKnownPairsURL(call: 0), "https://api.btcmarkets.net/v3/markets")
    }

    func testBuild187AddressesUseStoredOrDerivedIds() {
        XCTAssertEqual(Bitkub().tickerAddress(call: 0, info: PairQuery("btc", "thb", nil)),
                       "https://api.bitkub.com/api/v3/market/ticker?sym=BTC_THB")
        XCTAssertEqual(Coinone().tickerAddress(call: 0, info: PairQuery("BTC", "KRW", nil)),
                       "https://api.coinone.co.kr/public/v2/ticker_new/KRW/BTC")
        XCTAssertEqual(Coinone().tickerAddress(call: 0, info: PairQuery("X", "Y", "ETH-KRW")),
                       "https://api.coinone.co.kr/public/v2/ticker_new/KRW/ETH")
        XCTAssertEqual(BitoPro().tickerAddress(call: 0, info: PairQuery("BTC", "TWD", nil)),
                       "https://api.bitopro.com/v3/tickers/btc_twd")
        XCTAssertEqual(MaxMaicoin().tickerAddress(call: 0, info: PairQuery("BTC", "TWD", nil)),
                       "https://max-api.maicoin.com/api/v3/ticker?market=btctwd")
        // Gesamtlisten: die Adresse bleibt, die Kennung wählt die Zeile
        XCTAssertEqual(Bitpanda().tickerAddress(call: 0, info: PairQuery("BTC", "EUR", nil)),
                       "https://api.bitpanda.com/v1/ticker")
        XCTAssertEqual(CoinSpot().tickerAddress(call: 0, info: PairQuery("XLM", "AUD", nil)),
                       "https://www.coinspot.com.au/pubapi/v2/latest")
    }

    func testMaxBulkIsASortedSelection() {
        let exchange = MaxMaicoin()
        let ids = ["ethtwd", "btctwd", "btctwd", "", "BAD ID"]
        XCTAssertEqual(exchange.bulkTickersURL(call: 0, pairIds: ids),
                       "https://max-api.maicoin.com/api/v3/tickers?markets%5B%5D=btctwd&markets%5B%5D=ethtwd")
        XCTAssertNil(exchange.bulkTickersURL(call: 1, pairIds: ids))
        XCTAssertNil(exchange.bulkTickersURL(call: 0, pairIds: ["BAD ID"]))
        XCTAssertNil(exchange.bulkTickersURL(call: 0))
        XCTAssertEqual(exchange.bulkKnownPairsURL(call: 0), "https://max-api.maicoin.com/api/v3/markets")
    }
}
