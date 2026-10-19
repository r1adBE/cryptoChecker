import XCTest
@testable import CryptoChecker

/// Adressen von bitbank, Coincheck, HashKey Exchange und BTC Markets: Kennung aus der Paarliste oder aus Basis und
/// Gegenwährung gebildet, dazu die gefilterte Sammelabfrage von BTC Markets (je Markt ein «marketId»). Wie
/// `RegionalMarketAddressTest.kt`.
final class RegionalMarketAddressTests: XCTestCase {

    func testTickerAddressesUseStoredOrDerivedIds() {
        XCTAssertEqual(Bitbank().tickerAddress(call: 0, info: PairQuery("BTC", "JPY", nil)),
                       "https://public.bitbank.cc/btc_jpy/ticker")
        XCTAssertEqual(Bitbank().tickerAddress(call: 0, info: PairQuery("X", "Y", "eth_jpy")),
                       "https://public.bitbank.cc/eth_jpy/ticker")
        XCTAssertEqual(Coincheck().tickerAddress(call: 0, info: PairQuery("BTC", "JPY", nil)),
                       "https://coincheck.com/api/ticker?pair=btc_jpy")
        XCTAssertEqual(HashKey().tickerAddress(call: 0, info: PairQuery("BTC", "HKD", nil)),
                       "https://api-pro.hashkey.com/quote/v1/ticker/24hr?symbol=BTCHKD")
        XCTAssertEqual(BtcMarkets().tickerAddress(call: 0, info: PairQuery("BTC", "AUD", nil)),
                       "https://api.btcmarkets.net/v3/markets/BTC-AUD/ticker")
    }

    func testBtcMarketsBulkIsASortedSelection() {
        let market = BtcMarkets()
        let ids = ["XRP-AUD", "BTC-AUD", "BTC-AUD", "", "BAD ID"]
        XCTAssertEqual(market.bulkTickersURL(call: 0, pairIds: ids),
                       "https://api.btcmarkets.net/v3/markets/tickers?marketId=BTC-AUD&marketId=XRP-AUD")
        // Nur eine Anfrage; ohne (brauchbare) Kennung und ungefiltert gibt es keine Sammelabfrage
        XCTAssertNil(market.bulkTickersURL(call: 1, pairIds: ids))
        XCTAssertNil(market.bulkTickersURL(call: 0, pairIds: ["BAD ID"]))
        XCTAssertNil(market.bulkTickersURL(call: 0, pairIds: []))
        XCTAssertNil(market.bulkTickersURL(call: 0))
        XCTAssertEqual(market.bulkKnownPairsURL(call: 0), "https://api.btcmarkets.net/v3/markets")
    }
}
